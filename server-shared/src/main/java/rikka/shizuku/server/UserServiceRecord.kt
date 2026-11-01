package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteCallbackList
import java.util.UUID
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.Logger

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

    @JvmField
    var service: IBinder? = null

    @JvmField
    val callbacks: RemoteCallbackList<IShizukuServiceConnection> = ConnectionList()

    @JvmField
    var starting: Boolean = false

    fun setStartingTimeout(timeoutMillis: Long) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token)
            return
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis)
        starting = true
        val timeoutRunnable = Runnable {
            if (starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis)
                removeSelf()
            }
        }
        startTimeoutCallback = timeoutRunnable
        HandlerUtil.getMainHandler().postDelayed(timeoutRunnable, timeoutMillis)
    }

    @JvmName("updateDaemon")
    fun setDaemon(daemon: Boolean) {
        this.daemon = daemon
    }

    fun setBinder(binder: IBinder) {
        LOGGER.v("Binder received for service record %s", token)

        startTimeoutCallback?.let { HandlerUtil.getMainHandler().removeCallbacks(it) }
        starting = false
        service = binder

        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
            LOGGER.w("linkToDeath %s", token)
        }

        broadcastBinderReceived()
    }

    fun broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", token)

        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).connected(service)
            } catch (_: Throwable) {
                LOGGER.w("Failed to call connected %s", token)
            }
        }
        callbacks.finishBroadcast()
    }

    fun broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", token)

        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).died()
            } catch (_: Throwable) {
                LOGGER.w("Failed to call died %s", token)
            }
        }
        callbacks.finishBroadcast()
    }

    abstract fun removeSelf()

    fun destroy() {
        service?.let {
            runCatching { it.unlinkToDeath(deathRecipient, 0) }
        }

        val activeService = service
        if (activeService != null && activeService.pingBinder()) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(activeService.interfaceDescriptor!!)
                activeService.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY)
            } catch (_: Throwable) {
                LOGGER.w("Failed to call destroy %s", token)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        callbacks.kill()
    }

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("UserServiceRecord")
    }
}
