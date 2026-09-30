package net.gooshy.omodadash

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

enum class Flow { PARKED, DRIVE, REGEN }

/**
 * One moment of car state, from the car ([LiveInput]) or the scripted demo
 * ([DemoInput]). Null means "the car hasn't said".
 */
data class Reading(
    val speedKmh: Double? = null,
    val longG: Double? = null,
    val latG: Double? = null,
    val yawDps: Double? = null,
    val steerDeg: Double? = null,
    /** Battery power, kW, negative when regenerating. Estimated on the car ([PowerEstimate]). */
    val powerKw: Double? = null,
    val gear: String = "—",
    val brake: Boolean = false,
    val indL: Boolean = false,
    val indR: Boolean = false,
    val autoHold: Boolean = false,
    val flow: Flow = Flow.PARKED,
    val mode: String = "—",
    val ev: String = "—",
    val regenLevel: String? = null,
    val tempC: Double? = null,
    val soc: Double? = null,
    val rangeKm: Double? = null,
    val tripKm: Double? = null,
    val tripS: Double? = null,
    val tripAvgKmh: Double? = null,
    val tripAvgKwh: Double? = null,
    val recentKwh: Double? = null,
    val odoKm: Double? = null,
    val tyres: List<Double?> = listOf(null, null, null, null),
    val engineOn: Boolean = false,
    val engineTorqueNm: Double? = null,
    /** Engine rpm; the car refreshes it only every 5 s. */
    val engineRpm: Double? = null,
    /** True when the data link has gone quiet for over 2 s. */
    val stale: Boolean = false,
)

interface DashInput {
    /** [nowMs] is elapsedRealtime; [dt] seconds since the last frame. */
    fun read(nowMs: Long, dt: Double): Reading
}

/** Personal bests, so the timing table has something to be purple against. */
data class Bests(
    val t060: Double? = null,
    val acc: Double? = null,
    val brk: Double? = null,
    val lat: Double? = null,
    val topMph: Double? = null,
)

/**
 * Everything the dashboard draws, eased for display. Ported from the design's
 * `tick()`: values glide toward each new reading with a per-channel time
 * constant, so a 2 Hz channel moves smoothly rather than jumping.
 */
class DashEngine {

    var r = Reading(stale = true); private set

    // Eased display values.
    var speed = 0.0; private set
    var longG = 0.0; private set
    var latG = 0.0; private set
    var yaw = 0.0; private set
    var steer = 0.0; private set
    var power = 0.0; private set
    var soc = 0.0; private set
    var shift = 0.0; private set

    // This drive.
    var regenS = 0.0; private set
    var driveS = 0.0; private set
    var sAcc = 0.0; private set
    var sBrk = 0.0; private set
    var sLatL = 0.0; private set
    var sLatR = 0.0; private set
    var sTopMph = 0.0; private set
    var sT060: Double? = null; private set

    /** Launch timer: running elapsed or the final 0–60 time. */
    var launchEl: Double? = null; private set
    var launchDone: Double? = null; private set
    private var armed = false
    private var launchT0 = 0.0
    private var clockS = 0.0

    /** Friction-circle trail: (lat, long, age anchor ms). */
    val trail = ArrayDeque<Triple<Double, Double, Long>>()

    /** Set when a hard brake (> 0.5 g) starts; the screen flashes and clears it. */
    var hardBrakeAt = 0L; private set
    private var hb = false

    private var seeded = false

    /** True once any reading has arrived, so "stale" means lost rather than not yet started. */
    val demoStarted: Boolean get() = seeded

    fun resetSession() {
        regenS = 0.0; driveS = 0.0
        sAcc = 0.0; sBrk = 0.0; sLatL = 0.0; sLatR = 0.0; sTopMph = 0.0; sT060 = null
        launchEl = null; launchDone = null; armed = false
        trail.clear()
    }

    fun tick(input: DashInput, nowMs: Long, dt: Double) {
        clockS += dt
        val rd = input.read(nowMs, dt)
        r = rd
        if (!seeded && !rd.stale) {
            speed = rd.speedKmh ?: 0.0; soc = rd.soc ?: 0.0; steer = rd.steerDeg ?: 0.0
            seeded = true
        }
        if (rd.stale) return

        fun ease(cur: Double, target: Double?, tau: Double) =
            if (target == null) cur else cur + (target - cur) * (1 - exp(-dt / tau))
        speed = ease(speed, rd.speedKmh, .12)
        longG = ease(longG, rd.longG, .07)
        latG = ease(latG, rd.latG, .07)
        yaw = ease(yaw, rd.yawDps, .15)
        steer = ease(steer, rd.steerDeg, .12)
        power = ease(power, rd.powerKw, .15)
        soc = ease(soc, rd.soc, .35)
        // Shift lights: motor power share once power is known; until then the
        // forward g-force a launch produces (0.5 g = full bar).
        val shiftTarget = rd.powerKw?.let { max(0.0, it) / DRIVE_MAX_KW }
            ?: max(0.0, (rd.longG ?: 0.0)) / 0.5
        shift = ease(shift, min(1.0, shiftTarget), .08)

        when (rd.flow) {
            Flow.REGEN -> regenS += dt
            Flow.DRIVE -> driveS += dt
            Flow.PARKED -> Unit
        }

        trail.addLast(Triple(latG, longG, nowMs))
        while (trail.isNotEmpty() && nowMs - trail.first().third > 2000) trail.removeFirst()

        if (rd.gear != "R") {
            sAcc = max(sAcc, rd.longG ?: 0.0)
            sBrk = max(sBrk, -(rd.longG ?: 0.0))
        }
        sLatL = max(sLatL, -(rd.latG ?: 0.0))
        sLatR = max(sLatR, rd.latG ?: 0.0)
        sTopMph = max(sTopMph, (rd.speedKmh ?: 0.0) * KMH_TO_MPH)

        if (longG < -0.5 && !hb) { hb = true; hardBrakeAt = nowMs } else if (longG > -0.4) hb = false

        launch(rd)
    }

    /** 0–60 mph timer: arms when stopped in D, runs from the first pull. */
    private fun launch(rd: Reading) {
        val v = rd.speedKmh ?: return
        val a = rd.longG ?: 0.0
        val running = launchEl != null && launchDone == null
        if (rd.gear == "D" && v < 0.2 && !running) armed = true
        if (armed && !running && v >= 0.2 && a > 0.1) {
            armed = false; launchT0 = clockS; launchEl = 0.0; launchDone = null
            return
        }
        if (running) {
            val el = clockS - launchT0
            launchEl = el
            if (v >= 96.56) {
                launchDone = el
                sT060 = min(sT060 ?: 99.0, el)
            } else if (el > 20 || a < -0.05) {
                launchEl = null
            }
        }
    }

    fun regenPct(): Double = if (regenS + driveS <= 0) 0.0 else regenS / (regenS + driveS) * 100

    companion object {
        const val KMH_TO_MPH = 0.621371
        /** Full scale of the power bar and shift lights. Drive 4 put down about 250 kW. */
        const val DRIVE_MAX_KW = 250.0
        const val REGEN_MAX_KW = 60.0
    }
}

/** The car, via [Live]. */
class LiveInput : DashInput {

    private var accel = 0.0
    private var lastSpeedAt = 0L
    /** Forward acceleration from the accelerometer, m/s², smoothed over ~0.5 s. */
    private var fwd: Double? = null
    /** When the engine-on flow state began; drive 5 had 0.1 s blips of state 6 with the engine off. */
    private var engineSince: Long? = null

    /** On once the flow state has said so for a second, or the rpm is non-zero. */
    private fun engineOn(nowMs: Long): Boolean {
        if ((Live.value("engine_rpm") ?: 0.0) > 0) return true
        val flowOn = (Live.value("engine_running") ?: 0.0) > 0.5
        if (!flowOn) { engineSince = null; return false }
        val since = engineSince ?: nowMs.also { engineSince = it }
        return nowMs - since >= 1000
    }

    override fun read(nowMs: Long, dt: Double): Reading {
        val speedS = Live["speed_kmh"] ?: Live["speed_display_kmh"]
        val stale = speedS == null || nowMs - speedS.atMs > 2000
        val v = speedS?.value

        // Longitudinal g from successive speed samples, smoothed (the analysis
        // showed this is steadier than the head unit's own accelerometer).
        Live.speedsSince(lastSpeedAt).let { samples ->
            var prev: Pair<Long, Double>? = null
            for (s in samples) {
                val p = prev
                if (p != null && s.first > p.first) {
                    val a = (s.second - p.second) / 3.6 / ((s.first - p.first) / 1000.0) / 9.81
                    accel += (a.coerceIn(-1.5, 1.5) - accel) * 0.35
                }
                prev = s
                lastSpeedAt = s.first
            }
        }
        // Power: the accelerometer includes slopes, so it's preferred; speed-derived
        // acceleration is the fallback when the sensor has gone quiet.
        val acc = Live[Live.ACCEL_FWD]?.takeIf { nowMs - it.atMs < 1000 }
        fwd = acc?.let { a ->
            val f = PowerEstimate.forwardAccel(a.value)
            fwd?.let { it + (f - it) * (1 - exp(-dt / 0.5)) } ?: f
        }
        val gearRaw = Live.value("gear")?.toInt()
        val power = v?.let { speed ->
            when (gearRaw) {
                8 -> PowerEstimate.batteryKw(speed, fwd ?: accel * 9.81)
                2 -> PowerEstimate.batteryKw(speed, 0.0)
                else -> 0.0
            }
        }

        val yaw = Live.value("yaw_rate_dps")
        val lat = if (yaw != null && v != null) yaw / 57.2958 * (v / 3.6) / 9.81 else null

        val flow = when (Live.value("energy_flow")?.toInt()) {
            3, 6, 9, 11 -> Flow.DRIVE
            14, 16 -> Flow.REGEN
            1, 7 -> Flow.PARKED
            else -> if ((v ?: 0.0) > 0.5) Flow.DRIVE else Flow.PARKED
        }
        val gear = when (gearRaw) {
            4 -> "P"; 8 -> "D"; 2 -> "R"; 1 -> "N"; else -> "—"
        }
        val trip = Live.value("trip_km")
        return Reading(
            speedKmh = v,
            longG = accel,
            latG = lat,
            yawDps = yaw,
            steerDeg = Live.value("steering_deg"),
            powerKw = power,
            gear = gear,
            brake = (Live.value("brake_pedal") ?: 0.0) > 0.5,
            indL = (Live.value("indicator_left") ?: 0.0) > 0.5,
            indR = (Live.value("indicator_right") ?: 0.0) > 0.5,
            autoHold = (Live.value("auto_hold_active") ?: 0.0) > 0.5,
            flow = flow,
            // The mode numbers are known (0, 2, 3, 5, 6) but not their names yet.
            mode = Live.value("drive_mode")?.let { "MODE ${it.toInt()}" } ?: "—",
            ev = when (Live.value("power_mode")?.toInt()) { 1 -> "EV"; 2 -> "HEV"; else -> "—" },
            regenLevel = RegenControl.name(Live.value(RegenControl.CHANNEL)?.toInt())?.let { "REGEN $it" },
            tempC = Live.value("outside_temp_c"),
            soc = Live.value("soc_pct"),
            rangeKm = Live.value("ev_range_km"),
            tripKm = trip,
            tripS = Live.value("trip_time_s"),
            tripAvgKmh = Live.value("trip_avg_speed_kmh"),
            tripAvgKwh = Live.value("trip_avg_power_kwh_100km"),
            recentKwh = Live.value("recent_avg_power_kwh_100km"),
            odoKm = Live.value("odometer_km"),
            tyres = listOf("fl", "fr", "rl", "rr").map { Live.value("tyre_psi_$it") },
            engineOn = engineOn(nowMs),
            engineTorqueNm = Live.value("engine_torque_nm")?.takeIf { it > -999 },
            engineRpm = Live.value("engine_rpm"),
            stale = stale,
        )
    }
}

/**
 * The design's 60-second scripted loop: parked on auto-hold, a 0.45 g launch,
 * a 0.4 g left bend with the indicator, a data dropout, 0.3 g regen braking,
 * a short reverse. Shown with DEMO, or when there's no car bus at all, so
 * the screen can be checked parked or off the car.
 */
class DemoInput : DashInput {

    private var t = 0.0
    private val tab = FloatArray(6002 * 2)
    private var soc = 72.41
    private var tripKm = 14.2
    private var tripS = 1104.0
    private var tripKwh = 2.41

    init {
        var v = 0.0
        for (i in 0 until 6002) {
            val tt = i * 0.01
            var a = aProf(tt)
            if (tt < 49) {
                v += a * 9.81 * 0.01
                if (v <= 0) { v = 0.0; if (a < 0) a = 0.0 }
            } else if (tt < 55) {
                val u = PI * (tt - 49) / 6
                v = 8 / 3.6 * sin(u)
                a = -(8 / 3.6 * PI / 6 * cos(u)) / 9.81
            } else { v = 0.0; a = 0.0 }
            tab[i * 2] = (v * 3.6).toFloat()
            tab[i * 2 + 1] = a.toFloat()
        }
    }

    private fun sm(a: Double, b: Double, x: Double): Double {
        val u = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return u * u * (3 - 2 * u)
    }

    private fun aProf(t: Double): Double = when {
        t >= 8 && t < 15 -> 0.45 * sm(8.0, 8.35, t) * (1 - sm(14.3, 14.7, t))
        t >= 36 && t < 46.5 -> -0.3 * sm(36.0, 36.5, t) * (1 - sm(45.9, 46.5, t))
        t >= 15 && t < 36 -> 0.012 * sin(t * 1.7) + 0.008 * sin(t * 4.3)
        else -> 0.0
    }

    override fun read(nowMs: Long, dt: Double): Reading {
        t = (t + dt) % 60.0
        val i = min(5999, (t * 100).toInt())
        val f = t * 100 - i
        val v = tab[i * 2] + (tab[i * 2 + 2] - tab[i * 2]) * f
        val a = tab[i * 2 + 1] + (tab[i * 2 + 3] - tab[i * 2 + 1]) * f
        val vm = v / 3.6
        val gear = if (t < 49) "D" else if (t < 55.5) "R" else "P"
        val bend = sm(22.0, 23.5, t) * (1 - sm(28.5, 30.0, t))
        val mov = if (v > 5) 1.0 else 0.0
        var lat = -0.4 * bend + 0.012 * sin(t * 2.3) * mov
        var steer = -38 * bend + 1.5 * sin(t * 0.9) * mov
        var yaw = if (vm > 1) lat * 9.81 / vm * 57.3 else 0.0
        if (gear == "R") {
            steer = 240 * sm(49.3, 50.8, t) * (1 - sm(53.5, 55.0, t))
            yaw = -vm * tan(steer / 15 / 57.3) / 2.8 * 57.3
            lat = -vm * (yaw / 57.3) / 9.81
        }
        val power = if (gear == "R") {
            if (v > 0.3) 4 + vm * 3 else 0.0
        } else {
            val w = 1150 * a * 9.81 * vm + 0.47 * vm * vm * vm + 216 * vm
            if (w >= 0) min(150.0, w / 1000) else max(-60.0, w / 1000 * 0.85)
        }
        val flow = if (v < 0.3 || gear == "P") Flow.PARKED else if (a < -0.04 && gear == "D") Flow.REGEN else Flow.DRIVE

        tripKm += vm * dt / 1000
        tripS += dt
        val kwh = power * dt / 3600
        tripKwh += kwh
        soc = max(0.0, soc - kwh / 34.5 * 100)

        return Reading(
            speedKmh = v, longG = a, latG = lat, yawDps = yaw, steerDeg = steer, powerKw = null,
            gear = gear,
            brake = (t >= 38 && t < 46.3) || (t >= 54.6 && t < 56),
            indL = t >= 19.5 && t < 23.2,
            autoHold = t < 8 || (t >= 46.3 && t < 49),
            flow = flow, mode = "MODE 6", ev = "EV", regenLevel = "REGEN HIGH", tempC = 17.0,
            soc = soc, rangeKm = (soc * 1.31).let { kotlin.math.round(it) },
            tripKm = tripKm, tripS = tripS, tripAvgKmh = tripKm / (tripS / 3600),
            tripAvgKwh = tripKwh / tripKm * 100, recentKwh = 16.2, odoKm = 1004.0,
            tyres = listOf(32.4, 32.4, 32.0, 32.6),
            stale = t >= 31 && t < 35.5,
        )
    }

    companion object {
        val BESTS = Bests(t060 = 5.90, acc = 0.52, brk = 0.61, lat = 0.48, topMph = 88.0)
    }
}

internal fun signed(v: Double, n: Int): String = (if (v < 0) "−" else "+") + "%.${n}f".format(abs(v))
