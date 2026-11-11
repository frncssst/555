package dev.handoff.hostspike

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.net.Inet4Address

object Status {
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    fun lanAddress(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    fun build(ctx: Context): JSONObject {
        val now = System.currentTimeMillis()
        val sims = SimInfo.snapshot(ctx)
        val (pct, charging) = battery(ctx)
        return JSONObject().apply {
            put("time", EventLog.format(now))
            put("service_running", HostService.running)
            put("call_state", CallState.label())
            if (CallState.state == TelephonyManager.CALL_STATE_RINGING) {
                put("ringing_for_ms", now - CallState.ringingAt)
            }
            put("last_screened_number", CallState.screenedNumber ?: JSONObject.NULL)
            put("main_number", Prefs.mainNumber(ctx).ifEmpty { "(not set)" })
            put("is_host", SimInfo.isHost(ctx, sims) ?: "unknown")
            put("sims", SimInfo.toJson(sims))
            put("battery_pct", pct)
            put("charging", charging)
            put("last_heartbeat", HostService.lastHeartbeatLabel)
            put("auto_action", Prefs.autoAction(ctx))
        }
    }
}
