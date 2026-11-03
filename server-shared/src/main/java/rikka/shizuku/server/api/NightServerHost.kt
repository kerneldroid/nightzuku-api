package rikka.shizuku.server.api

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerPolicy
import rikka.shizuku.server.UserServiceManager

/**
 * Everything [NightServiceEndpoint] needs from the embedding server.
 *
 * The generic [rikka.shizuku.server.Service] deliberately does not implement this interface: the
 * methods below are the ones an embedding server already owns under its own names, and requiring
 * an interface would force `override` modifiers onto code that predates this module.
 */
interface NightServerHost {

    val configManager: ConfigManager

    val clientManager: ClientManager<out ConfigManager>

    val userServiceManager: UserServiceManager

    /** Deployment identity policy, or `null` when the embedding server does not configure one. */
    val serverPolicy: ServerPolicy?

    /** The legacy `IShizukuService` binder; used to reject self-referential `transactRemote`. */
    val serverBinder: IBinder

    /**
     * Registers (or refreshes) the client behind [bridgeApp] for the Night wire.
     *
     * Returns the resulting record, or `null` when the client could not be registered.
     */
    fun attachNightClient(
        callingUid: Int,
        callingPid: Int,
        bridgeApp: IShizukuApplication,
        packageName: String,
        clientProtocolVersion: Int
    ): ClientRecord?

    /** Called when a Night-wire callback binder dies. */
    fun onClientProcessDied(uid: Int, pid: Int)

    /** Routes a Night-wire permission request into the legacy prompt flow. */
    fun requestPermissionFromNightWire(uid: Int, pid: Int, requestCode: Int)

    /** Pushes the current capability state for [uid] to its attached clients. */
    fun notifyCapabilitiesUpdated(uid: Int)

    /** Pauses or resumes all client access. */
    fun setAccessPaused(paused: Boolean)

    /** Whether the optional watchdog is enabled; `default` is returned when unsupported. */
    fun getNightDogEnabled(default: Boolean = false): Boolean

    fun checkCallerManagerPermission(func: String, callingUid: Int, callingPid: Int): Boolean

    fun enforceCallingPermission(func: String)

    fun enforceManagerPermission(func: String)

    fun addUserService(conn: IShizukuServiceConnection?, options: Bundle): Int

    fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int

    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess

    /** Dispatches rish transaction codes handled by the embedding server's rish service. */
    fun onTransactRish(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean
}
