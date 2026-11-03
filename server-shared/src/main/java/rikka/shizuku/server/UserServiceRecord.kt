/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (UserServiceRecord) under Apache-2.0 / MIT:
 * - Pre-cached interfaceDescriptor outside monitor to avoid blocking on wedged UserService binders during destroy()
 * - broadcastBinderDied() notification when a record is destroyed before its binder ever attached
 * - HostProcess tracking and pendingDestroy/removed state flags
 */

package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteCallbackList
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.HostProcess
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.PackageSignatureVerifier

abstract class UserServiceRecord(
    @JvmField val versionCode: Int,
    @JvmField var daemon: Boolean
) {

    private var startTimeoutCallback: Runnable? = null

    private inner class ConnectionList : RemoteCallbackList<IShizukuServiceConnection>() {
        override fun onCallbackDied(callback: IShizukuServiceConnection) {
            if (daemon || registeredCallbackCount != 0) {
                return
            }
            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", token)
            removeSelf()
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        LOGGER.v("Binder for service record %s is dead", token)
        removeSelf()
    }

    @JvmField
    var token: String = "${UUID.randomUUID()}-${System.currentTimeMillis()}"

    val logId: String = "#${NEXT_LOG_ID.incrementAndGet()}"

    @JvmField
    @Volatile
    var service: IBinder? = null

    @JvmField
    val callbacks: RemoteCallbackList<IShizukuServiceConnection> = ConnectionList()

    @JvmField
    @Volatile
    var starting: Boolean = false

    @JvmField
    @Volatile
    var pendingDestroy: Boolean = false

    @Volatile
    var isRemoved: Boolean = false
        private set

    @Volatile
    var host: HostProcess? = null
        internal set

    @JvmField
    var ownerUid: Int = -1

    /**
     * Signing identity of the package installation this record was created for. Used by the
     * manager to refuse reusing a record after the APK was replaced with a different signer.
     */
    @Volatile
    internal var signingBaseline: PackageSignatureVerifier.Baseline? = null

    @Volatile
    private var cachedInterfaceDescriptor: String? = null

    fun setStartingTimeout(timeoutMillis: Long) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token)
            return
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis)
        starting = true
        val timeoutRunnable = Runnable {
            if (!isRemoved && starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis)
                removeSelf()
            }
        }
        startTimeoutCallback = timeoutRunnable
        HandlerUtil.getMainHandler().postDelayed(timeoutRunnable, timeoutMillis)
    }

    fun markRemoved() {
        isRemoved = true
        pendingDestroy = true
        starting = false
        startTimeoutCallback?.let {
            runCatching { HandlerUtil.getMainHandler().removeCallbacks(it) }
        }
        startTimeoutCallback = null
    }

    @JvmName("updateDaemon")
    fun setDaemon(daemon: Boolean) {
        this.daemon = daemon
    }

    @JvmOverloads
    fun setBinder(binder: IBinder, interfaceDescriptor: String? = null) {
        LOGGER.v("Binder received for service record %s", token)

        startTimeoutCallback?.let {
            runCatching { HandlerUtil.getMainHandler().removeCallbacks(it) }
        }
        startTimeoutCallback = null
        starting = false
        service = binder
        if (interfaceDescriptor != null) {
            cachedInterfaceDescriptor = interfaceDescriptor
        }

        if (pendingDestroy || isRemoved) {
            LOGGER.v("Service record %s was marked for removal while starting; destroying binder immediately", token)
            destroy()
            return
        }

        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
            LOGGER.w("linkToDeath %s", token)
        }

        broadcastBinderReceived()
    }

    fun broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", token)

        val currentService = service
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).connected(currentService)
                } catch (_: Throwable) {
                    LOGGER.w("Failed to call connected %s", token)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    fun broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", token)

        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).died()
                } catch (_: Throwable) {
                    LOGGER.w("Failed to call died %s", token)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    abstract fun removeSelf()

    fun destroy() {
        try {
            val activeService = service
            if (activeService != null) {
                runCatching { activeService.unlinkToDeath(deathRecipient, 0) }
            }

            if (activeService != null && activeService.pingBinder()) {
                val descriptor = cachedInterfaceDescriptor
                    ?: runCatching { activeService.interfaceDescriptor }.getOrNull()
                if (!descriptor.isNullOrEmpty()) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        activeService.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY)
                    } catch (_: Throwable) {
                        LOGGER.w("Failed to call destroy %s", token)
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                } else {
                    LOGGER.w("No interface descriptor for service record %s, skipping destroy transaction", token)
                }
            } else if (activeService == null) {
                broadcastBinderDied()
            }
        } finally {
            callbacks.kill()
        }
    }

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("UserServiceRecord")

        private val NEXT_LOG_ID = AtomicInteger()
    }
}
