package dev.handoff.hostspike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager

/** Receives incoming SMS as a non-default app. Google Messages still receives and stores them as usual. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) return
        SelfStats.count("sms_broadcast")
        val from = msgs[0].originatingAddress
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val sentAt = msgs[0].timestampMillis
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        EventLog.log(
            ctx, "sms_received",
            "from" to from,
            "chars" to body.length,
            "segments" to msgs.size,
            "sub_id" to subId,
            "ms_since_sent" to (System.currentTimeMillis() - sentAt),
            "service_running" to HostService.running
        )
    }
}
