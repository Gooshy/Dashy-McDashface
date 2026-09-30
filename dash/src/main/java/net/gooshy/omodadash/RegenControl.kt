package net.gooshy.omodadash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import net.gooshy.omodaboard.vdbus.VdBus
import net.gooshy.omodaboard.vdbus.VdService

/**
 * The dashboard's one write to the car: the regen level, `NEW_ENERGY` 37.
 *
 * The same command the car's own settings app sends ("Energy recovery level",
 * `OtherSettingFragment` → `sendPHEVEnergyRegenLevel`). Everything else in both
 * apps is read-only; the shared bus client in `:core` has no write path, so
 * this class binds the CarInfo service itself and can send exactly one
 * command, with only the values 1–3.
 *
 * **The car reports and accepts different numbers.** From the vendor code:
 *
 * | Level  | reported | sent |
 * |--------|----------|------|
 * | Low    | 2        | 1    |
 * | Medium | 1        | 2    |
 * | High   | 0        | 3    |
 *
 * Dash 1.1–1.3 sent the reported number, so a tap from Medium (1) sent 2, which
 * is Medium again, and nothing changed. Everything here that says "level" is
 * the reported number.
 *
 * A tap steps one level from what the car last reported, never from a guess.
 * The car then has [CONFIRM_MS] to report the new level back; if it doesn't,
 * the tile shows the failure and nothing is retried.
 */
class RegenControl(private val context: Context) {

    companion object {
        const val CHANNEL = "vdbus.NEW_ENERGY.37"
        private const val MODULE_NEW_ENERGY = 327682
        private const val CMD = 37
        /** Reported levels in tap order: Low → Medium → High. */
        val LEVELS = listOf(2, 1, 0)
        fun name(level: Int?): String? = when (level) { 2 -> "LOW"; 1 -> "MED"; 0 -> "HIGH"; else -> null }
        /** The value to send for a reported level: Low 1, Medium 2, High 3. */
        private fun sendValue(level: Int) = 3 - level
        /** The vendor app waits 2 s (10 ticks of 200 ms) for the car to answer. */
        private const val CONFIRM_MS = 4_000L
        private const val FAILED_SHOW_MS = 3_000L
        private const val TRANSACTION_SET = IBinder.FIRST_CALL_TRANSACTION + 1 // 2
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var pendingLevel: Int? = null
    @Volatile private var pendingSince = 0L
    @Volatile private var failedAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    fun start() {
        if (bound) return
        val intent = Intent(VdService.CAR_INFO.action).setPackage(VdService.CAR_INFO.packageName)
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    fun stop() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false; binder = null; pendingLevel = null
    }

    /** What the tile shows. */
    sealed interface Status {
        data class Level(val level: Int) : Status
        data class Setting(val target: Int) : Status
        data object Failed : Status
        data object Unknown : Status
    }

    fun status(now: Long = SystemClock.elapsedRealtime()): Status {
        val current = Live.value(CHANNEL)?.toInt()?.takeIf { it in LEVELS }
        pendingLevel?.let { target ->
            when {
                current == target -> pendingLevel = null
                now - pendingSince > CONFIRM_MS -> { pendingLevel = null; failedAt = now }
                else -> return Status.Setting(target)
            }
        }
        if (now - failedAt < FAILED_SHOW_MS) return Status.Failed
        return current?.let { Status.Level(it) } ?: Status.Unknown
    }

    /** Steps Low → Medium → High → Low. Does nothing unless the car's current level is known. */
    fun cycle() {
        if (pendingLevel != null) return
        val current = Live.value(CHANNEL)?.toInt()?.takeIf { it in LEVELS } ?: return
        val target = LEVELS[(LEVELS.indexOf(current) + 1) % LEVELS.size]
        val b = binder ?: return
        pendingLevel = target
        pendingSince = SystemClock.elapsedRealtime()
        Thread({ if (!send(b, target)) { pendingLevel = null; failedAt = SystemClock.elapsedRealtime() } }, "dash-regen").start()
    }

    private fun send(target: IBinder, level: Int): Boolean {
        check(level in LEVELS)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(VdBus.DESCRIPTOR)
            data.writeInt(1)
            VdBus.writeEvent(data, MODULE_NEW_ENERGY, Bundle().apply {
                putInt(VdBus.KEY_CMD_ID, CMD)
                putIntArray(VdBus.KEY_VALUE, intArrayOf(sendValue(level)))
            })
            target.transact(TRANSACTION_SET, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle(); data.recycle()
        }
    }
}
