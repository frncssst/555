package dev.handoff.hostspike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Answer and Decline buttons on the ringing notification. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER = "dev.handoff.hostspike.ANSWER"
        const val ACTION_DECLINE = "dev.handoff.hostspike.DECLINE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallControl.answer(ctx, "notification")
            ACTION_DECLINE -> CallControl.decline(ctx, "notification")
        }
    }
}

/** Restarts the host service after a reboot, if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val autoStart = Prefs.autoStart(ctx)
        EventLog.log(ctx, "boot_completed", "auto_start" to autoStart)
        if (!autoStart) return
        try {
            HostService.start(ctx)
        } catch (e: Exception) {
            EventLog.log(ctx, "boot_start_failed", "error" to e.toString())
        }
    }
}
