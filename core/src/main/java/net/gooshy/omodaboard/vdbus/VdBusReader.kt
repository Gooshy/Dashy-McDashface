package net.gooshy.omodaboard.vdbus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds a VDS service and reads from it. Get and subscribe only — there is no
 * write method here, by design (see [VdBus]).
 *
 * Adapted from CheryPicker's `VdBusClient`, minus its StateFlow/EventLog
 * plumbing: the recorder wants plain results it can log as findings.
 */
class VdBusReader(private val context: Context, val service: VdService) {

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    var status: String = "not bound"
        private set

    private val connected = CountDownLatch(1)
    private val subscriptions = mutableMapOf<Int, VdNotifyStub>()
    @Volatile private var listSubscription: Pair<IntArray, VdCallbackStub>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, bound: IBinder?) {
            binder = bound
            status = "connected (${name?.flattenToShortString()})"
            // The service restarts on its own schedule; put our subscriptions back.
            synchronized(subscriptions) {
                subscriptions.forEach { (module, stub) -> transactSubscribe(module, stub, true) }
            }
            listSubscription?.let { (ids, cb) -> subscribeEvents(ids, cb) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            status = "disconnected by the service"
        }

        override fun onNullBinding(name: ComponentName?) {
            status = "bound but returned no binder"
            connected.countDown()
        }
    }

    val isConnected: Boolean get() = binder != null

    /** Binds and waits up to [timeoutMs]. Returns whether a binder arrived. */
    fun connect(timeoutMs: Long = 5_000): Boolean {
        val intent = Intent(service.action).setPackage(service.packageName)
        if (context.packageManager.resolveService(intent, 0) == null) {
            status = "not installed (nothing resolves ${service.action})"
            return false
        }
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (se: SecurityException) {
            status = "permission denied: ${se.message}"
            return false
        }
        if (!bound) {
            status = "bindService() returned false"
            return false
        }
        status = "binding…"
        connected.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (binder == null && status == "binding…") status = "bind timed out after ${timeoutMs}ms"
        return binder != null
    }

    fun disconnect() {
        clearListSubscription()
        synchronized(subscriptions) {
            subscriptions.forEach { (module, stub) -> transactSubscribe(module, stub, false) }
            subscriptions.clear()
        }
        runCatching { context.unbindService(connection) }
        binder = null
    }

    /**
     * Reads one command's current value. Null when the service has nothing for
     * it — which, during the sweep, is the common and uninteresting answer.
     */
    fun get(moduleId: Int, cmd: Int): VdEvent? = transact(
        moduleId = moduleId,
        code = VdBus.TRANSACTION_GET,
        payload = Bundle().apply { putInt(VdBus.KEY_CMD_ID, cmd) },
        readReply = { VdBus.readEvent(it) },
    ).getOrNull()

    /** Push every change on [moduleId] to [onEvent]. Empty cmd array = whole module. */
    fun subscribe(moduleId: Int, onEvent: (VdEvent) -> Unit): Result<Unit> {
        val stub = object : VdNotifyStub() {
            override fun onNotify(event: VdEvent) = onEvent(event)
        }
        val result = transactSubscribe(moduleId, stub, true)
        if (result.isSuccess) synchronized(subscriptions) { subscriptions[moduleId] = stub }
        return result
    }

    /**
     * The vendor client's own subscribe: a list of event ids and one callback.
     * This is how CarLAN events are subscribed (CarInfo modules use the
     * per-module [subscribe] instead).
     */
    fun subscribeEvents(eventIds: IntArray, callback: VdCallbackStub): Result<Unit> {
        val target = binder ?: return Result.failure(IllegalStateException("not connected: $status"))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(VdBus.DESCRIPTOR)
            data.writeIntArray(eventIds)
            data.writeInt(android.os.Process.myPid())
            data.writeString(context.packageName)
            data.writeStrongBinder(callback)
            target.transact(VdBus.TRANSACTION_SUBSCRIBE_LIST, data, reply, 0)
            reply.readException()
            listSubscription = eventIds to callback
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Unsubscribes by re-subscribing an empty list with the same callback. */
    private fun clearListSubscription() {
        val (_, cb) = listSubscription ?: return
        listSubscription = null
        runCatching { subscribeEvents(IntArray(0), cb) }
        listSubscription = null
    }

    private fun transactSubscribe(moduleId: Int, stub: VdNotifyStub, subscribe: Boolean): Result<Unit> =
        transact(
            moduleId = moduleId,
            code = if (subscribe) VdBus.TRANSACTION_SUBSCRIBE_EVENT else VdBus.TRANSACTION_UNSUBSCRIBE_EVENT,
            payload = Bundle().apply { putIntArray(VdBus.KEY_CMD_ID_ARRAY, IntArray(0)) },
            extraArgs = { it.writeStrongBinder(stub) },
            readReply = { },
        )

    /** One binder round trip against IVDBus. Every transaction starts with a nullable VDEvent. */
    private fun <T> transact(
        moduleId: Int,
        code: Int,
        payload: Bundle,
        extraArgs: (Parcel) -> Unit = {},
        readReply: (Parcel) -> T,
    ): Result<T> {
        val target = binder ?: return Result.failure(IllegalStateException("not connected: $status"))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(VdBus.DESCRIPTOR)
            data.writeInt(1)
            VdBus.writeEvent(data, moduleId, payload)
            extraArgs(data)
            target.transact(code, data, reply, 0)
            reply.readException()
            Result.success(readReply(reply))
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
