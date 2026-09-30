package net.gooshy.omodadash

import android.os.SystemClock
import net.gooshy.omodaboard.core.KnownChannels
import java.util.concurrent.ConcurrentHashMap

/**
 * The latest decoded value of every identified channel, for the dashboard.
 *
 * Fed by [LiveFeed] on its own threads as readings arrive. Values are
 * keyed by their [KnownChannels] name (`speed_kmh`, `soc_pct`, …); a few raw
 * channels with no decode yet are kept under their raw name.
 */
object Live {

    class Sample(val value: Double, val atMs: Long)

    private val values = ConcurrentHashMap<String, Sample>()

    /** Raw channels the dashboard reads directly. */
    val RAW = setOf(RegenControl.CHANNEL, ACCEL_FWD)

    /** The head unit accelerometer's forward axis (raw axis 2, m/s²), for [PowerEstimate]. */
    const val ACCEL_FWD = "sensor.accelerometer[2]"

    /** Every speed sample in arrival order, for acceleration. Bounded ring. */
    private val speedLock = Any()
    private val speedTimes = LongArray(64)
    private val speedValues = DoubleArray(64)
    private var speedHead = 0
    private var speedCount = 0

    fun onRecord(channel: String, values: DoubleArray) {
        val now = SystemClock.elapsedRealtime()
        KnownChannels.BY_SOURCE[channel]?.forEach { k ->
            val v = runCatching { k.decode(values) }.getOrNull() ?: return@forEach
            if (!v.isFinite()) return@forEach
            this.values[k.name] = Sample(v, now)
            if (k.name == "speed_kmh") synchronized(speedLock) {
                speedTimes[speedHead] = now
                speedValues[speedHead] = v
                speedHead = (speedHead + 1) % speedTimes.size
                if (speedCount < speedTimes.size) speedCount++
            }
        }
        if (channel in RAW && values.isNotEmpty()) this.values[channel] = Sample(values[0], now)
    }

    operator fun get(name: String): Sample? = values[name]

    fun value(name: String): Double? = values[name]?.value

    /** Speed samples newer than [sinceMs], oldest first, as (time ms, km/h). */
    fun speedsSince(sinceMs: Long): List<Pair<Long, Double>> = synchronized(speedLock) {
        (0 until speedCount).map { i ->
            val idx = (speedHead - speedCount + i + speedTimes.size) % speedTimes.size
            speedTimes[idx] to speedValues[idx]
        }.filter { it.first > sinceMs }
    }

}
