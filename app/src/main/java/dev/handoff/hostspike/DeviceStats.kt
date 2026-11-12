package dev.handoff.hostspike

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-wide readings logged with each heartbeat and power event: battery charge and current,
 * screen, network, signal and how Android is treating the app. All are cheap reads, taken only
 * when an event is logged.
 */
object DeviceStats {

    private fun okInt(v: Int?): Int? = v?.takeIf { it != Int.MIN_VALUE && it != 0 }

    fun battery(ctx: Context): Array<Pair<String, Any?>> = try {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> "unplugged"
        }
        val tempTenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else null
        arrayOf(
            "battery_pct" to pct,
            "plugged" to plugged,
            "charge_uah" to okInt(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)),
            "current_now_ua" to okInt(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)),
            "current_avg_ua" to okInt(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)),
            "voltage_mv" to i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
            "temp_c" to if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null,
            "cycle_count" to cycles?.takeIf { it >= 0 }
        )
    } catch (e: Exception) {
        arrayOf("battery_read_error" to e.toString())
    }

    fun phone(ctx: Context): Array<Pair<String, Any?>> = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        val signal = try {
            ctx.getSystemService(TelephonyManager::class.java).signalStrength?.level
        } catch (_: Exception) {
            null
        }
        val bucket = when (usm.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "other_${usm.appStandbyBucket}"
        }
        arrayOf(
            "screen_on" to pm.isInteractive,
            "network" to network,
            "signal_level" to signal,
            "standby_bucket" to bucket,
            "background_restricted" to am.isBackgroundRestricted,
            "process_uptime_min" to Math.round((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 6000.0) / 10.0,
            "memory_kb" to Debug.getPss()
        )
    } catch (e: Exception) {
        arrayOf("phone_read_error" to e.toString())
    }

    /** Why Android ended the app's process the last few times, logged when the service starts. */
    fun recentExits(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            for (e in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3)) {
                out.put(JSONObject().apply {
                    put("time", EventLog.format(e.timestamp))
                    put("reason", exitReason(e.reason))
                    put("description", e.description ?: JSONObject.NULL)
                })
            }
        } catch (e: Exception) {
            out.put(JSONObject().put("error", e.toString()))
        }
        return out
    }

    fun exitReason(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "app exited itself"
        ApplicationExitInfo.REASON_SIGNALED -> "stopped by a system signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too many resources"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user asked (force stop or update)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped it"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on stopped"
        ApplicationExitInfo.REASON_OTHER -> "other system reason"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
        else -> "reason code $r"
    }
}
