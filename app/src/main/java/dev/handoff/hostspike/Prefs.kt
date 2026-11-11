package dev.handoff.hostspike

import android.content.Context
import java.security.SecureRandom

object Prefs {
    const val PORT = 8765

    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun mainNumber(ctx: Context): String = p(ctx).getString("main_number", "") ?: ""
    fun setMainNumber(ctx: Context, v: String) = p(ctx).edit().putString("main_number", v.trim()).apply()

    /** "none", "answer" or "decline": what the app does by itself a few seconds after a call starts ringing. */
    fun autoAction(ctx: Context): String = p(ctx).getString("auto_action", "none") ?: "none"
    fun setAutoAction(ctx: Context, v: String) = p(ctx).edit().putString("auto_action", v).apply()

    fun autoDelaySec(ctx: Context): Int = p(ctx).getInt("auto_delay", 3)
    fun setAutoDelaySec(ctx: Context, v: Int) = p(ctx).edit().putInt("auto_delay", v.coerceIn(1, 30)).apply()

    fun autoStart(ctx: Context): Boolean = p(ctx).getBoolean("auto_start", false)
    fun setAutoStart(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_start", v).apply()

    /** Shared secret for the local command page, so other people on the same Wi-Fi cannot control the phone. */
    fun token(ctx: Context): String {
        val existing = p(ctx).getString("token", null)
        if (existing != null) return existing
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val t = (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        p(ctx).edit().putString("token", t).apply()
        return t
    }
}
