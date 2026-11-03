package rikka.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX
import java.util.Objects
import kerneldroid.nightzuku.api.NightClient
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION
import rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED

open class Shizuku protected constructor() {

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    fun interface OnRequestPermissionResultListener {
        fun onRequestPermissionResult(requestCode: Int, grantResult: Int)
    }

    private data class ListenerHolder<T>(
        val listener: T,
        val handler: Handler?
    )

    class UserServiceArgs(
        @JvmField val componentName: ComponentName
    ) {
        @JvmField internal var versionCode: Int = 1
        @JvmField internal var processName: String? = null
        @JvmField internal var tag: String? = null
        @JvmField internal var debuggable: Boolean = false
        @JvmField internal var daemon: Boolean = true
        @JvmField internal var use32BitAppProcess: Boolean = false

        fun daemon(daemon: Boolean): UserServiceArgs = apply {
            this.daemon = daemon
        }

        fun tag(tag: String): UserServiceArgs = apply {
            this.tag = tag
        }

        fun version(versionCode: Int): UserServiceArgs = apply {
            this.versionCode = versionCode
        }

        fun debuggable(debuggable: Boolean): UserServiceArgs = apply {
            this.debuggable = debuggable
        }

        fun processNameSuffix(processNameSuffix: String): UserServiceArgs = apply {
            this.processName = processNameSuffix
        }

        fun use32BitAppProcess(use32BitAppProcess: Boolean): UserServiceArgs = apply {
            this.use32BitAppProcess = use32BitAppProcess
        }

        internal fun forAdd(): Bundle {
            return Bundle().apply {
                putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName)
                putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, debuggable)
                putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, versionCode)
                putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, daemon)
                putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, use32BitAppProcess)
                putString(
                    ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
                    Objects.requireNonNull(processName, "process name suffix must not be null")
                )
                tag?.let { putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, it) }
            }
        }

        internal fun forRemove(remove: Boolean): Bundle {
            return Bundle().apply {
                putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName)
                tag?.let { putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, it) }
                putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, remove)
            }
        }
    }

    companion object {

        private const val TAG = "ShizukuApplication"
        private const val TRANSACTION_ATTACH_APPLICATION_V13 = 18

        @Volatile
        private var binder: IBinder? = null

        @Volatile
        private var service: IShizukuService? = null

        @Volatile
        private var serverUid: Int = -1

        @Volatile
        private var serverApiVersion: Int = -1

        @Volatile
        private var serverPatchVersion: Int = -1

        @Volatile
        private var serverContext: String? = null

        @Volatile
        private var permissionGranted: Boolean = false

        @Volatile
        private var shouldShowRequestPermissionRationale: Boolean = false

        @Volatile
        private var binderReady: Boolean = false

        private val RECEIVED_LISTENERS = ArrayList<ListenerHolder<OnBinderReceivedListener>>()
        private val DEAD_LISTENERS = ArrayList<ListenerHolder<OnBinderDeadListener>>()
        private val PERMISSION_LISTENERS = ArrayList<ListenerHolder<OnRequestPermissionResultListener>>()
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        private val SHIZUKU_APPLICATION: IShizukuApplication = object : IShizukuApplication.Stub() {
            override fun bindApplication(data: Bundle?) {
                if (data == null) return
                serverUid = data.getInt(BIND_APPLICATION_SERVER_UID, -1)
                serverApiVersion = data.getInt(BIND_APPLICATION_SERVER_VERSION, -1)
                serverPatchVersion = data.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, -1)
                serverContext = data.getString(BIND_APPLICATION_SERVER_SECONTEXT)
                permissionGranted = data.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false)
                shouldShowRequestPermissionRationale = data.getBoolean(
                    BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE,
                    false
                )
                scheduleBinderReceivedListeners()
            }

            override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
                val allowed = data?.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false) ?: false
                permissionGranted = allowed
                scheduleRequestPermissionResultListener(
                    requestCode,
                    if (allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                )
            }
        }

        private val DEATH_RECIPIENT = IBinder.DeathRecipient {
            binderReady = false
            onBinderReceived(null, null)
        }

        @Throws(RemoteException::class)
        private fun attachApplicationV13(target: IBinder, packageName: String?): Boolean {
            val args = Bundle().apply {
                putInt(ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION)
                putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName)
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder())
                data.writeInt(1)
                args.writeToParcel(data, 0)
                val result = target.transact(TRANSACTION_ATTACH_APPLICATION_V13, data, reply, 0)
                reply.readException()
                result
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        @JvmStatic
        @Synchronized
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
            if (binder === newBinder) return

            if (newBinder == null) {
                binder?.let { old ->
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT, 0)
                    } catch (_: Throwable) {
                    }
                }
                binder = null
                service = null
                serverUid = -1
                serverApiVersion = -1
                serverPatchVersion = -1
                serverContext = null
                permissionGranted = false
                shouldShowRequestPermissionRationale = false
                binderReady = false

                scheduleBinderDeadListeners()
            } else {
                binder?.let { old ->
                    try {
                        old.unlinkToDeath(DEATH_RECIPIENT, 0)
                    } catch (_: Throwable) {
                    }
                }
                binder = newBinder
                service = IShizukuService.Stub.asInterface(newBinder)

                try {
                    newBinder.linkToDeath(DEATH_RECIPIENT, 0)
                } catch (_: Throwable) {
                    Log.i(TAG, "attachApplication linkToDeath failed")
                }

                try {
                    attachApplicationV13(newBinder, packageName)
                    Log.i(TAG, "attachApplication")
                } catch (e: Throwable) {
                    Log.w(TAG, Log.getStackTraceString(e))
                }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun addBinderReceivedListener(listener: OnBinderReceivedListener, handler: Handler? = null) {
            addBinderReceivedListener(Objects.requireNonNull(listener), false, handler)
        }

        @JvmStatic
        @JvmOverloads
        fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener, handler: Handler? = null) {
            addBinderReceivedListener(Objects.requireNonNull(listener), true, handler)
        }

        private fun addBinderReceivedListener(
            listener: OnBinderReceivedListener,
            sticky: Boolean,
            handler: Handler?
        ) {
            val shouldNotifyNow: Boolean
            synchronized(RECEIVED_LISTENERS) {
                RECEIVED_LISTENERS.add(ListenerHolder(listener, handler))
                shouldNotifyNow = sticky && binderReady && binder?.pingBinder() == true
            }
            if (shouldNotifyNow) {
                if (handler != null) {
                    handler.post { listener.onBinderReceived() }
                } else if (Looper.myLooper() == Looper.getMainLooper()) {
                    listener.onBinderReceived()
                } else {
                    MAIN_HANDLER.post { listener.onBinderReceived() }
                }
            }
        }

        @JvmStatic
        fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean {
            synchronized(RECEIVED_LISTENERS) {
                return RECEIVED_LISTENERS.removeIf { holder -> holder.listener == listener }
            }
        }

        private fun scheduleBinderReceivedListeners() {
            val snapshot: List<ListenerHolder<OnBinderReceivedListener>>
            synchronized(RECEIVED_LISTENERS) {
                binderReady = true
                snapshot = ArrayList(RECEIVED_LISTENERS)
            }
            for (holder in snapshot) {
                if (holder.handler != null) {
                    holder.handler.post { holder.listener.onBinderReceived() }
                } else if (Looper.myLooper() == Looper.getMainLooper()) {
                    holder.listener.onBinderReceived()
                } else {
                    MAIN_HANDLER.post { holder.listener.onBinderReceived() }
                }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun addBinderDeadListener(listener: OnBinderDeadListener, handler: Handler? = null) {
            synchronized(RECEIVED_LISTENERS) {
                DEAD_LISTENERS.add(ListenerHolder(listener, handler))
            }
        }

        @JvmStatic
        fun removeBinderDeadListener(listener: OnBinderDeadListener): Boolean {
            synchronized(RECEIVED_LISTENERS) {
                return DEAD_LISTENERS.removeIf { holder -> holder.listener == listener }
            }
        }

        private fun scheduleBinderDeadListeners() {
            val snapshot = synchronized(RECEIVED_LISTENERS) { ArrayList(DEAD_LISTENERS) }
            for (holder in snapshot) {
                if (holder.handler != null) {
                    holder.handler.post { holder.listener.onBinderDead() }
                } else if (Looper.myLooper() == Looper.getMainLooper()) {
                    holder.listener.onBinderDead()
                } else {
                    MAIN_HANDLER.post { holder.listener.onBinderDead() }
                }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun addRequestPermissionResultListener(
            listener: OnRequestPermissionResultListener,
            handler: Handler? = null
        ) {
            synchronized(RECEIVED_LISTENERS) {
                PERMISSION_LISTENERS.add(ListenerHolder(listener, handler))
            }
        }

        @JvmStatic
        fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener): Boolean {
            synchronized(RECEIVED_LISTENERS) {
                return PERMISSION_LISTENERS.removeIf { holder -> holder.listener == listener }
            }
        }

        private fun scheduleRequestPermissionResultListener(requestCode: Int, result: Int) {
            val snapshot = synchronized(RECEIVED_LISTENERS) { ArrayList(PERMISSION_LISTENERS) }
            for (holder in snapshot) {
                if (holder.handler != null) {
                    holder.handler.post { holder.listener.onRequestPermissionResult(requestCode, result) }
                } else if (Looper.myLooper() == Looper.getMainLooper()) {
                    holder.listener.onRequestPermissionResult(requestCode, result)
                } else {
                    MAIN_HANDLER.post { holder.listener.onRequestPermissionResult(requestCode, result) }
                }
            }
        }

        @JvmStatic
        internal fun requireService(): IShizukuService {
            return service ?: throw IllegalStateException("binder haven't been received")
        }

        @JvmStatic
        fun getBinder(): IBinder? = binder ?: NightClient.getBinder()

        @JvmStatic
        fun pingBinder(): Boolean = binder?.pingBinder() == true

        private fun rethrowAsRuntimeException(e: RemoteException): RuntimeException = RuntimeException(e)

        @JvmStatic
        fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
            try {
                requireService().asBinder().transact(
                    ShizukuApiConstants.BINDER_TRANSACTION_transact,
                    data,
                    reply,
                    flags
                )
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): ShizukuRemoteProcess {
            if (!pingBinder() && NightClient.pingBinder()) {
                return NightClient.newProcess(cmd, env, dir)
            }
            return try {
                ShizukuRemoteProcess(requireService().newProcess(cmd, env, dir))
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        fun getUid(): Int {
            if (serverUid != -1) return serverUid
            if (!pingBinder() && NightClient.pingBinder()) return NightClient.getUid()
            return try {
                serverUid = requireService().uid
                serverUid
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                -1
            }
        }

        @JvmStatic
        fun getVersion(): Int {
            if (serverApiVersion != -1) return serverApiVersion
            if (!pingBinder() && NightClient.pingBinder()) return NightClient.getVersion()
            return try {
                serverApiVersion = requireService().version
                serverApiVersion
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                -1
            }
        }

        @JvmStatic
        fun isPreV11(): Boolean = false

        @JvmStatic
        fun getLatestServiceVersion(): Int = ShizukuApiConstants.SERVER_VERSION

        @JvmStatic
        fun getSELinuxContext(): String? {
            if (serverContext != null) return serverContext
            if (!pingBinder() && NightClient.pingBinder()) return NightClient.getSELinuxContext()
            return try {
                serverContext = requireService().seLinuxContext
                serverContext
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                null
            }
        }

        private fun cleanupFailedConnection(connection: ShizukuServiceConnection, conn: ServiceConnection) {
            connection.removeConnection(conn)
            if (!connection.hasConnections()) {
                ShizukuServiceConnections.remove(connection)
            }
        }

        @JvmStatic
        fun bindUserService(args: UserServiceArgs, conn: ServiceConnection) {
            if (!pingBinder() && NightClient.pingBinder()) {
                NightClient.bindUserService(args, conn)
                return
            }
            val connection = ShizukuServiceConnections.get(args)
            connection.addConnection(conn)
            try {
                requireService().addUserService(connection, args.forAdd())
            } catch (t: Throwable) {
                cleanupFailedConnection(connection, conn)
                if (t is RemoteException) throw rethrowAsRuntimeException(t)
                throw t
            }
        }

        @JvmStatic
        fun peekUserService(args: UserServiceArgs, conn: ServiceConnection): Int {
            if (!pingBinder() && NightClient.pingBinder()) {
                return NightClient.peekUserService(args, conn)
            }
            val connection = ShizukuServiceConnections.get(args)
            connection.addConnection(conn)
            val result = try {
                val bundle = args.forAdd().apply {
                    putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true)
                }
                requireService().addUserService(connection, bundle)
            } catch (t: Throwable) {
                cleanupFailedConnection(connection, conn)
                if (t is RemoteException) throw rethrowAsRuntimeException(t)
                throw t
            }

            val normalized = if (getVersion() >= 13) {
                result
            } else {
                if (result == 0) 0 else -1
            }
            if (normalized < 0) {
                cleanupFailedConnection(connection, conn)
            }
            return normalized
        }

        @JvmStatic
        fun unbindUserService(args: UserServiceArgs, conn: ServiceConnection?, remove: Boolean) {
            if (!pingBinder() && NightClient.pingBinder()) {
                NightClient.unbindUserService(args, conn, remove)
                return
            }
            if (remove) {
                try {
                    requireService().removeUserService(null, args.forRemove(true))
                } catch (e: RemoteException) {
                    throw rethrowAsRuntimeException(e)
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
                if (getVersion() >= 14 || (getVersion() == 13 && getServerPatchVersion() >= 4)) {
                    try {
                        requireService().removeUserService(connection, args.forRemove(false))
                    } catch (e: RemoteException) {
                        throw rethrowAsRuntimeException(e)
                    }
                }
            }
        }

        @JvmStatic
        fun checkRemotePermission(permission: String): Int {
            if (getUid() == 0) return PackageManager.PERMISSION_GRANTED
            return try {
                requireService().checkPermission(permission)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        fun requestPermission(requestCode: Int) {
            if (!pingBinder() && NightClient.pingBinder()) {
                NightClient.requestPermission(requestCode)
                return
            }
            try {
                requireService().requestPermission(requestCode)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        fun checkSelfPermission(): Int {
            if (permissionGranted) return PackageManager.PERMISSION_GRANTED
            if (!pingBinder() && NightClient.pingBinder()) {
                return NightClient.checkSelfPermission()
            }
            try {
                permissionGranted = requireService().checkSelfPermission()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
            return if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }

        @JvmStatic
        fun shouldShowRequestPermissionRationale(): Boolean {
            if (permissionGranted) return false
            if (shouldShowRequestPermissionRationale) return true
            if (!pingBinder() && NightClient.pingBinder()) {
                return NightClient.shouldShowRequestPermissionRationale()
            }
            try {
                shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
            return shouldShowRequestPermissionRationale
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun exit() {
            try {
                requireService().exit()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun attachUserService(binder: IBinder, options: Bundle) {
            try {
                requireService().attachUserService(binder, options)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun dispatchPermissionConfirmationResult(
            requestUid: Int,
            requestPid: Int,
            requestCode: Int,
            data: Bundle
        ) {
            try {
                requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun getFlagsForUid(uid: Int, mask: Int): Int {
            return try {
                requireService().getFlagsForUid(uid, mask)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
            try {
                requireService().updateFlagsForUid(uid, mask, value)
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

        @JvmStatic
        @RestrictTo(LIBRARY_GROUP_PREFIX)
        fun getServerPatchVersion(): Int = serverPatchVersion
    }
}
