package dev.handoff.hostspike

import android.net.TrafficStats
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures the app's own footprint for the battery-impact estimate (PRD F16).
 *
 * It costs almost nothing: counters are in-memory increments, and the CPU and data totals are read
 * from the kernel only when a heartbeat is logged. Android does not let ordinary apps read the
 * per-app battery figure Settings shows, so these inputs feed an estimate instead.
 */
object SelfStats {
    private val wakeups = ConcurrentHashMap<String, AtomicLong>()
    private var lastCpuMs = -1L
    private var lastNetBytes = -1L
    private var lastAt = 0L

    /** Count one event that woke or used the app, by cause. */
    fun count(cause: String) {
        wakeups.getOrPut(cause) { AtomicLong() }.incrementAndGet()
    }

    /** Totals since this process started, plus the change since the previous snapshot. */
    @Synchronized
    fun snapshot(): Array<Pair<String, Any?>> {
        val now = System.currentTimeMillis()
        val cpuMs = Process.getElapsedCpuTime()
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        val net = if (rx < 0 || tx < 0) -1L else rx + tx
        val counts = JSONObject()
        for ((k, v) in wakeups.toSortedMap()) counts.put(k, v.get())
        val out = arrayOf<Pair<String, Any?>>(
            "self_cpu_ms_total" to cpuMs,
            "self_cpu_ms_delta" to if (lastCpuMs >= 0) cpuMs - lastCpuMs else null,
            "self_net_bytes_total" to net,
            "self_net_bytes_delta" to if (lastNetBytes >= 0 && net >= 0) net - lastNetBytes else null,
            "self_wakeups_total" to counts,
            "self_interval_min" to if (lastAt > 0) Math.round((now - lastAt) / 6000.0) / 10.0 else null
        )
        lastCpuMs = cpuMs
        lastNetBytes = net
        lastAt = now
        return out
    }
}
