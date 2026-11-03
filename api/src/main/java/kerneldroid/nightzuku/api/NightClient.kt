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
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.ShizukuServiceConnections

/**
 * Immutable, lock-free snapshot of an attached Nightzuku client session.
 *
 * Replaces disjoint `@Volatile` fields so readers always observe a mutually consistent
 * `(binder, serverUid, serverVersion, seContext, permissionGranted, shouldShowRationale, capabilities)` slice.
 */
data class NightSession(
    val sessionId: Long,
    val binder: IBinder,
    val serverUid: Int,
    val serverVersion: Int,
    val seContext: String?,
    val permissionGranted: Boolean,
    val shouldShowRationale: Boolean,
    val capabilities: Int
)

/**
 * Connection and protocol handshake diagnostics for [NightClient].
 */
sealed class NightDiagnostics {
    data object Idle : NightDiagnostics()

    data class Connected(
        val sessionId: Long,
        val serverUid: Int,
        val serverVersion: Int,
        val serverMinVersion: Int,
        val seContext: String?,
        val capabilities: Int
    ) : NightDiagnostics()

    data class Incompatible(
        val clientVersion: Int,
        val serverVersion: Int,
        val serverMinVersion: Int = serverVersion,
        val status: Int = if (clientVersion < serverMinVersion) {
            NightProtocol.ATTACH_STATUS_CLIENT_TOO_OLD
        } else {
            NightProtocol.ATTACH_STATUS_SERVER_TOO_OLD
        }
    ) : NightDiagnostics() {
        constructor(clientVersion: Int, serverVersion: Int) : this(
            clientVersion = clientVersion,
            serverVersion = serverVersion,
            serverMinVersion = serverVersion
        )
    }

    data class Disconnected(val reason: String? = null) : NightDiagnostics()
}

/**
 * High-speed, zero-stub client connector for the native Nightzuku API (`INightService`).
 *
 * Communicates with `NightServiceEndpoint` using flat Parcel serialization,
 * atomic [NightSession] state snapshots, explicit [NightDiagnostics] handshake reporting,
 * non-blocking `oneway` async command execution (`execAsync` / `execAsyncFuture`),
 * one-shot `execFast`, kernel-EOF remote process spawning, UserService binding/peeking/unbinding,
 * proactive `connectFast` pull, global access pause control, and server diagnostics queries.
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

    fun interface OnDiagnosticsListener {
        fun onDiagnosticsChanged(diagnostics: NightDiagnostics)
    }

    private data class ListenerHolder<T>(
        val listener: T,
        val handler: Handler?
    )

    private val stateLock = Any()
    private val nextSessionId = AtomicLong(1L)
    private val nextExecCallId = AtomicLong(1L)

    private val session = AtomicReference<NightSession?>()
    private val diagnostics = AtomicReference<NightDiagnostics>(NightDiagnostics.Idle)
    private val pendingExecCalls = ConcurrentHashMap<Long, CompletableFuture<ExecResult>>()

    private var linkedBinder: IBinder? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receivedListeners = CopyOnWriteArrayList<ListenerHolder<OnBinderReceivedListener>>()
    private val deadListeners = CopyOnWriteArrayList<ListenerHolder<OnBinderDeadListener>>()
    private val permissionListeners = CopyOnWriteArrayList<ListenerHolder<OnPermissionResultListener>>()
    private val diagnosticsListeners = CopyOnWriteArrayList<ListenerHolder<OnDiagnosticsListener>>()

    private class ExecCallFuture(
        private val callId: Long
    ) : CompletableFuture<ExecResult>() {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            if (cancelled) {
                cancelExecAsync(callId)
            }
            return cancelled
        }
    }

    private val callbackBinder: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(NightProtocol.CALLBACK_DESCRIPTOR)
                    return true
                }
                NightProtocol.CALLBACK_ON_PERMISSION_CHANGED -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    val granted = data.readInt() != 0
                    val rationale = data.readInt() != 0
                    val caps = data.readInt()
                    session.updateAndGet { current ->
                        current?.copy(
                            permissionGranted = granted,
                            shouldShowRationale = rationale,
                            capabilities = caps
                        )
                    }
                    return true
                }
                NightProtocol.CALLBACK_ON_REQUEST_PERMISSION_RESULT -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    val requestCode = data.readInt()
                    val allowed = data.readInt() != 0
                    val caps = data.readInt()
                    session.updateAndGet { current ->
                        current?.copy(
                            permissionGranted = allowed,
                            shouldShowRationale = if (allowed) false else current.shouldShowRationale,
                            capabilities = caps
                        )
                    }
                    val grantResult = if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                    dispatchPermissionResult(requestCode, grantResult, caps)
                    return true
                }
                NightProtocol.CALLBACK_ON_EXEC_RESULT -> {
                    data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                    val callId = data.readLong()
                    val exitCode = data.readInt()
                    val timedOut = data.readInt() != 0
                    val stdout = data.createByteArray() ?: ByteArray(0)
                    val stderr = data.createByteArray() ?: ByteArray(0)
                    pendingExecCalls.remove(callId)?.complete(
                        ExecResult(exitCode, timedOut, stdout, stderr)
                    )
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

    private fun failPendingExecCalls(reason: String) {
        val keys = ArrayList(pendingExecCalls.keys)
        for (callId in keys) {
            pendingExecCalls.remove(callId)?.completeExceptionally(RemoteException(reason))
        }
    }

    @JvmStatic
    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        var shouldDispatchReceived = false
        var shouldDispatchDead = false
        var updatedDiagnostics: NightDiagnostics? = null

        synchronized(stateLock) {
            val existingSession = session.get()
            if (newBinder != null && existingSession?.binder === newBinder && newBinder.pingBinder()) {
                return
            }

            unlinkCurrentLocked()

            if (newBinder == null) {
                val hadSession = existingSession != null
                session.set(null)
                val disc = NightDiagnostics.Disconnected("Binder died or cleared")
                diagnostics.set(disc)
                updatedDiagnostics = disc
                shouldDispatchDead = hadSession
            } else {
                try {
                    newBinder.linkToDeath(deathRecipient, 0)
                    linkedBinder = newBinder
                } catch (t: Throwable) {
                    Log.w(TAG, "linkToDeath failed", t)
                    session.set(null)
                    val disc = NightDiagnostics.Disconnected("linkToDeath failed: ${t.message}")
                    diagnostics.set(disc)
                    updatedDiagnostics = disc
                    return
                }

                if (packageName != null) {
                    try {
                        val attached = attachFlat(newBinder, packageName)
                        updatedDiagnostics = diagnostics.get()
                        if (attached) {
                            shouldDispatchReceived = true
                        } else {
                            unlinkCurrentLocked()
                            session.set(null)
                            if (existingSession != null) {
                                shouldDispatchDead = true
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Flat attach handshake failed", t)
                        unlinkCurrentLocked()
                        session.set(null)
                        val disc = NightDiagnostics.Disconnected("Handshake failed: ${t.message}")
                        diagnostics.set(disc)
                        updatedDiagnostics = disc
                        return
                    }
                } else {
                    val newSession = NightSession(
                        sessionId = nextSessionId.getAndIncrement(),
                        binder = newBinder,
                        serverUid = -1,
                        serverVersion = NightProtocol.PROTOCOL_VERSION,
                        seContext = null,
                        permissionGranted = false,
                        shouldShowRationale = false,
                        capabilities = 0
                    )
                    session.set(newSession)
                }
            }
        }

        if (shouldDispatchDead) {
            failPendingExecCalls("NightService binder died")
        }

        updatedDiagnostics?.let(::dispatchDiagnostics)

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
    private fun attachFlat(target: IBinder, packageName: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeInt(NightProtocol.PROTOCOL_VERSION)
            data.writeString(packageName)
            target.transact(NightProtocol.TRANSACTION_ATTACH, data, reply, 0)
            reply.readException()

            val status = reply.readInt()
            val srvProtocolVersion = reply.readInt()
            val srvMinProtocolVersion = reply.readInt()

            if (status != NightProtocol.ATTACH_STATUS_OK) {
                val incompatible = NightDiagnostics.Incompatible(
                    clientVersion = NightProtocol.PROTOCOL_VERSION,
                    serverVersion = srvProtocolVersion,
                    serverMinVersion = srvMinProtocolVersion,
                    status = status
                )
                diagnostics.set(incompatible)
                session.set(null)
                Log.w(
                    TAG,
                    "Protocol version mismatch: status=$status client=${NightProtocol.PROTOCOL_VERSION} " +
                        "server=$srvProtocolVersion (min=$srvMinProtocolVersion)"
                )
                return false
            }

            val srvUid = reply.readInt()
            val grantState = reply.readInt()
            val caps = reply.readInt()
            val seContext = reply.readString()
            val sid = nextSessionId.getAndIncrement()

            val newSession = NightSession(
                sessionId = sid,
                binder = target,
                serverUid = srvUid,
                serverVersion = srvProtocolVersion,
                seContext = seContext,
                permissionGranted = grantState == NightProtocol.STATE_GRANTED,
                shouldShowRationale = grantState == NightProtocol.STATE_DENIED_PERMANENT,
                capabilities = caps
            )
            session.set(newSession)
            diagnostics.set(
                NightDiagnostics.Connected(
                    sessionId = sid,
                    serverUid = srvUid,
                    serverVersion = srvProtocolVersion,
                    serverMinVersion = srvMinProtocolVersion,
                    seContext = seContext,
                    capabilities = caps
                )
            )
            return true
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun getSession(): NightSession? = session.get()

    @JvmStatic
    fun getDiagnostics(): NightDiagnostics = diagnostics.get()

    @JvmStatic
    fun getBinder(): IBinder? = session.get()?.binder

    @JvmStatic
    fun pingBinder(): Boolean = session.get()?.binder?.pingBinder() == true

    @JvmStatic
    fun getUid(): Int = session.get()?.serverUid ?: -1

    @JvmStatic
    fun getVersion(): Int = session.get()?.serverVersion ?: -1

    @JvmStatic
    fun getSELinuxContext(): String? = session.get()?.seContext

    @JvmStatic
    fun getCapabilities(): Int = session.get()?.capabilities ?: 0

    @JvmStatic
    fun hasCapability(capabilityFlag: Int): Boolean = ((session.get()?.capabilities ?: 0) and capabilityFlag) != 0

    @JvmStatic
    fun checkSelfPermission(): Int {
        val currentSession = session.get() ?: return PackageManager.PERMISSION_DENIED
        if (currentSession.permissionGranted) return PackageManager.PERMISSION_GRANTED
        val currentBinder = currentSession.binder
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            currentBinder.transact(NightProtocol.TRANSACTION_CHECK_PERMISSION, data, reply, 0)
            reply.readException()
            val granted = reply.readInt() != 0
            val rationale = reply.readInt() != 0
            val caps = reply.readInt()
            session.updateAndGet { s ->
                if (s?.binder === currentBinder) {
                    s.copy(
                        permissionGranted = granted,
                        shouldShowRationale = rationale,
                        capabilities = caps
                    )
                } else {
                    s
                }
            }
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @JvmStatic
    fun shouldShowRequestPermissionRationale(): Boolean {
        val currentSession = session.get() ?: return false
        if (currentSession.permissionGranted) return false
        return currentSession.shouldShowRationale
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

    /**
     * Dispatches an asynchronous command over `IBinder.FLAG_ONEWAY` without blocking any client
     * or server Binder thread during command execution. Cancelling the returned [CompletableFuture]
     * sends `oneway TRANSACTION_CANCEL_EXEC(callId)` to immediately kill the remote process group.
     */
    @JvmStatic
    @JvmOverloads
    fun execAsyncFuture(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Long = NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS
    ): CompletableFuture<ExecResult> {
        val current = requireBinder()
        val callId = nextExecCallId.getAndIncrement()
        val future = ExecCallFuture(callId)
        pendingExecCalls[callId] = future

        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.writeLong(callId)
            data.writeStringArray(cmd)
            data.writeStringArray(env)
            data.writeString(dir)
            data.writeLong(timeoutMs)
            // Oneway binder transactions do not carry the sender PID, so include it explicitly.
            data.writeInt(Process.myPid())
            val sent = current.transact(NightProtocol.TRANSACTION_EXEC_ASYNC, data, null, IBinder.FLAG_ONEWAY)
            if (!sent) {
                pendingExecCalls.remove(callId)
                future.completeExceptionally(RemoteException("TRANSACTION_EXEC_ASYNC returned false"))
            }
        } catch (t: Throwable) {
            pendingExecCalls.remove(callId)
            future.completeExceptionally(t)
        } finally {
            data.recycle()
        }
        return future
    }

    /**
     * Suspending coroutine wrapper over [execAsyncFuture] with zero thread-pool blocking.
     * Cancelling the calling coroutine immediately dispatches `oneway TRANSACTION_CANCEL_EXEC(callId)`.
     */
    suspend fun execAsync(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Long = NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS
    ): ExecResult = suspendCancellableCoroutine { cont ->
        val future = try {
            execAsyncFuture(cmd, env, dir, timeoutMs)
        } catch (t: Throwable) {
            cont.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            future.cancel(true)
        }
        future.whenComplete { result, error ->
            if (error != null) {
                if (!cont.isCancelled) {
                    cont.resumeWithException(error)
                }
            } else if (result != null) {
                cont.resume(result)
            }
        }
    }

    @JvmStatic
    fun cancelExecAsync(callId: Long) {
        val current = session.get()?.binder
        if (current != null) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
                data.writeLong(callId)
                current.transact(NightProtocol.TRANSACTION_CANCEL_EXEC, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Throwable) {
            } finally {
                data.recycle()
            }
        }
        val pending = pendingExecCalls.remove(callId)
        if (pending != null && !pending.isDone) {
            pending.complete(
                ExecResult(
                    exitCode = 137,
                    timedOut = false,
                    stdout = ByteArray(0),
                    stderr = "Command execution cancelled".toByteArray(StandardCharsets.UTF_8)
                )
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    fun newProcess(
        cmd: Array<String>,
        env: Array<String>? = null,
        dir: String? = null
    ): ShizukuRemoteProcess {
        val current = session.get()?.binder
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
                    val exitPipe = if (reply.dataAvail() > 0 && reply.readInt() != 0) {
                        ParcelFileDescriptor.CREATOR.createFromParcel(reply)
                    } else {
                        null
                    }
                    return ShizukuRemoteProcess(remoteProcess, exitPipe)
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

    private fun cleanupFailedConnection(connection: rikka.shizuku.ShizukuServiceConnection, conn: ServiceConnection) {
        connection.removeConnection(conn)
        if (!connection.hasConnections()) {
            ShizukuServiceConnections.remove(connection)
        }
    }

    @JvmStatic
    fun bindUserService(args: Shizuku.UserServiceArgs, conn: ServiceConnection): Int {
        val current = session.get()?.binder
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
                cleanupFailedConnection(connection, conn)
            } catch (t: Throwable) {
                cleanupFailedConnection(connection, conn)
                if (t is RemoteException) throw RuntimeException(t)
                throw t
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
        val current = session.get()?.binder
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
                    val res = reply.readInt()
                    if (res < 0) {
                        cleanupFailedConnection(connection, conn)
                    }
                    return res
                }
                cleanupFailedConnection(connection, conn)
            } catch (t: Throwable) {
                cleanupFailedConnection(connection, conn)
                if (t is RemoteException) throw RuntimeException(t)
                throw t
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        return Shizuku.peekUserService(args, conn)
    }

    @JvmStatic
    fun unbindUserService(args: Shizuku.UserServiceArgs, conn: ServiceConnection?, remove: Boolean) {
        val current = session.get()?.binder
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
        val current = session.get()?.binder ?: return null
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
            shouldNotifyNow = session.get()?.binder?.pingBinder() == true
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

    @JvmStatic
    @JvmOverloads
    fun addDiagnosticsListener(listener: OnDiagnosticsListener, handler: Handler? = null) {
        synchronized(stateLock) {
            diagnosticsListeners.add(ListenerHolder(listener, handler))
        }
    }

    @JvmStatic
    fun removeDiagnosticsListener(listener: OnDiagnosticsListener): Boolean {
        synchronized(stateLock) {
            var removed = false
            for (holder in diagnosticsListeners) {
                if (holder.listener == listener) {
                    diagnosticsListeners.remove(holder)
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

    private fun dispatchDiagnostics(diag: NightDiagnostics) {
        for (holder in diagnosticsListeners) {
            val h = holder.handler
            if (h != null) {
                h.post { holder.listener.onDiagnosticsChanged(diag) }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                holder.listener.onDiagnosticsChanged(diag)
            } else {
                mainHandler.post { holder.listener.onDiagnosticsChanged(diag) }
            }
        }
    }

    @JvmStatic
    fun requireBinder(): IBinder {
        return session.get()?.binder ?: throw IllegalStateException("NightClient binder hasn't been received")
    }
}
