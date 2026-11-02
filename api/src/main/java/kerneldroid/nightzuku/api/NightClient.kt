/*
 * Copyright (c) 2026 kerneldroid
 * Portions adapted from d4rken-org/porter-api under Apache-2.0 / MIT:
 * - Single-unlink DeathRecipient guard and atomic sticky-listener registration
 * - Verified ContentProvider authority resolution before cross-process binder fetch
 * - Per-listener UserService unbinding semantics
 */

package kerneldroid.nightzuku.api

import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.ShizukuServiceConnections

/**
 * High-speed, zero-stub client connector for the native Nightzuku API (`INightService`).
 *
 * Communicates with `NightServiceEndpoint` using flat Parcel serialization,
 * supports capability inspection, guarded `transactRemote`, one-shot `execFast`,
 * remote process spawning, UserService binding/peeking/unbinding, proactive `connectFast` pull,
 * global access pause control, and server diagnostics queries.
 */
object NightClient {

    private const val TAG = "NightClient"
    private const val MANAGER_PACKAGE = "kerneldroid.nightzuku"

    data class ExecResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val stdout: ByteArray,
        val stderr: ByteArray
    ) {
        val outputText: String
            get() = String(stdout, StandardCharsets.UTF_8)

        val errorText: String
            get() = String(stderr, StandardCharsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExecResult) return false
            return exitCode == other.exitCode &&
                timedOut == other.timedOut &&
                stdout.contentEquals(other.stdout) &&
                stderr.contentEquals(other.stderr)
        }

        override fun hashCode(): Int {
            var result = exitCode
            result = 31 * result + timedOut.hashCode()
            result = 31 * result + stdout.contentHashCode()
            result = 31 * result + stderr.contentHashCode()
            return result
        }
    }

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    fun interface OnPermissionResultListener {
        fun onPermissionResult(requestCode: Int, grantResult: Int, capabilities: Int)
    }

    private data class ListenerHolder<T>(
        val listener: T,
        val handler: Handler?
    )

    private val stateLock = Any()

    @Volatile
    private var binder: IBinder? = null

    private var linkedBinder: IBinder? = null

    @Volatile
    private var serverUid: Int = -1

    @Volatile
    private var serverVersion: Int = -1

    @Volatile
    private var serverContext: String? = null

    @Volatile
    private var permissionGranted: Boolean = false

    @Volatile
    private var shouldShowRationale: Boolean = false

    @Volatile
    private var capabilitiesMask: Int = 0

    @Volatile
    private var binderReady: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receivedListeners = CopyOnWriteArrayList<ListenerHolder<OnBinderReceivedListener>>()
    private val deadListeners = CopyOnWriteArrayList<ListenerHolder<OnBinderDeadListener>>()
    private val permissionListeners = CopyOnWriteArrayList<ListenerHolder<OnPermissionResultListener>>()

    private val callbackBinder: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(NightProtocol.CALLBACK_DESCRIPTOR)
                    return true
                }
                NightProtocol.CALLBACK_ON_PERMISSION_CHANGED -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    permissionGranted = data.readInt() != 0
                    shouldShowRationale = data.readInt() != 0
                    capabilitiesMask = data.readInt()
                    return true
                }
                NightProtocol.CALLBACK_ON_REQUEST_PERMISSION_RESULT -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    val requestCode = data.readInt()
                    val allowed = data.readInt() != 0
                    val caps = data.readInt()
                    permissionGranted = allowed
                    capabilitiesMask = caps
                    val grantResult = if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    dispatchPermissionResult(requestCode, grantResult, caps)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        onBinderReceived(null, null)
    }

    private fun unlinkCurrentLocked() {
        val currentLinked = linkedBinder ?: return
        linkedBinder = null
        try {
            currentLinked.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        var shouldDispatchReceived = false
        var shouldDispatchDead = false

        synchronized(stateLock) {
            if (newBinder != null && binder === newBinder && binderReady && newBinder.pingBinder()) {
                return
            }

            unlinkCurrentLocked()

            if (newBinder == null) {
                val hadBinder = binder != null || binderReady
                binder = null
                serverUid = -1
                serverVersion = -1
                serverContext = null
                permissionGranted = false
                shouldShowRationale = false
                capabilitiesMask = 0
                binderReady = false
                shouldDispatchDead = hadBinder
            } else {
                binder = newBinder
                try {
                    newBinder.linkToDeath(deathRecipient, 0)
                    linkedBinder = newBinder
                } catch (t: Throwable) {
                    Log.w(TAG, "linkToDeath failed", t)
                    binder = null
                    binderReady = false
                    return
                }

                if (packageName != null) {
                    try {
                        attachFlat(newBinder, packageName)
                        binderReady = true
                        shouldDispatchReceived = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "Flat attach handshake failed", t)
                        unlinkCurrentLocked()
                        binder = null
                        binderReady = false
                        return
                    }
                }
            }
        }

        if (shouldDispatchDead) {
            dispatchBinderDead()
        } else if (shouldDispatchReceived) {
            dispatchBinderReceived()
        }
    }

    /**
     * Proactively pulls the privileged Nightzuku binder (and legacy Shizuku binder if present)
     * without waiting for the server's asynchronous `IActivityManager` process observer.
     *
     * Queries the app's own `NightProvider` first (for non-provider processes), then queries the
     * Nightzuku Manager's connector/provider endpoints.
     */
    @JvmStatic
    fun connectFast(context: Context): Boolean {
        if (pingBinder()) return true

        val appContext = context.applicationContext ?: context
        val pkg = appContext.packageName

        // 1. Check our own process provider authority (verifying ownership so no other app can spoof it)
        val selfNightAuthority = "$pkg${NightProtocol.PROVIDER_SUFFIX}"
        if (ownsAuthority(appContext, selfNightAuthority)) {
            val reply = callProviderSafe(appContext, selfNightAuthority, NightProtocol.METHOD_GET_BINDER, null)
            val nightBinder = LegacyParcelCodec.extractBinderFromBundle(reply)
            if (nightBinder != null && nightBinder.pingBinder()) {
                onBinderReceived(nightBinder, pkg)
            }
        }

        val selfLegacyAuthority = "$pkg${NightProtocol.LEGACY_PROVIDER_SUFFIX}"
        if (!Shizuku.pingBinder() && ownsAuthority(appContext, selfLegacyAuthority)) {
            val reply = callProviderSafe(appContext, selfLegacyAuthority, "getBinder", null)
            val legacyBinder = LegacyParcelCodec.extractBinderFromBundle(reply)
            if (legacyBinder != null && legacyBinder.pingBinder()) {
                Shizuku.onBinderReceived(legacyBinder, pkg)
            }
        }

        if (pingBinder()) return true

        // 2. Pull directly from Nightzuku Manager connector or manager provider authorities
        val managerAuthorities = arrayOf(
            "$MANAGER_PACKAGE${NightProtocol.CONNECTOR_SUFFIX}",
            "$MANAGER_PACKAGE${NightProtocol.PROVIDER_SUFFIX}"
        )
        for (authority in managerAuthorities) {
            val reply = callProviderSafe(appContext, authority, NightProtocol.METHOD_REQUEST_BINDER, pkg)
                ?: callProviderSafe(appContext, authority, NightProtocol.LEGACY_METHOD_REQUEST_BINDER, pkg)
                ?: continue

            val extractedBinder = LegacyParcelCodec.extractBinderFromBundle(reply)
            if (extractedBinder != null && extractedBinder.pingBinder()) {
                val descriptor = runCatching { extractedBinder.interfaceDescriptor }.getOrNull()
                if (descriptor == NightProtocol.DESCRIPTOR) {
                    onBinderReceived(extractedBinder, pkg)
                } else {
                    Shizuku.onBinderReceived(extractedBinder, pkg)
                }
            }

            if (pingBinder()) return true
        }

        return pingBinder()
    }

    private fun ownsAuthority(context: Context, authority: String): Boolean {
        return try {
            context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    private fun callProviderSafe(
        context: Context,
        authority: String,
        method: String,
        arg: String?
    ): Bundle? {
        return try {
            val extras = Bundle().apply {
                if (arg != null) {
                    putString("packageName", arg)
                }
            }
            context.contentResolver.call(
                Uri.parse("content://$authority"),
                method,
                arg,
                extras
            )
        } catch (_: Throwable) {
            null
        }
    }

    @Throws(RemoteException::class)
    private fun attachFlat(target: IBinder, packageName: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeInt(NightProtocol.PROTOCOL_VERSION)
            data.writeString(packageName)
            target.transact(NightProtocol.TRANSACTION_ATTACH, data, reply, 0)
            reply.readException()
            serverUid = reply.readInt()
            serverVersion = reply.readInt()
            val grantState = reply.readInt()
            capabilitiesMask = reply.readInt()
            serverContext = reply.readString()
            permissionGranted = grantState == NightProtocol.STATE_GRANTED
            shouldShowRationale = grantState == NightProtocol.STATE_DENIED_PERMANENT
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getBinder(): IBinder? = binder

    @JvmStatic
    fun pingBinder(): Boolean = binder?.pingBinder() == true

    @JvmStatic
    fun getUid(): Int = serverUid

    @JvmStatic
    fun getVersion(): Int = serverVersion

    @JvmStatic
    fun getSELinuxContext(): String? = serverContext

    @JvmStatic
    fun getCapabilities(): Int = capabilitiesMask

    @JvmStatic
    fun hasCapability(capabilityFlag: Int): Boolean = (capabilitiesMask and capabilityFlag) != 0

    @JvmStatic
    fun checkSelfPermission(): Int {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED
        val current = binder ?: return PackageManager.PERMISSION_DENIED
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            current.transact(NightProtocol.TRANSACTION_CHECK_PERMISSION, data, reply, 0)
            reply.readException()
            permissionGranted = reply.readInt() != 0
            shouldShowRationale = reply.readInt() != 0
            capabilitiesMask = reply.readInt()
            if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun shouldShowRequestPermissionRationale(): Boolean {
        if (permissionGranted) return false
        return shouldShowRationale
    }

    @JvmStatic
    fun requestPermission(requestCode: Int) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(requestCode)
            current.transact(NightProtocol.TRANSACTION_REQUEST_PERMISSION, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun execFast(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Long = NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS
    ): ExecResult {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeStringArray(cmd)
            data.writeStringArray(env)
            data.writeString(dir)
            data.writeLong(timeoutMs)
            current.transact(NightProtocol.TRANSACTION_EXEC_FAST, data, reply, 0)
            reply.readException()
            val exitCode = reply.readInt()
            val timedOut = reply.readInt() != 0
            val stdout = reply.createByteArray() ?: ByteArray(0)
            val stderr = reply.createByteArray() ?: ByteArray(0)
            ExecResult(exitCode, timedOut, stdout, stderr)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun newProcess(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null
    ): ShizukuRemoteProcess {
        val current = binder
        if (current != null && current.pingBinder()) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                data.writeStringArray(cmd)
                data.writeStringArray(env)
                data.writeString(dir)
                if (current.transact(NightProtocol.TRANSACTION_NEW_PROCESS, data, reply, 0)) {
                    reply.readException()
                    val remoteBinder = reply.readStrongBinder()
                    val remoteProcess = IRemoteProcess.Stub.asInterface(remoteBinder)
                        ?: throw IllegalStateException("Server returned null IRemoteProcess")
                    return ShizukuRemoteProcess(remoteProcess)
                }
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        return Shizuku.newProcess(cmd, env, dir)
    }

    @JvmStatic
    fun bindUserService(args: Shizuku.UserServiceArgs, conn: ServiceConnection): Int {
        val current = binder
        if (current != null && current.pingBinder()) {
            val connection = ShizukuServiceConnections.get(args)
            connection.addConnection(conn)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                data.writeStrongBinder(connection.asBinder())
                data.writeBundle(args.forAdd())
                if (current.transact(NightProtocol.TRANSACTION_ADD_USER_SERVICE, data, reply, 0)) {
                    reply.readException()
                    return reply.readInt()
                }
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        Shizuku.bindUserService(args, conn)
        return NightProtocol.USER_SERVICE_RESULT_BOUND
    }

    @JvmStatic
    fun peekUserService(args: Shizuku.UserServiceArgs, conn: ServiceConnection): Int {
        val current = binder
        if (current != null && current.pingBinder()) {
            val connection = ShizukuServiceConnections.get(args)
            connection.addConnection(conn)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                val options = args.forAdd().apply {
                    putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true)
                }
                data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                data.writeStrongBinder(connection.asBinder())
                data.writeBundle(options)
                if (current.transact(NightProtocol.TRANSACTION_ADD_USER_SERVICE, data, reply, 0)) {
                    reply.readException()
                    return reply.readInt()
                }
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        return Shizuku.peekUserService(args, conn)
    }

    @JvmStatic
    fun unbindUserService(args: Shizuku.UserServiceArgs, conn: ServiceConnection?, remove: Boolean) {
        val current = binder
        if (current != null && current.pingBinder()) {
            if (remove) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                    data.writeStrongBinder(null)
                    data.writeBundle(args.forRemove(true))
                    current.transact(NightProtocol.TRANSACTION_REMOVE_USER_SERVICE, data, reply, 0)
                    reply.readException()
                    return
                } catch (e: RemoteException) {
                    throw RuntimeException(e)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            } else {
                val connection = ShizukuServiceConnections.get(args)
                if (conn != null) {
                    connection.removeConnection(conn)
                    if (connection.hasConnections()) {
                        return
                    }
                } else {
                    connection.clearConnections()
                }
                ShizukuServiceConnections.remove(connection)
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                    data.writeStrongBinder(connection.asBinder())
                    data.writeBundle(args.forRemove(false))
                    current.transact(NightProtocol.TRANSACTION_REMOVE_USER_SERVICE, data, reply, 0)
                    reply.readException()
                    return
                } catch (e: RemoteException) {
                    throw RuntimeException(e)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
        Shizuku.unbindUserService(args, conn, remove)
    }

    @JvmStatic
    fun claimUserServiceLaunch(token: String): Boolean {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeString(token)
            current.transact(NightProtocol.TRANSACTION_CLAIM_USER_SERVICE, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getUidCapabilities(uid: Int): Int {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(uid)
            current.transact(NightProtocol.TRANSACTION_GET_UID_CAPABILITIES, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun setUidCapabilities(uid: Int, capabilities: Int) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(uid)
            data.writeInt(capabilities and NightProtocol.MASK_CAPABILITIES)
            current.transact(NightProtocol.TRANSACTION_SET_UID_CAPABILITIES, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun transactRemote(targetBinder: IBinder, code: Int, data: Parcel, reply: Parcel?, flags: Int) {
        val current = requireBinder()
        val wrapped = Parcel.obtain()
        try {
            wrapped.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            wrapped.writeStrongBinder(targetBinder)
            wrapped.writeInt(code)
            wrapped.writeInt(flags)
            wrapped.appendFrom(data, 0, data.dataSize())
            current.transact(NightProtocol.TRANSACTION_TRANSACT_REMOTE, wrapped, reply, 0)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            wrapped.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getSystemProperty(name: String, defaultValue: String? = null): String? {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeString(name)
            data.writeString(defaultValue)
            current.transact(NightProtocol.TRANSACTION_GET_SYSTEM_PROPERTY, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun setSystemProperty(name: String, value: String) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeString(name)
            data.writeString(value)
            current.transact(NightProtocol.TRANSACTION_SET_SYSTEM_PROPERTY, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun isAccessPaused(): Boolean {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            current.transact(NightProtocol.TRANSACTION_GET_ACCESS_PAUSED, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun setAccessPaused(paused: Boolean) {
        val current = requireBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeInt(if (paused) 1 else 0)
            current.transact(NightProtocol.TRANSACTION_SET_ACCESS_PAUSED, data, reply, 0)
            reply.readException()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getServerDiagnostics(): Bundle? {
        val current = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            if (!current.transact(NightProtocol.TRANSACTION_GET_SERVER_DIAGNOSTICS, data, reply, 0)) {
                return null
            }
            reply.readException()
            reply.readBundle(NightClient::class.java.classLoader)
        } catch (_: Throwable) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun addBinderReceivedListener(listener: OnBinderReceivedListener, handler: Handler? = null) {
        synchronized(stateLock) {
            receivedListeners.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    @JvmOverloads
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener, handler: Handler? = null) {
        val shouldNotifyNow: Boolean
        synchronized(stateLock) {
            receivedListeners.add(ListenerHolder(listener, handler))
            shouldNotifyNow = binderReady && binder?.pingBinder() == true
        }
        if (shouldNotifyNow) {
            if (handler != null) {
                handler.post { listener.onBinderReceived() }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived()
            } else {
                mainHandler.post { listener.onBinderReceived() }
            }
        }
    }

    @JvmStatic
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean {
        synchronized(stateLock) {
            var removed = false
            for (holder in receivedListeners) {
                if (holder.listener == listener) {
                    receivedListeners.remove(holder)
                    removed = true
                }
            }
            return removed
        }
    }

    @JvmStatic
    @JvmOverloads
    fun addBinderDeadListener(listener: OnBinderDeadListener, handler: Handler? = null) {
        synchronized(stateLock) {
            deadListeners.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removeBinderDeadListener(listener: OnBinderDeadListener): Boolean {
        synchronized(stateLock) {
            var removed = false
            for (holder in deadListeners) {
                if (holder.listener == listener) {
                    deadListeners.remove(holder)
                    removed = true
                }
            }
            return removed
        }
    }

    @JvmStatic
    @JvmOverloads
    fun addPermissionResultListener(listener: OnPermissionResultListener, handler: Handler? = null) {
        synchronized(stateLock) {
            permissionListeners.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removePermissionResultListener(listener: OnPermissionResultListener): Boolean {
        synchronized(stateLock) {
            var removed = false
            for (holder in permissionListeners) {
                if (holder.listener == listener) {
                    permissionListeners.remove(holder)
                    removed = true
                }
            }
            return removed
        }
    }

    private fun dispatchBinderReceived() {
        for (holder in receivedListeners) {
            val h = holder.handler
            if (h != null) {
                h.post { holder.listener.onBinderReceived() }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                holder.listener.onBinderReceived()
            } else {
                mainHandler.post { holder.listener.onBinderReceived() }
            }
        }
    }

    private fun dispatchBinderDead() {
        for (holder in deadListeners) {
            val h = holder.handler
            if (h != null) {
                h.post { holder.listener.onBinderDead() }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                holder.listener.onBinderDead()
            } else {
                mainHandler.post { holder.listener.onBinderDead() }
            }
        }
    }

    private fun dispatchPermissionResult(requestCode: Int, grantResult: Int, capabilities: Int) {
        for (holder in permissionListeners) {
            val h = holder.handler
            if (h != null) {
                h.post { holder.listener.onPermissionResult(requestCode, grantResult, capabilities) }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                holder.listener.onPermissionResult(requestCode, grantResult, capabilities)
            } else {
                mainHandler.post { holder.listener.onPermissionResult(requestCode, grantResult, capabilities) }
            }
        }
    }

    @JvmStatic
    fun requireBinder(): IBinder {
        return binder ?: throw IllegalStateException("NightClient binder hasn't been received")
    }
}
