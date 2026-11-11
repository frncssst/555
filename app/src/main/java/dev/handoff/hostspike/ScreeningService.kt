package dev.handoff.hostspike

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Holding the call screening role lets the app see the caller's number before the phone rings.
 * It never blocks or alters calls: it records the number and lets the call through unchanged.
 * Android only invokes non-dialler screening apps for some calls (commonly numbers not in contacts),
 * so the log records which calls it saw.
 */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
            val number = details.handle?.schemeSpecificPart
            CallState.screenedNumber = number
            CallState.screenedAt = System.currentTimeMillis()
            EventLog.log(
                this, "call_screened",
                "number" to number,
                "presentation" to details.handlePresentation
            )
        }
        respondToCall(details, CallResponse.Builder().build())
    }
}
