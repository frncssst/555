package dev.handoff.hostspike

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.TelecomManager

/**
 * Answers or declines the ringing cellular call without being the default phone app.
 * Both methods are deprecated but still public; whether they work on current Android
 * for a non-default app is exactly what this spike tests.
 */
object CallControl {

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(ctx: Context, source: String): Boolean {
        val since = CallState.msSinceRinging()
        CallState.commandType = "answer"
        CallState.commandAt = System.currentTimeMillis()
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            tm.acceptRingingCall()
            EventLog.log(
                ctx, "answer_requested",
                "source" to source, "ok" to true,
                "state_at_request" to CallState.label(), "ms_from_ringing" to since
            )
            true
        } catch (e: Exception) {
            EventLog.log(ctx, "answer_requested", "source" to source, "ok" to false, "error" to e.toString())
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun decline(ctx: Context, source: String): Boolean {
        val since = CallState.msSinceRinging()
        CallState.commandType = "decline"
        CallState.commandAt = System.currentTimeMillis()
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            val returned = tm.endCall()
            EventLog.log(
                ctx, "decline_requested",
                "source" to source, "returned" to returned,
                "state_at_request" to CallState.label(), "ms_from_ringing" to since
            )
            returned
        } catch (e: Exception) {
            EventLog.log(ctx, "decline_requested", "source" to source, "returned" to false, "error" to e.toString())
            false
        }
    }
}
