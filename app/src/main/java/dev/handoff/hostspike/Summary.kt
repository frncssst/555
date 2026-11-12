package dev.handoff.hostspike

import android.content.Context
import org.json.JSONObject

/** Turns the event log into the numbers the Phase 1 exit tests need. */
object Summary {
    fun build(ctx: Context): String {
        val counts = HashMap<String, Int>()
        var first = 0L
        var last = 0L
        var heartbeats = 0
        var lateBeats = 0
        var maxGap = 0.0
        var batteryFirst = -1
        var batteryLast = -1
        var answerOk = 0
        var answerErr = 0
        var declineTrue = 0
        var declineFalse = 0
        var declineErr = 0
        val answerLatency = ArrayList<Long>()
        val declineLatency = ArrayList<Long>()
        var smsSentOk = 0
        var smsSentFail = 0
        var smsDelivered = 0
        var smsIn = 0
        var smsOut = 0
        var mmsIn = 0
        var mmsInComplete = 0
        var mmsOut = 0
        var mmsOutComplete = 0
        var unknownLongest = 0.0
        val errors = ArrayList<String>()

        for (line in EventLog.readAll(ctx).lineSequence()) {
            if (line.isBlank()) continue
            val o = try {
                JSONObject(line)
            } catch (e: Exception) {
                continue
            }
            val type = o.optString("type")
            val t = o.optLong("t")
            if (first == 0L) first = t
            last = t
            counts[type] = (counts[type] ?: 0) + 1
            when (type) {
                "heartbeat" -> {
                    heartbeats++
                    if (o.optBoolean("late")) lateBeats++
                    val g = o.optDouble("gap_min", 0.0)
                    if (g > maxGap) maxGap = g
                    val b = o.optInt("battery_pct", -1)
                    if (batteryFirst < 0) batteryFirst = b
                    batteryLast = b
                }
                "answer_requested" -> if (o.optBoolean("ok")) answerOk++ else answerErr++
                "decline_requested" -> when {
                    o.has("error") -> declineErr++
                    o.optBoolean("returned") -> declineTrue++
                    else -> declineFalse++
                }
                "call_answered" -> if (o.optString("trigger") == "answer") answerLatency.add(o.optLong("ms_from_command"))
                "call_ended_while_ringing" -> if (o.optString("trigger") == "decline") declineLatency.add(o.optLong("ms_from_command"))
                "sms_sent" -> if (o.optBoolean("ok")) smsSentOk++ else smsSentFail++
                "sms_delivered" -> if (o.optBoolean("ok")) smsDelivered++
                "sms_stored" -> if (o.optString("direction") == "in") smsIn++ else smsOut++
                "mms_stored" -> if (o.optString("direction") == "in") {
                    mmsIn++
                    if (o.optBoolean("complete")) mmsInComplete++
                } else {
                    mmsOut++
                    if (o.optBoolean("complete")) mmsOutComplete++
                }
                "host_unknown_ended" -> unknownLongest = maxOf(unknownLongest, o.optDouble("duration_min", 0.0))
                "permission_missing", "command_server_error", "mms_read_denied", "mms_read_error",
                "messages_read_denied", "messages_read_error",
                "sms_send_failed", "boot_start_failed" -> errors.add(type + " " + o.optString("error", o.optString("permission", "")))
            }
        }

        if (first == 0L) return "No events yet. Grant permissions, then start the host service."

        val hours = (last - first) / 3_600_000.0
        fun c(k: String) = counts[k] ?: 0
        fun median(l: List<Long>) = if (l.isEmpty()) "n/a" else "${l.sorted()[l.size / 2]} ms"

        return buildString {
            appendLine("Run: %.1f h (%s to %s)".format(hours, EventLog.format(first), EventLog.format(last)))
            appendLine("Service starts ${c("service_started")}, stops ${c("service_stopped")}, reboots seen ${c("boot_completed")}")
            appendLine("Heartbeats $heartbeats, late $lateBeats, longest gap %.1f min".format(maxGap))
            if (batteryFirst >= 0) appendLine("Battery $batteryFirst% to $batteryLast% (check charging in the log)")
            appendLine()
            appendLine("Calls ringing ${c("call_ringing")}, caller number seen by screening ${c("call_screened")}")
            appendLine("Answer commands: ok $answerOk, errors $answerErr; answered after command ${answerLatency.size}, median ${median(answerLatency)}")
            appendLine("Decline commands: returned true $declineTrue, false $declineFalse, errors $declineErr; ended after command ${declineLatency.size}, median ${median(declineLatency)}")
            appendLine()
            appendLine("SMS received (broadcast) ${c("sms_received")}")
            appendLine("Message store, any app: SMS in $smsIn, out $smsOut; MMS in $mmsIn ($mmsInComplete with content), out $mmsOut ($mmsOutComplete with content)")
            appendLine("SMS sent: requested ${c("sms_send_requested")}, sent ok $smsSentOk, failed $smsSentFail, delivered $smsDelivered")
            appendLine()
            appendLine("SIM raw events ${c("sims_changed")}; host changes ${c("host_changed")}, blips ignored ${c("host_blip_ignored")}")
            appendLine("Host unknown periods ${c("host_unknown_started")}, longest %.1f min".format(unknownLongest))
            if (errors.isNotEmpty()) {
                appendLine()
                appendLine("Problems (${errors.size}):")
                errors.distinct().take(10).forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }
}
