package dev.handoff.hostspike

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the event log into a plain-English report of the Phase 1 tests and the battery measurements. */
object Summary {

    // Assumptions behind the app's battery estimate. Shown in the report so the method is visible.
    private const val CPU_MA = 150.0          // average current while the app is using the processor
    private const val MAH_PER_WAKEUP = 0.01   // about 1 second of the phone being kept awake
    private const val MAH_PER_MB = 1.0        // cautious figure for mobile data; Wi-Fi uses less
    private const val DEFAULT_CAPACITY_MAH = 5000.0

    private val clock = ThreadLocal.withInitial { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
    private fun at(t: Long) = clock.get()!!.format(Date(t))

    private fun duration(ms: Long): String {
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun f1(d: Double) = "%.1f".format(d)
    private fun f2(d: Double) = "%.2f".format(d)
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

    private fun wakeupSum(o: JSONObject?): Long {
        if (o == null) return 0
        var s = 0L
        for (k in o.keys()) s += o.optLong(k)
        return s
    }

    fun build(ctx: Context): String {
        val events = EventLog.readAll(ctx).lineSequence().mapNotNull { line ->
            if (line.isBlank()) null else try { JSONObject(line) } catch (_: Exception) { null }
        }.toList()
        if (events.isEmpty()) return "No events yet. Grant permissions, then start the host service."

        val counts = HashMap<String, Int>()
        for (o in events) counts[o.optString("type")] = (counts[o.optString("type")] ?: 0) + 1
        fun c(k: String) = counts[k] ?: 0

        val first = events.first().optLong("t")
        val last = events.last().optLong("t")

        return buildString {
            battery(events, last)
            appendLine()
            reliability(events, first, last, ::c)
            appendLine()
            calls(events, ::c)
            appendLine()
            messages(events, ::c)
            appendLine()
            sim(events, ::c)
            problems(events)
        }.trimEnd()
    }

    // ---- Battery ----

    private fun StringBuilder.battery(events: List<JSONObject>, last: Long) {
        appendLine("BATTERY")

        // Latest reading of the phone's battery.
        val latest = events.lastOrNull { it.has("battery_pct") }
        val latestPct = latest?.optInt("battery_pct", -1) ?: -1
        val latestCharge = latest?.optLong("charge_uah", -1) ?: -1
        val pluggedNow = latest?.let { it.optString("plugged", if (it.optBoolean("charging")) "charger" else "unplugged") }

        // Battery size, worked out from the charge counter and the percentage.
        val capacityFrom = events.lastOrNull { it.optLong("charge_uah", -1) > 0 && it.optInt("battery_pct", 0) >= 20 }
        val capacity = capacityFrom?.let { it.optLong("charge_uah") / 1000.0 / (it.optInt("battery_pct") / 100.0) }
        val capacityMah = capacity ?: DEFAULT_CAPACITY_MAH

        // When was it last unplugged? Prefer the unplug event; otherwise a heartbeat that stopped charging.
        var unplugIdx = events.indexOfLast { it.optString("type") == "power_disconnected" }
        if (unplugIdx < 0) {
            var prevCharging: Boolean? = null
            events.forEachIndexed { i, o ->
                if (o.optString("type") == "heartbeat") {
                    val charging = o.optBoolean("charging")
                    if (prevCharging == true && !charging) unplugIdx = i
                    prevCharging = charging
                }
            }
        }
        val lastPlugIn = events.lastOrNull { it.optString("type") == "power_connected" }

        if (pluggedNow != null && pluggedNow != "unplugged") {
            val since = lastPlugIn?.let { " since ${at(it.optLong("t"))}" } ?: ""
            appendLine("Charging now ($pluggedNow)$since, at $latestPct%.")
        }

        if (unplugIdx < 0) {
            appendLine("No unplug seen yet in this log. Unplug the phone to start a battery measurement.")
        } else {
            val u = events[unplugIdx]
            val uT = u.optLong("t")
            val uPct = u.optInt("battery_pct", -1)
            val uCharge = u.optLong("charge_uah", -1)
            // If it has been plugged in again since, measure up to that point.
            val endIdx = (unplugIdx + 1 until events.size).firstOrNull { events[it].optString("type") == "power_connected" }
                ?: (events.size - 1)
            val end = events[endIdx]
            val endT = end.optLong("t")
            val endPct = if (end.has("battery_pct")) end.optInt("battery_pct") else latestPct
            val endCharge = if (end.has("charge_uah")) end.optLong("charge_uah", -1) else latestCharge
            val hours = (endT - uT) / 3_600_000.0

            appendLine("Last unplugged: ${at(uT)}" + (if (uPct >= 0) ", at $uPct%." else "."))
            if (hours > 0.05) {
                val pctLine = if (uPct >= 0 && endPct >= 0) {
                    val drop = uPct - endPct
                    "the battery went from $uPct% to $endPct%, about ${f1(drop / hours)}% an hour."
                } else "battery percentage not available."
                appendLine("Unplugged for ${duration(endT - uT)}: $pctLine")
            }
            val phoneMah = if (uCharge > 0 && endCharge > 0) (uCharge - endCharge) / 1000.0 else null
            if (phoneMah != null && hours > 0.05) {
                appendLine("The whole phone used about ${phoneMah.toInt()} mAh in that time (${f1(phoneMah / hours)} mAh an hour), from the battery's own charge counter.")
            }

            // The app's own use since unplugging, summed across any app restarts.
            var cpuMs = 0L; var netBytes = 0L; var wakeups = 0L
            var baseCpu = u.optLong("self_cpu_ms_total", -1)
            var baseNet = u.optLong("self_net_bytes_total", -1)
            var baseWake = if (u.has("self_wakeups_total")) wakeupSum(u.optJSONObject("self_wakeups_total")) else -1L
            var lastCpu = baseCpu; var lastNet = baseNet; var lastWake = baseWake
            for (i in unplugIdx + 1..endIdx) {
                val o = events[i]
                if (o.optString("type") == "service_started") {
                    if (baseCpu >= 0) { cpuMs += lastCpu - baseCpu; netBytes += maxOf(0L, lastNet - baseNet); wakeups += lastWake - baseWake }
                    baseCpu = -1; baseNet = -1; baseWake = -1
                    continue
                }
                if (!o.has("self_cpu_ms_total")) continue
                val cpu = o.optLong("self_cpu_ms_total")
                val net = o.optLong("self_net_bytes_total", -1)
                val wake = wakeupSum(o.optJSONObject("self_wakeups_total"))
                if (baseCpu < 0) { baseCpu = cpu; baseNet = net; baseWake = wake }
                lastCpu = cpu; lastNet = net; lastWake = wake
            }
            if (baseCpu >= 0) { cpuMs += lastCpu - baseCpu; netBytes += maxOf(0L, lastNet - baseNet); wakeups += lastWake - baseWake }

            if (hours > 0.05 && (cpuMs > 0 || wakeups > 0)) {
                val cpuS = cpuMs / 1000.0
                val mb = netBytes / 1_048_576.0
                appendLine("This app used ${f1(cpuS)} seconds of processor time (${f1(cpuS / hours)} s an hour), ${f1(mb * 1024)} KB of data, and was woken ${wakeups} times.")
                val appMah = cpuS * CPU_MA / 3600.0 + wakeups * MAH_PER_WAKEUP + mb * MAH_PER_MB
                val perHour = appMah / hours
                val pctPerDay = perHour * 24 / capacityMah * 100
                appendLine("Estimated battery used by this app: about ${f2(perHour)} mAh an hour, roughly ${f1(pctPerDay)}% of a full charge per day.")
                if (phoneMah != null && phoneMah > 0) {
                    appendLine("That is about ${f1(appMah / phoneMah * 100)}% of everything the phone used since it was unplugged.")
                }
            } else if (hours > 0.05) {
                appendLine("The app's own usage isn't in this log yet (it starts with version 0.4.1).")
            }
        }

        val capNote = if (capacity != null) "your battery holds about ${capacityMah.toInt()} mAh (worked out from its charge counter)"
            else "your battery is assumed to hold ${DEFAULT_CAPACITY_MAH.toInt()} mAh"
        appendLine("How the app estimate works: processor seconds × ${CPU_MA.toInt()} mA, plus ${MAH_PER_WAKEUP} mAh per wake-up, plus ${MAH_PER_MB.toInt()} mAh per MB of data; $capNote. It is a rough guide. A night with the service paused would give a more accurate comparison.")

        // Battery health readings, when the phone provides them.
        latest?.let { o ->
            val bits = ArrayList<String>()
            if (o.has("temp_c") && !o.isNull("temp_c")) bits.add("temperature ${o.optDouble("temp_c")}°C")
            if (o.has("cycle_count") && !o.isNull("cycle_count")) bits.add("${o.optInt("cycle_count")} charge cycles")
            if (o.has("voltage_mv") && o.optInt("voltage_mv", -1) > 0) bits.add("${o.optInt("voltage_mv")} mV")
            if (bits.isNotEmpty()) appendLine("Battery now: " + bits.joinToString(", ") + ".")
        }
    }

    // ---- Reliability ----

    private fun StringBuilder.reliability(events: List<JSONObject>, first: Long, last: Long, c: (String) -> Int) {
        appendLine("STAYING RUNNING")
        appendLine("This log covers ${duration(last - first)}, from ${at(first)} to ${at(last)}.")
        appendLine("The service started ${plural(c("service_started"), "time")} and was stopped ${plural(c("service_stopped"), "time")}. The phone restarted ${plural(c("boot_completed"), "time")}" +
            if (c("boot_completed") > 0) ", and the app came back on its own each time." else ".")

        val beats = events.filter { it.optString("type") == "heartbeat" }
        val late = beats.count { it.optBoolean("late") }
        val maxGap = beats.maxOfOrNull { it.optDouble("gap_min", 0.0) } ?: 0.0
        appendLine("Heartbeats: ${beats.size} logged, $late late (the target is every 15 minutes; the longest gap was ${f1(maxGap)} min). A late heartbeat means Android delayed the app's timer, not that the app stopped.")

        val dozeBeats = beats.count { it.optBoolean("doze") }
        if (beats.isNotEmpty()) appendLine("The phone was in deep sleep (Doze) for $dozeBeats of those heartbeats.")

        val lastBeat = beats.lastOrNull()
        if (lastBeat != null) {
            val bucket = lastBeat.optString("standby_bucket")
            if (bucket.isNotEmpty()) {
                appendLine("Android currently rates the app as \"${bucket.replace('_', ' ')}\" for background use" +
                    (if (lastBeat.optBoolean("background_restricted")) ", and background use is RESTRICTED." else "."))
            }
        }
        if (lastBeat != null && lastBeat.has("memory_kb")) {
            appendLine("The app is using about ${lastBeat.optInt("memory_kb") / 1024} MB of memory.")
        }

        val exits = events.lastOrNull { it.optString("type") == "service_started" }?.optJSONArray("recent_process_exits")
        val lastExit = exits?.optJSONObject(0)
        if (lastExit != null && lastExit.has("reason")) {
            appendLine("Last time Android ended the app: ${lastExit.optString("time").take(16)}, because of: ${lastExit.optString("reason")}.")
        }
    }

    // ---- Calls ----

    private fun StringBuilder.calls(events: List<JSONObject>, c: (String) -> Int) {
        appendLine("CALLS")
        val rang = c("call_ringing")
        val byScreening = c("call_screened")
        val byCallLog = events.count { it.optString("type") == "call_number_from_call_log_access" && it.optString("number") != "empty" }
        appendLine("${plural(rang, "call")} rang. The caller's number was seen by call screening ${plural(byScreening, "time")} (not used for saved contacts) and by call-log access ${plural(byCallLog, "time")}.")

        val answers = events.filter { it.optString("type") == "answer_requested" }
        val answerLat = events.filter { it.optString("type") == "call_answered" && it.optString("trigger") == "answer" }.map { it.optLong("ms_from_command") }
        if (answers.isNotEmpty()) {
            val ok = answers.count { it.optBoolean("ok") }
            appendLine("Answer commands: ${answers.size} sent, $ok accepted." + median(answerLat, " The call connected a median of %d ms after the command."))
        }
        val declines = events.filter { it.optString("type") == "decline_requested" }
        val declineLat = events.filter { it.optString("type") == "call_ended_while_ringing" && it.optString("trigger") == "decline" }.map { it.optLong("ms_from_command") }
        if (declines.isNotEmpty()) {
            val ok = declines.count { it.optBoolean("returned") }
            appendLine("Decline or hang-up commands: ${declines.size} sent, $ok accepted." + median(declineLat, " Ringing calls ended a median of %d ms after the command."))
        }
        val remote = (answers + declines).count { it.optString("source") == "lan" }
        if (remote > 0) appendLine("$remote of those commands came from another device on the same Wi-Fi.")
    }

    private fun median(l: List<Long>, template: String): String =
        if (l.isEmpty()) "" else template.format(l.sorted()[l.size / 2])

    // ---- Messages ----

    private fun StringBuilder.messages(events: List<JSONObject>, c: (String) -> Int) {
        appendLine("MESSAGES")
        val stored = events.filter { it.optString("type") == "sms_stored" }
        val mms = events.filter { it.optString("type") == "mms_stored" }
        val smsIn = stored.count { it.optString("direction") == "in" }
        val smsOut = stored.size - smsIn
        val mmsIn = mms.filter { it.optString("direction") == "in" }
        val mmsOut = mms.filter { it.optString("direction") != "in" }
        appendLine("Texts seen arriving: ${c("sms_received")}.")
        appendLine("From the phone's message store (any app, including Google Messages): ${plural(smsIn, "text")} in and $smsOut out; ${plural(mmsIn.size, "picture message")} in (${mmsIn.count { it.optBoolean("complete") }} with content) and ${mmsOut.size} out (${mmsOut.count { it.optBoolean("complete") }} with content).")
        val sent = events.count { it.optString("type") == "sms_sent" && it.optBoolean("ok") }
        val failed = events.count { it.optString("type") == "sms_sent" && !it.optBoolean("ok") }
        val delivered = events.count { it.optString("type") == "sms_delivered" && it.optBoolean("ok") }
        if (c("sms_send_requested") > 0) {
            appendLine("Texts sent by this app: ${c("sms_send_requested")} requested, $sent sent, $failed failed, $delivered delivery reports" +
                if (delivered == 0 && sent > 0) " (your carrier may not return them)." else ".")
        }
    }

    // ---- SIM ----

    private fun StringBuilder.sim(events: List<JSONObject>, c: (String) -> Int) {
        appendLine("SIM AND MAIN-PHONE ROLE")
        val longest = events.filter { it.optString("type") == "host_unknown_ended" }.maxOfOrNull { it.optDouble("duration_min", 0.0) } ?: 0.0
        appendLine("The SIM reported ${plural(c("sims_changed"), "change")}. The main-phone role changed ${plural(c("host_changed"), "time")}, and ${plural(c("host_blip_ignored"), "short blip")} ${if (c("host_blip_ignored") == 1) "was" else "were"} ignored.")
        if (c("host_unknown_started") > 0) {
            appendLine("The phone number was briefly unknown ${plural(c("host_unknown_started"), "time")} (longest ${f1(longest)} min), for example with no signal. The phone kept its main-phone role throughout.")
        }
    }

    // ---- Problems ----

    private fun StringBuilder.problems(events: List<JSONObject>) {
        val types = setOf(
            "permission_missing", "command_server_error", "mms_read_denied", "mms_read_error",
            "messages_read_denied", "messages_read_error", "sms_send_failed", "boot_start_failed"
        )
        val errors = events.filter { it.optString("type") in types }
            .map { it.optString("type").replace('_', ' ') + ": " + it.optString("error", it.optString("permission", "")) }
            .distinct()
        if (errors.isEmpty()) return
        appendLine()
        appendLine("PROBLEMS")
        errors.take(10).forEach { appendLine("- $it") }
    }
}
