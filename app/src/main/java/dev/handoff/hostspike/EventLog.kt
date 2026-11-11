package dev.handoff.hostspike

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Append-only event log, one JSON object per line, stored in the app's private files folder.
 * Every test result in the spike is read back from this log.
 */
object EventLog {
    private const val TAG = "HostSpike"
    private const val MAX_RECENT = 300

    private val recent = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var file: File? = null
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun format(t: Long): String = dateFormat.get()!!.format(Date(t))

    @Synchronized
    fun setup(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "events.jsonl")
        file = f
        if (f.exists()) {
            f.readLines().takeLast(MAX_RECENT).forEach { recent.addLast(it) }
        }
    }

    fun log(ctx: Context, type: String, vararg fields: Pair<String, Any?>) {
        val line: String
        synchronized(this) {
            setup(ctx)
            val now = System.currentTimeMillis()
            val o = JSONObject()
            o.put("t", now)
            o.put("time", format(now))
            o.put("type", type)
            for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
            line = o.toString()
            try {
                file!!.appendText(line + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Could not write event log", e)
            }
            recent.addLast(line)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        Log.i(TAG, line)
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun recentLines(): List<String> = recent.toList()

    @Synchronized
    fun readAll(ctx: Context): String {
        setup(ctx)
        val f = file!!
        return if (f.exists()) f.readText() else ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        setup(ctx)
        file!!.writeText("")
        recent.clear()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
