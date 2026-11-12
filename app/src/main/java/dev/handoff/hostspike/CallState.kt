package dev.handoff.hostspike

import android.telephony.TelephonyManager

/** Shared, in-memory view of the current call, written by the service and read by everything else. */
object CallState {
    @Volatile var state: Int = TelephonyManager.CALL_STATE_IDLE
    @Volatile var ringingAt: Long = 0

    /** Number seen by the call screening service, which is only invoked for some calls. */
    @Volatile var screenedNumber: String? = null
    @Volatile var screenedAt: Long = 0

    /** Number from the phone-state listener, available for every call when call-log access is granted. */
    @Volatile var listenerNumber: String? = null
    @Volatile var listenerAt: Long = 0

    /** Last command the app issued, so the log can tell app-triggered outcomes from manual ones. */
    @Volatile var commandType: String? = null
    @Volatile var commandAt: Long = 0

    fun label(s: Int = state): String = when (s) {
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "idle"
    }

    fun msSinceRinging(): Long? =
        if (state == TelephonyManager.CALL_STATE_RINGING && ringingAt > 0) System.currentTimeMillis() - ringingAt else null

    /** The command issued during the current ringing period, if any. */
    fun commandDuringRinging(): String? =
        if (commandAt > 0 && commandAt >= ringingAt) commandType else null
}
