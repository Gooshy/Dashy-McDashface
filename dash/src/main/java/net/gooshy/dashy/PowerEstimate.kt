package net.gooshy.dashy

/**
 * Battery power, estimated from how the car is moving: the bus carries no
 * motor power or accelerator position (drive 4 swept every command to 1000),
 * and the cluster's own kW figure lives on the QNX side.
 *
 * Wheel power is the force to accelerate the car (the head unit's forward
 * accelerometer, which includes the pull of any slope) plus drag and rolling
 * resistance, times speed. Two factors fitted on drive 4 against the fall and
 * rise of the fine battery % turn that into battery power: it matched over
 * 5 s windows at r = 0.993 in electric driving. With the engine on, the figure
 * is the total the car is putting down, not only the battery's share.
 */
object PowerEstimate {

    private const val MASS_KG = 2300.0
    private const val CDA_M2 = 0.8
    private const val AIR_KG_M3 = 1.2
    private const val ROLLING = 0.012
    private const val G = 9.81

    /** Battery kW per wheel kW while driving (≈ 70% drivetrain efficiency). */
    private const val DRIVE_FACTOR = 1.43
    /** Battery kW recovered per wheel kW while slowing; the rest goes to the friction brakes. */
    private const val REGEN_FACTOR = 0.77
    /** The most the battery was seen taking back; friction brakes do the rest. */
    private const val REGEN_MAX_KW = 60.0

    /** Head unit mounting: forward accelerometer (axis 2) = 1.0596 × true + −1.301 m/s² on this car. */
    private const val ACCEL_SCALE = 1.0596
    private const val ACCEL_OFFSET = -1.301

    /** Forward acceleration in m/s² (slope included) from the raw accelerometer axis 2. */
    fun forwardAccel(rawAxis2: Double): Double = (rawAxis2 - ACCEL_OFFSET) / ACCEL_SCALE

    /** Battery kW: positive driving, negative regenerating. */
    fun batteryKw(speedKmh: Double, forwardMs2: Double): Double {
        val v = speedKmh / 3.6
        if (v < 0.3) return 0.0
        val force = MASS_KG * forwardMs2 + 0.5 * AIR_KG_M3 * CDA_M2 * v * v + ROLLING * MASS_KG * G
        val wheelKw = force * v / 1000
        return if (wheelKw >= 0) wheelKw * DRIVE_FACTOR else maxOf(-REGEN_MAX_KW, wheelKw * REGEN_FACTOR)
    }
}
