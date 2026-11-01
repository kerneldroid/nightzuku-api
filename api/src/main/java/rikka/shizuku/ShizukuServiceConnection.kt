package rikka.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.util.concurrent.CopyOnWriteArraySet
import moe.shizuku.server.IShizukuServiceConnection

internal class ShizukuServiceConnection(
    args: Shizuku.UserServiceArgs
) : IShizukuServiceConnection.Stub() {

    private val connections = CopyOnWriteArraySet<ServiceConnection>()
    private val componentName: ComponentName = args.componentName
    private val deathRecipient = IBinder.DeathRecipient { died() }
    private var binder: IBinder? = null

    @Volatile
    private var dead: Boolean = false

    fun addConnection(conn: ServiceConnection?) {
        if (conn != null) {
            connections.add(conn)
        }
    }

    fun removeConnection(conn: ServiceConnection?) {
        if (conn != null) {
            connections.remove(conn)
        }
    }

    fun clearConnections() {
        connections.clear()
    }

    override fun connected(service: IBinder?) {
        MAIN_HANDLER.post {
            for (conn in connections) {
                conn.onServiceConnected(componentName, service)
            }
        }
        this.binder = service
        try {
            service?.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
        }
    }

    override fun died() {
        val currentBinder = binder
        binder = null
        if (currentBinder != null) {
            try {
                currentBinder.unlinkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
        }

        if (dead) return
        dead = true

        MAIN_HANDLER.post {
            for (conn in connections) {
                conn.onServiceDisconnected(componentName)
            }
            connections.clear()
            ShizukuServiceConnections.remove(this)
        }
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
