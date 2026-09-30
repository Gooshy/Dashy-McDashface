package net.gooshy.omodadash

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.gooshy.omodaboard.core.KnownChannels
import net.gooshy.omodaboard.vdbus.VdBusReader
import net.gooshy.omodaboard.vdbus.VdModules
import net.gooshy.omodaboard.vdbus.VdService

/**
 * Reads the car for the dashboard: only the channels in [KnownChannels]
 * (plus the regen level). Runs while the dashboard is on screen. All reads;
 * there is no write path in the bus client. The one write, the regen level,
 * is [regen], started and stopped with the feed.
 *
 *  - VDBus: every module that holds a known channel is subscribed, so pushes
 *    arrive at once; the fast-moving channels are also polled at 10 Hz and
 *    the rest at 1 Hz, since the bus doesn't push everything.
 *  - Car API: speed at 10 Hz, gear and the brake pedal on change.
 *  - The head unit's accelerometer, forward axis, for the power estimate.
 */
class LiveFeed(private val context: Context) {

    enum class State { CONNECTING, CONNECTED, UNAVAILABLE }

    /** Snapshot state, so the screen follows it; written from the feed's threads. */
    var state by mutableStateOf(State.CONNECTING); private set

    // A fresh reader per start: a reader's bind is single-use.
    private var bus = VdBusReader(context, VdService.CAR_INFO)
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    @Volatile private var running = false
    /** Bumped on every start, so a previous start's poll loop knows to quit. */
    @Volatile private var gen = 0
    private var threads = listOf<Thread>()

    val regen = RegenControl(context)

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size > 2) Live.onRecord(Live.ACCEL_FWD, doubleArrayOf(event.values[2].toDouble()))
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val moduleIds = VdModules.SWEEP_ORDER.associate { (name, id) -> name.removePrefix("MODULE_") to id }

    /** (module id, cmd) for every identified VDBus channel. */
    private val commands: List<Pair<Int, Int>> = (KnownChannels.BY_SOURCE.keys + Live.RAW)
        .filter { it.startsWith("vdbus.") }
        .mapNotNull { ch ->
            val (_, module, cmd) = ch.split('.')
            moduleIds[module]?.let { it to cmd.toInt() }
        }
        .distinct()

    private val fast = commands.filter { "vdbus.${VdModules.shortName(it.first)}.${it.second}" in FAST }

    fun start() {
        if (running) return
        running = true
        state = State.CONNECTING
        bus = VdBusReader(context, VdService.CAR_INFO)
        regen.start()
        sensors?.let { sm -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME) } }
        val g = ++gen
        threads = listOf(
            Thread({ runBus(g) }, "dash-bus"),
            Thread({ runCarApi() }, "dash-car"),
        ).onEach { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        regen.stop()
        sensors?.unregisterListener(accelListener)
        threads.forEach { it.interrupt() }
        runCatching { props?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        car = null; props = null
        val old = bus
        Thread { old.disconnect() }.start()
    }

    private fun runBus(g: Int) {
        val bus = bus
        fun alive() = running && gen == g
        if (!bus.connect()) { if (alive()) state = State.UNAVAILABLE; return }
        if (!alive()) return
        state = State.CONNECTED
        commands.map { it.first }.distinct().forEach { module ->
            bus.subscribe(module) { ev ->
                val v = ev.values ?: return@subscribe
                val cmd = ev.cmd ?: return@subscribe
                if (v.isNotEmpty()) publish(module, cmd, v)
            }
        }
        var tick = 0
        while (alive()) {
            val batch = if (tick % 10 == 0) commands else fast
            for ((module, cmd) in batch) {
                if (!alive()) return
                bus.get(module, cmd)?.values?.takeIf { it.isNotEmpty() }?.let { publish(module, cmd, it) }
            }
            tick++
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    private fun publish(module: Int, cmd: Int, v: IntArray) =
        Live.onRecord("vdbus.${VdModules.shortName(module)}.$cmd", DoubleArray(v.size) { v[it].toDouble() })

    private fun runCarApi() {
        if (runCatching { Class.forName("android.car.Car") }.isFailure) return
        runCatching {
            val c = Car.createCar(context) ?: return
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = m
            m.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
            m.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            m.registerCallback(callback, VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }
    }

    // Lazy: it implements an android.car interface, absent on non-car devices.
    private val callback by lazy {
        object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val v = when (val x = value.value) {
                    is Float -> x.toDouble()
                    is Int -> x.toDouble()
                    is Boolean -> if (x) 1.0 else 0.0
                    else -> return
                }
                Live.onRecord("car.${VehiclePropertyIds.toString(value.propertyId)}", doubleArrayOf(v))
            }

            override fun onErrorEvent(propId: Int, zone: Int) = Unit
        }
    }

    private companion object {
        /** Channels a driver sees move: polled at 10 Hz. */
        val FAST = setOf(
            "vdbus.READONLY_INFO.119", // dashboard speed
            "vdbus.READONLY_INFO.139", // yaw rate
            "vdbus.READONLY_INFO.66",  // steering
            "vdbus.READONLY_INFO.80",  // brake pedal
            "vdbus.READONLY_INFO.34", "vdbus.READONLY_INFO.35", // indicators
            "vdbus.CAR_SETTING.52",    // auto-hold
            "vdbus.NEW_ENERGY.8",      // energy flow
        )
    }
}
