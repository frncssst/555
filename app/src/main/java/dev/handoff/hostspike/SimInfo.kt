package dev.handoff.hostspike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the active SIM subscriptions so the app can tell when the main SIM arrives or leaves.
 * Many carriers do not store the phone number on the SIM, so an empty number is itself a finding.
 */
object SimInfo {
    data class Sim(val subId: Int, val slot: Int, val carrier: String, val displayName: String, val number: String)

    private fun has(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun snapshot(ctx: Context): List<Sim> {
        if (!has(ctx, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val list = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        return list.map { info ->
            val number = if (has(ctx, Manifest.permission.READ_PHONE_NUMBERS)) {
                try {
                    sm.getPhoneNumber(info.subscriptionId)
                } catch (e: Exception) {
                    ""
                }
            } else ""
            Sim(
                subId = info.subscriptionId,
                slot = info.simSlotIndex,
                carrier = info.carrierName?.toString() ?: "",
                displayName = info.displayName?.toString() ?: "",
                number = number
            )
        }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(9)

    /** True or false when it can tell, null when the main number or SIM numbers are unknown. */
    fun isHost(ctx: Context, sims: List<Sim>): Boolean? {
        val main = digits(Prefs.mainNumber(ctx))
        if (main.length < 9) return null
        val known = sims.filter { digits(it.number).length == 9 }
        if (known.isEmpty()) return if (sims.isEmpty()) false else null
        return known.any { digits(it.number) == main }
    }

    fun signature(sims: List<Sim>): String = sims.joinToString("|") { "${it.subId}:${it.slot}:${it.number}" }

    fun toJson(sims: List<Sim>): JSONArray = JSONArray().apply {
        for (s in sims) {
            put(JSONObject().apply {
                put("sub_id", s.subId)
                put("slot", s.slot)
                put("carrier", s.carrier)
                put("name", s.displayName)
                put("number", s.number.ifEmpty { "(not provided by SIM)" })
            })
        }
    }
}
