/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (PorterServiceConnection) under Apache-2.0 / MIT:
 * - Immediate cache eviction on death before posting disconnect callbacks to main thread
 * - Unlinking previous service binder before linking replacement binder
 */

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

    @Volatile
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

    fun hasConnections(): Boolean = connections.isNotEmpty()

    fun clearConnections() {
        connections.clear()
    }

    override fun connected(service: IBinder?) {
        synchronized(this) {
            if (dead) return
            val previous = binder
            if (previous != null && previous !== service) {
                try {
                    previous.unlinkToDeath(deathRecipient, 0)
                } catch (_: Throwable) {
                }
            }
            this.binder = service
            if (service != null && previous !== service) {
                try {
                    service.linkToDeath(deathRecipient, 0)
                } catch (_: RemoteException) {
                    died()
                    return
                }
            }
        }

        MAIN_HANDLER.post {
            for (conn in connections) {
                conn.onServiceConnected(componentName, service)
            }
        }
    }

    override fun died() {
        val snapshot: List<ServiceConnection>
        synchronized(this) {
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

            // Evict from cache immediately under lock so a subsequent bindUserService call
            // never receives this dead instance while the main thread runnable is still queued.
            ShizukuServiceConnections.remove(this)
            snapshot = connections.toList()
            connections.clear()
        }

        MAIN_HANDLER.post {
            for (conn in snapshot) {
                conn.onServiceDisconnected(componentName)
            }
        }
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
