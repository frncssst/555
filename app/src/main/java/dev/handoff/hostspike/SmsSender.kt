package dev.handoff.hostspike

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicInteger

/** Sends SMS from the main number as a non-default app, tracking sent and delivered reports. */
object SmsSender {
    const val ACTION_SENT = "dev.handoff.hostspike.SMS_SENT"
    const val ACTION_DELIVERED = "dev.handoff.hostspike.SMS_DELIVERED"
    private val requestCodes = AtomicInteger(1000)

    fun send(ctx: Context, to: String, body: String, source: String): String {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            EventLog.log(ctx, "sms_send_failed", "reason" to "SEND_SMS not granted")
            return "SEND_SMS permission not granted"
        }
        if (to.isBlank() || body.isBlank()) return "Number and message are both required"
        val id = System.currentTimeMillis()
        return try {
            val sms = ctx.getSystemService(SmsManager::class.java)
            val parts = sms.divideMessage(body)
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent.add(statusIntent(ctx, ACTION_SENT, id, i))
                delivered.add(statusIntent(ctx, ACTION_DELIVERED, id, i))
            }
            if (parts.size == 1) {
                sms.sendTextMessage(to, null, body, sent[0], delivered[0])
            } else {
                sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
            }
            EventLog.log(
                ctx, "sms_send_requested",
                "id" to id, "to" to to, "chars" to body.length, "parts" to parts.size, "source" to source
            )
            "Send requested (id $id, ${parts.size} part(s))"
        } catch (e: Exception) {
            EventLog.log(ctx, "sms_send_failed", "id" to id, "error" to e.toString())
            "Send failed: $e"
        }
    }

    private fun statusIntent(ctx: Context, action: String, id: Long, part: Int): PendingIntent {
        val i = Intent(ctx, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra("id", id)
            .putExtra("part", part)
        // Mutable so the platform can attach its result extras; the intent is explicit to this app.
        return PendingIntent.getBroadcast(
            ctx, requestCodes.incrementAndGet(), i,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val part = intent.getIntExtra("part", -1)
        val ok = resultCode == Activity.RESULT_OK
        val result = if (ok) "ok" else resultLabel(resultCode)
        when (intent.action) {
            SmsSender.ACTION_SENT ->
                EventLog.log(ctx, "sms_sent", "id" to id, "part" to part, "ok" to ok, "result" to result)
            SmsSender.ACTION_DELIVERED ->
                EventLog.log(ctx, "sms_delivered", "id" to id, "part" to part, "ok" to ok, "result" to result)
        }
    }

    private fun resultLabel(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        else -> "code_$code"
    }
}
