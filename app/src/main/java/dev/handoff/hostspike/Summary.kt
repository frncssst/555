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
                "permission_missing", "command_server_error", "mms_read_denied", "mms_read_error",
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
            appendLine("SMS received ${c("sms_received")}; MMS seen ${c("mms_seen")}")
            appendLine("SMS sent: requested ${c("sms_send_requested")}, sent ok $smsSentOk, failed $smsSentFail, delivered $smsDelivered")
            appendLine()
            appendLine("SIM changes ${c("sims_changed")}")
            if (errors.isNotEmpty()) {
                appendLine()
                appendLine("Problems (${errors.size}):")
                errors.distinct().take(10).forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }
}
