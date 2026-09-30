package net.gooshy.omodaboard.core

/**
 * What the raw channels mean, as far as drives have established it.
 *
 * Each entry turns a raw reading into a named value in real units, recorded
 * alongside the raw one as `known.<name>`. Sources and decodes are from the
 * first drive's analysis (`drive-20260928-210312/claude-report.md`); the
 * confidence travels into summary.json so nothing probable is passed off as
 * confirmed.
 *
 * Multi-byte VDBus values are big-endian. A byte of 255 is "no data yet" (the
 * trip computer reads all-255 until it has something), so decodes skip it.
 */
object KnownChannels {

    enum class Confidence { CONFIRMED, HIGH, PROBABLE }

    class Known(
        val name: String,
        val unit: String,
        val confidence: Confidence,
        val note: String,
        val decode: (DoubleArray) -> Double?,
    )

    /** Raw channel name (as the registry names it) → what it decodes to. */
    val BY_SOURCE: Map<String, List<Known>> = buildMap {
        fun add(source: String, vararg k: Known) = put(source, k.toList())

        // ---- driving dynamics ------------------------------------------------
        add("car.PERF_VEHICLE_SPEED", Known("speed_kmh", "km/h", Confidence.CONFIRMED, "car API, 10 Hz, matches GPS") { it[0] * 3.6 })
        add("vdbus.READONLY_INFO.119", Known("speed_display_kmh", "km/h", Confidence.CONFIRMED, "ID_SPEED_GAUGE_DISPLAY; reads ~4% high") { be16(it, 0) })
        add("vdbus.READONLY_INFO.139", Known("yaw_rate_dps", "°/s", Confidence.CONFIRMED, "unnamed in vendor tables; matches the gyro exactly; right positive, ~25 Hz") { be16(it, 0)?.let { v -> (YAW_CENTRE - v) / 100.0 } })
        add(
            "vdbus.READONLY_INFO.66",
            Known("steering_raw", "counts", Confidence.CONFIRMED, "ID_STEERING_ANGLE; right positive, 0 = straight") { be16(it, 0)?.let { v -> STEER_CENTRE - v } },
            // Drive 2 full-lock test while parked read +491 / −483, which is a
            // typical ~2.7-turn rack if one count is one degree.
            Known("steering_deg", "°", Confidence.PROBABLE, "assumes 1 count = 1°; full lock read ±~487") { be16(it, 0)?.let { v -> STEER_CENTRE - v } },
        )
        add("vdbus.READONLY_INFO.67", Known("steering_rate_raw", "?", Confidence.HIGH, "ID_STEERING_ANGLE_SPEED; scale unknown") { be16(it, 0) })
        add("car.PARKING_BRAKE_ON", Known("brake_pedal", "0/1", Confidence.CONFIRMED, "mislabelled by the VHAL; identical to ID_BRAKE_PEDAL (READONLY_INFO.80)") { it[0] })
        add("car.GEAR_SELECTION", Known("gear", "4=P 8=D 2=R", Confidence.CONFIRMED, "VHAL gear enum") { it[0] })
        add("vdbus.READONLY_INFO.26", Known("gearbox_state", "4=D 2=R 1=P", Confidence.HIGH, "ID_GEARBOX_STATE; matched the car API gear on drive 4") { it[0] })
        add("vdbus.READONLY_INFO.75", Known("handbrake_state", "enum", Confidence.HIGH, "ID_HANDBRAKE_STATE (EPB)") { it[0] })
        add("vdbus.READONLY_INFO.33", Known("indicator_left_lamp", "0/1", Confidence.CONFIRMED, "ID_LH_TURN_LIGHT_STS; flashes with the lamp") { it[0] })
        add("vdbus.READONLY_INFO.32", Known("indicator_right_lamp", "0/1", Confidence.CONFIRMED, "ID_RH_TURN_LIGHT_STS; flashes with the lamp") { it[0] })
        add("vdbus.READONLY_INFO.35", Known("indicator_left", "0/1", Confidence.CONFIRMED, "ID_DIRECTION_IND_LEFT; steady while the stalk is on") { it[0] })
        add("vdbus.READONLY_INFO.34", Known("indicator_right", "0/1", Confidence.CONFIRMED, "ID_DIRECTION_IND_RIGHT; steady while the stalk is on") { it[0] })
        add("vdbus.CAR_SETTING.52", Known("auto_hold_active", "0/1", Confidence.HIGH, "[0] = 1 while auto-hold is holding the car") { it[0] })

        // ---- engine (zero / invalid while running on electric) ---------------
        // Drive 4 (engine on for 175 s): 93–94 idling at a standstill, 180–250 at
        // 110–125 km/h, so raw × 10 rpm. The car only refreshes it every 5 s.
        add(
            "vdbus.READONLY_INFO.62",
            Known("engine_rpm_raw", "?", Confidence.HIGH, "ID_ENGINE_RPM; non-zero only with the engine on; updates every 5 s") { be16(it, 0) },
            Known("engine_rpm", "rpm", Confidence.PROBABLE, "ID_ENGINE_RPM × 10; idle read 930–940; updates every 5 s") { be16(it, 0)?.times(10) },
        )
        add("vdbus.READONLY_INFO.74", Known("engine_state", "enum", Confidence.HIGH, "ID_ENGINE_STATE") { it[0] })
        add("vdbus.READONLY_INFO.106", Known("engine_torque_nm", "Nm", Confidence.HIGH, "ID_MEAN_EFFECTIVE_TORQUE; vendor decode be16 × 0.5 − 1000") { be16(it, 0)?.let { v -> v * 0.5 - 1000 } })
        add("vdbus.READONLY_INFO.107", Known("coupling_torque_nm", "Nm", Confidence.HIGH, "ID_ESTIMATED_COUPLING_TORQUE; vendor decode be16 × 0.25") { be16(it, 0)?.times(0.25) })
        add("vdbus.READONLY_INFO.70", Known("instant_consumption_raw", "?", Confidence.HIGH, "ID_INSTANTANEOUS_CONSUMPTION; 0xFFFF = invalid") { be16(it, 0) })
        add("vdbus.READONLY_INFO.68", Known("fuel_pct", "%", Confidence.PROBABLE, "ID_FUEL_PERCENT; assumes be16 × 0.1") { be16(it, 0)?.div(10.0) })

        // ---- battery and energy ----------------------------------------------
        add("vdbus.NEW_ENERGY.44", Known("soc_pct", "%", Confidence.CONFIRMED, "ID_DISPLAY_SOC; 0.01% steps") { be16(it, 0)?.div(100.0) })
        add("vdbus.NEW_ENERGY.10", Known("soc_remaining_pct", "%", Confidence.HIGH, "ID_BMSH_SOC_REMANING") { it[0].takeIf { v -> v != 255.0 } })
        // Unnamed. Falls with SOC but showed no sag under load on drive 2.
        add("vdbus.READONLY_INFO.109", Known("hv_voltage_v", "V", Confidence.PROBABLE, "unnamed in vendor tables; falls with SOC but does not sag under load") { be16(it, 2)?.div(100.0) })
        add("vdbus.READONLY_INFO.110", Known("aux_12v_raw", "?", Confidence.HIGH, "ID_IBS_VOLTAGE (12 V battery sensor); scale unconfirmed") { be16(it, 0) })
        // 6, 7, 9, 11 and 16 appeared only while the engine ran (drive 4), each
        // matching the rpm channel's start and stop to within a second.
        add(
            "vdbus.NEW_ENERGY.8",
            Known("energy_flow", "enum", Confidence.CONFIRMED, "ID_ENERGY_FLOW; 1 parked, 3 motor drive, 14 regen; engine on: 6 series drive, 7 charging at rest, 9 engine + motor, 11 engine cruise, 16 regen") { it[0] },
            Known("engine_running", "0/1", Confidence.HIGH, "energy flow is one of the engine-on states (6, 7, 9, 11, 16); instant, unlike rpm") { if (it[0] in ENGINE_ON_FLOWS) 1.0 else 0.0 },
        )
        add("vdbus.NEW_ENERGY.4", Known("drive_mode", "enum", Confidence.HIGH, "ID_DRIVE_MODE") { it[0] })
        add("vdbus.NEW_ENERGY.9", Known("power_mode", "enum", Confidence.HIGH, "ID_HCU_POWER_MODE (EV / HEV)") { it[0] })
        // Not "engine running": on drive 4 it read 2 for 3.5 s as the engine started, then 1 while it ran.
        add("vdbus.NEW_ENERGY.119", Known("engine_start_pulse", "0/1", Confidence.PROBABLE, "unnamed; 2 briefly as the engine starts") { if (it[0] == 2.0) 1.0 else 0.0 })
        add("vdbus.NEW_ENERGY.83", Known("vcu_temp_raw", "?", Confidence.HIGH, "ID_VCU_TEMPERATURE_C; offset unconfirmed (raw 53→59 on a 10 °C morning)") { it[0] })
        add("vdbus.NEW_ENERGY.101", Known("driver_fatigue_level", "enum", Confidence.PROBABLE, "ID_DMS_DRVRFATILVL (driver monitoring camera)") { it[0] })

        // ---- trip computer and odometer (vendor decodes from EngFlowPresenter) --
        add("vdbus.READONLY_INFO.71", Known("odometer_km", "km", Confidence.CONFIRMED, "ID_GRAND_TOTAL_KM; be32") { be32(it) })
        add("vdbus.READONLY_INFO.40", Known("trip_since_reset_km", "km", Confidence.HIGH, "ID_GRAND_TOTAL_KM_AFTER_CLEAR; be32 × 0.1") { be32(it)?.div(10.0) })
        add("vdbus.READONLY_INFO.28", Known("trip_km", "km", Confidence.CONFIRMED, "ID_TRIP; 0.1 km steps") { be32(it)?.div(10.0) })
        add("vdbus.READONLY_INFO.29", Known("trip_time_s", "s", Confidence.CONFIRMED, "ID_DRIVE_TIME; [2] minutes, [3] seconds") {
            if (it.size < 4 || it[2] == 255.0 || it[3] == 255.0) null else it[2] * 60 + it[3]
        })
        add("vdbus.READONLY_INFO.31", Known("trip_avg_speed_kmh", "km/h", Confidence.CONFIRMED, "ID_AVG_SPEED; be16 × 0.1") { be16(it, 0)?.div(10.0) })
        add("vdbus.READONLY_INFO.52", Known("trip_avg_power_kwh_100km", "kWh/100 km", Confidence.HIGH, "ID_AVERAGE_POWER_CONSUMPTION_AFTER_RUNNING_8155; vendor decode") { signedTenth(it) })
        add("vdbus.READONLY_INFO.82", Known("recent_avg_power_kwh_100km", "kWh/100 km", Confidence.HIGH, "ID_AVERAGE_POWER_CONSUMPTION_AFTER_RUNNING (recent); vendor decode") { signedTenth(it) })
        add("vdbus.READONLY_INFO.53", Known("trip_avg_fuel_l_100km", "L/100 km", Confidence.HIGH, "ID_AVERAGE_FUEL_CONS_AFTER_RUNNING; be16 × 0.1") { be16(it, 0)?.takeIf { v -> v != 511.0 }?.div(10.0) })
        add("vdbus.READONLY_INFO.72", Known("fuel_range_km", "km", Confidence.HIGH, "ID_ENDURANCE_KM; be16") { be16(it, 0)?.takeIf { v -> v < 2000 } })
        add("vdbus.NEW_ENERGY.42", Known("ev_range_km", "km", Confidence.HIGH, "ID_DISPLAY_MILEAGE; be16") { be16(it, 0) })
        add("vdbus.NEW_ENERGY.54", Known("ev_lifetime_km", "km", Confidence.HIGH, "ID_EV_MILEAGE; be32") { be32(it) })
        add("vdbus.NEW_ENERGY.50", Known("hev_lifetime_km", "km", Confidence.HIGH, "ID_HEV_MILEAGE; be32") { be32(it) })
        add("vdbus.READONLY_INFO.25", Known("lifetime_charged_kwh", "kWh", Confidence.HIGH, "ID_SUM_PLG_CHRG_EGY; be32 × 0.1") { be32(it)?.div(10.0) })
        add("vdbus.READONLY_INFO.24", Known("lifetime_fuel_l", "L", Confidence.HIGH, "ID_SUM_FUEL; be32 × 0.1") { be32(it)?.div(10.0) })
        add("vdbus.READONLY_INFO.93", Known("service_due_km", "km", Confidence.HIGH, "ID_ICMT_DISTANCE; counts down") { be16(it, 0) })
        add("vdbus.READONLY_INFO.94", Known("service_due_days", "days", Confidence.HIGH, "ID_ICMT_DAY") { be16(it, 0) })

        // ---- tyres and environment -------------------------------------------
        // Checked against the car's own tyre screen before drive 2: raw 162/158/158/160
        // read 32/31/31/32 psi front-left/front-right/rear-left/rear-right.
        add(
            "vdbus.READONLY_INFO.140",
            *listOf("fl", "fr", "rl", "rr").mapIndexed { i, wheel ->
                Known("tyre_psi_$wheel", "psi", Confidence.CONFIRMED, "ID_TIRE_PRESSURE; raw × 0.2; matched the car's tyre screen") { v ->
                    v.getOrNull(i)?.takeIf { it != 255.0 }?.times(0.2)
                }
            }.toTypedArray(),
        )
        // Decode from the telemetry SDK's AirConditionerManager.getOutsideTemp().
        // Raw 132 on the 2026-09-29 morning = 11 °C, matching the tyre temps read off the car.
        add("vdbus.HVAC.19", Known("outside_temp_c", "°C", Confidence.CONFIRMED, "ID_AC_TEMPERATURE_CELSIUS_OUTSIDE; vendor decode raw × 0.5 − 55") { it[0].takeIf { v -> v != 255.0 }?.let { v -> v * 0.5 - 55 } })
    }

    /** Every known name with its metadata, for summary.json. */
    val ALL: List<Pair<String, Known>> = BY_SOURCE.flatMap { (src, ks) -> ks.map { src to it } }

    /** Energy-flow states seen only with the engine running. */
    val ENGINE_ON_FLOWS = setOf(6.0, 7.0, 9.0, 11.0, 16.0)

    private const val YAW_CENTRE = 18000.0
    private const val STEER_CENTRE = 2048.0

    private fun be16(v: DoubleArray, at: Int): Double? {
        if (v.size < at + 2) return null
        val hi = v[at]; val lo = v[at + 1]
        if (hi == 255.0 && lo == 255.0) return null
        return hi * 256 + lo
    }

    /** Vendor decode for the average-consumption words: be16 × 0.1, negative when byte 3 is 1. */
    private fun signedTenth(v: DoubleArray): Double? {
        if (v.size < 4) return null
        val magnitude = be16(v, 0) ?: return null
        return (if (v[3] == 1.0) -magnitude else magnitude) / 10.0
    }

    private fun be32(v: DoubleArray): Double? {
        if (v.size != 4 || v.all { it == 255.0 }) return null
        return ((v[0] * 256 + v[1]) * 256 + v[2]) * 256 + v[3]
    }
}
