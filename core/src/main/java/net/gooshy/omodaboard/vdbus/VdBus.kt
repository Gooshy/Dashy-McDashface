package net.gooshy.omodaboard.vdbus

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock

/**
 * The Desay vehicle bus wire protocol, read side only.
 *
 * Lifted from CheryPicker's `vdbus/VdBus.kt`, which was written from the
 * decompiled `com/desaysv/ivi/vdb/` client SDK. The SET transaction is
 * deliberately absent: this app records the car, it never changes it, and the
 * surest way to keep that true is to not have the code.
 */
object VdBus {

    const val DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBus"
    const val NOTIFY_DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBusNotify"
    const val CALLBACK_DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBusCallback"

    const val TRANSACTION_GET = IBinder.FIRST_CALL_TRANSACTION                    // 1
    /** subscribe(int[] eventIds, int pid, String pkg, IVDBusCallback) — how the vendor client subscribes to CarLAN. */
    const val TRANSACTION_SUBSCRIBE_LIST = IBinder.FIRST_CALL_TRANSACTION + 2     // 3
    const val TRANSACTION_SUBSCRIBE_EVENT = IBinder.FIRST_CALL_TRANSACTION + 3    // 4
    const val TRANSACTION_UNSUBSCRIBE_EVENT = IBinder.FIRST_CALL_TRANSACTION + 4  // 5

    /** The only transaction on IVDBusNotify, the callback interface we implement. */
    const val TRANSACTION_ON_NOTIFY = IBinder.FIRST_CALL_TRANSACTION

    /** IVDBusCallback: 1 = onVDBusCallback (get replies), 2 = onVDBusNotify (subscribed events). */
    const val TRANSACTION_ON_CALLBACK = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_ON_BUS_NOTIFY = IBinder.FIRST_CALL_TRANSACTION + 1

    /** VDThreadType.MAIN_THREAD. */
    const val THREAD_MAIN = 0

    const val KEY_CMD_ID = "CMD_ID"
    const val KEY_CMD_ID_ARRAY = "CMD_ID_ARRAY"
    const val KEY_VALUE = "VALUE"

    /** `VDEvent` on the wire: int id, Bundle payload, int threadType, long time. */
    fun writeEvent(parcel: Parcel, id: Int, payload: Bundle?) {
        parcel.writeInt(id)
        parcel.writeBundle(payload)
        parcel.writeInt(THREAD_MAIN)
        parcel.writeLong(SystemClock.elapsedRealtime())
    }

    /** Reads a `VDEvent`. Returns null for a null marker. */
    fun readEvent(parcel: Parcel): VdEvent? {
        if (parcel.readInt() == 0) return null
        val id = parcel.readInt()
        val payload = parcel.readBundle(VdBus::class.java.classLoader)
        parcel.readInt() // threadType
        parcel.readLong() // time
        return VdEvent(id, payload)
    }
}

data class VdEvent(val id: Int, val payload: Bundle?) {
    val cmd: Int? get() = payload?.takeIf { it.containsKey(VdBus.KEY_CMD_ID) }?.getInt(VdBus.KEY_CMD_ID)
    val values: IntArray? get() = payload?.getIntArray(VdBus.KEY_VALUE)

    /** Any payload keys beyond cmd/value, stringified. Unknown shapes are findings too. */
    fun extras(): Map<String, String> {
        val p = payload ?: return emptyMap()
        return (p.keySet() - setOf(VdBus.KEY_CMD_ID, VdBus.KEY_VALUE)).associateWith { key ->
            @Suppress("DEPRECATION")
            when (val v = p.get(key)) {
                is IntArray -> v.joinToString(",")
                is LongArray -> v.joinToString(",")
                is FloatArray -> v.joinToString(",")
                is ByteArray -> v.joinToString(",")
                is Array<*> -> v.joinToString(",")
                else -> v.toString()
            }
        }
    }
}

enum class VdService(val label: String, val packageName: String, val action: String) {
    CAR_INFO("Car info", "com.desaysv.ivi.vds.carinfo", "action.desaysv.ivi.vds.carinfo.SERVICE"),
    CAR_STATE("Car state", "com.desaysv.ivi.vds.carstate", "action.desaysv.ivi.vds.carstate.SERVICE"),
    CAR_LAN("Car LAN", "com.desaysv.ivi.vds.carlan", "action.desaysv.ivi.vds.carlan.SERVICE"),
}

/** `VDEventCarInfo` module ids. The event id in a CarInfo VDEvent *is* the module id. */
object VdModules {
    /**
     * Ordered by how likely a module is to carry live driving data, so the
     * discovery sweep finds speed-like channels in the first seconds of a drive
     * rather than after it has walked through seat massage settings.
     */
    val SWEEP_ORDER = listOf(
        "MODULE_READONLY_INFO" to 327684,
        "MODULE_CAR_COMPUTER" to 327701,
        "MODULE_CLUSTER" to 327686,
        "MODULE_NEW_ENERGY" to 327682,
        "MODULE_AUTONOMOUS_DRIVING" to 327702,
        "MODULE_METER_MALFUNCTION" to 327697,
        "MODULE_MALFUNTION" to 327693,
        "MODULE_QUERY" to 327698,
        "MODULE_RADAR" to 327689,
        "MODULE_HVAC" to 327690,
        "MODULE_CAR_SETTING" to 327681,
        "MODULE_HUD" to 327683,
        "MODULE_AVM" to 327688,
        "MODULE_RVC" to 327691,
        "MODULE_AUTO_PARKING" to 327692,
        "MODULE_DOANOSE" to 327687,
        "MODULE_EXTERNAL_AMPLIFIER" to 327685,
        "MODULE_AMBIENT_LIGHT" to 327699,
        "MODULE_DVR" to 327700,
        "MODULE_VR" to 327696,
        "MODULE_OTA" to 327694,
        "MODULE_ACCOUNT" to 327695,
    )

    private val byId = SWEEP_ORDER.associate { (k, v) -> v to k }

    fun nameOf(id: Int): String? = byId[id]

    /** "READONLY_INFO" — short enough to sit in a channel name. */
    fun shortName(id: Int): String = nameOf(id)?.removePrefix("MODULE_") ?: id.toString()
}

/** Our side of `IVDBusNotify`: the service calls transaction 1 when a subscribed module changes. */
abstract class VdNotifyStub : android.os.Binder(), IInterface {

    init {
        attachInterface(this, VdBus.NOTIFY_DESCRIPTOR)
    }

    abstract fun onNotify(event: VdEvent)

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == VdBus.TRANSACTION_ON_NOTIFY) {
            data.enforceInterface(VdBus.NOTIFY_DESCRIPTOR)
            VdBus.readEvent(data)?.let(::onNotify)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/**
 * Our side of `IVDBusCallback`, used by the list-style subscribe (transaction 3).
 * Hands over the event plus the raw bytes it arrived in, so a payload we cannot
 * decode is still recorded rather than lost.
 */
abstract class VdCallbackStub : android.os.Binder(), IInterface {

    init {
        attachInterface(this, VdBus.CALLBACK_DESCRIPTOR)
    }

    abstract fun onEvent(event: VdEvent, raw: ByteArray?)

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == VdBus.TRANSACTION_ON_CALLBACK || code == VdBus.TRANSACTION_ON_BUS_NOTIFY) {
            data.enforceInterface(VdBus.CALLBACK_DESCRIPTOR)
            val start = data.dataPosition()
            val raw = runCatching { data.marshall().copyOfRange(start, data.dataSize()) }.getOrNull()
            VdBus.readEvent(data)?.let { onEvent(it, raw) }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}
