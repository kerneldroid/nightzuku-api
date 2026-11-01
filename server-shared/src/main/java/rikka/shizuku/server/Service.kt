package rikka.shizuku.server

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SELinux
import android.os.SystemProperties
import android.system.Os
import androidx.annotation.CallSuper
import java.io.File
import java.io.IOException
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.PermissionManagerApis
import rikka.rish.RishConfig
import rikka.rish.RishService
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.api.RemoteProcessHolder
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils
import rikka.shizuku.server.util.UserHandleCompat

abstract class Service<
    UserServiceMgr : UserServiceManager,
    ClientMgr : ClientManager<ConfigMgr>,
    ConfigMgr : ConfigManager
> : IShizukuService.Stub() {

    val userServiceManager: UserServiceMgr
    open val configManager: ConfigMgr
    val clientManager: ClientMgr
    private val rishService: RishService

    init {
        RishConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)

        userServiceManager = this.onCreateUserServiceManager()
        configManager = this.onCreateConfigManager()
        clientManager = this.onCreateClientManager()
        rishService = object : RishService() {
            override fun enforceCallingPermission(func: String) {
                this@Service.enforceCallingPermission(func)
            }
        }
    }

    abstract fun onCreateUserServiceManager(): UserServiceMgr

    abstract fun onCreateClientManager(): ClientMgr

    abstract fun onCreateConfigManager(): ConfigMgr

    open fun checkCallerManagerPermission(func: String, callingUid: Int, callingPid: Int): Boolean = false

    fun enforceManagerPermission(func: String) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (callingPid == Os.getpid()) {
            return
        }

        if (checkCallerManagerPermission(func, callingUid, callingPid)) {
            return
        }

        val msg = "Permission Denial: $func from pid=${Binder.getCallingPid()} is not manager"
        LOGGER.w(msg)
        throw SecurityException(msg)
    }

    open fun checkCallerPermission(
        func: String,
        callingUid: Int,
        callingPid: Int,
        clientRecord: ClientRecord?
    ): Boolean = false

    fun enforceCallingPermission(func: String) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (callingUid == OsUtils.getUid()) {
            return
        }

        val clientRecord = clientManager.findClient(callingUid, callingPid)

        if (checkCallerPermission(func, callingUid, callingPid, clientRecord)) {
            return
        }

        if (clientRecord == null) {
            val msg = "Permission Denial: $func from pid=${Binder.getCallingPid()} is not an attached client"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        if (!clientRecord.allowed) {
            val msg = "Permission Denial: $func from pid=${Binder.getCallingPid()} requires permission"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }
    }

    @Throws(RemoteException::class)
    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        enforceCallingPermission("transactRemote")

        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val clientRecord = clientManager.findClient(callingUid, callingPid)

        val targetFlags = if (clientRecord != null && clientRecord.apiVersion >= 13) {
            data.readInt()
        } else {
            flags
        }

        LOGGER.d(
            "transact: uid=%d, descriptor=%s, code=%d",
            Binder.getCallingUid(),
            targetBinder.interfaceDescriptor,
            targetCode
        )
        val newData = Parcel.obtain()
        try {
            try {
                newData.appendFrom(data, data.dataPosition(), data.dataAvail())
            } catch (tr: Throwable) {
                LOGGER.w(tr, "appendFrom")
                return
            }
            val id = Binder.clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, newData, reply, targetFlags)
            } finally {
                Binder.restoreCallingIdentity(id)
            }
        } finally {
            newData.recycle()
        }
    }

    override fun getVersion(): Int {
        enforceCallingPermission("getVersion")
        return ShizukuApiConstants.SERVER_VERSION
    }

    override fun getUid(): Int {
        enforceCallingPermission("getUid")
        return Os.getuid()
    }

    @Throws(RemoteException::class)
    override fun checkPermission(permission: String): Int {
        enforceCallingPermission("checkPermission")
        return PermissionManagerApis.checkPermission(permission, Os.getuid())
    }

    override fun getSELinuxContext(): String {
        enforceCallingPermission("getSELinuxContext")
        return try {
            SELinux.getContext()
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun getSystemProperty(name: String, defaultValue: String?): String {
        enforceCallingPermission("getSystemProperty")
        return try {
            SystemProperties.get(name, defaultValue)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun setSystemProperty(name: String, value: String) {
        enforceCallingPermission("setSystemProperty")
        try {
            SystemProperties.set(name, value)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        enforceCallingPermission("removeUserService")
        return userServiceManager.removeUserService(conn, options)
    }

    override fun addUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        enforceCallingPermission("addUserService")

        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val clientRecord = clientManager.findClient(callingUid, callingPid)
        val callingApiVersion = clientRecord?.apiVersion ?: ShizukuApiConstants.SERVER_VERSION
        return userServiceManager.addUserService(conn, options, callingApiVersion)
    }

    override fun attachUserService(binder: IBinder, options: Bundle) {
        userServiceManager.attachUserService(binder, options)
    }

    override fun checkSelfPermission(): Boolean {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true
        }

        return clientManager.requireClient(callingUid, callingPid).allowed
    }

    override fun requestPermission(requestCode: Int) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val userId = UserHandleCompat.getUserId(callingUid)

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return
        }

        val clientRecord = clientManager.requireClient(callingUid, callingPid)

        if (clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true)
            return
        }

        val entry = configManager.find(callingUid)
        if (entry != null && entry.isDenied()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        showPermissionConfirmation(requestCode, clientRecord, callingUid, callingPid, userId)
    }

    abstract fun showPermissionConfirmation(
        requestCode: Int,
        clientRecord: ClientRecord,
        callingUid: Int,
        callingPid: Int,
        userId: Int
    )

    override fun shouldShowRequestPermissionRationale(): Boolean {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true
        }

        clientManager.requireClient(callingUid, callingPid)

        val entry = configManager.find(callingUid)
        return entry != null && entry.isDenied()
    }

    override fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess {
        enforceCallingPermission("newProcess")

        LOGGER.d(
            "newProcess: uid=%d, cmd=%s, env=%s, dir=%s",
            Binder.getCallingUid(),
            cmd.contentToString(),
            env?.contentToString(),
            dir
        )

        val process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }

        val clientRecord = clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid())
        val token = clientRecord?.client?.asBinder()

        return RemoteProcessHolder(process, token)
    }

    @CallSuper
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            transactRemote(data, reply, flags)
            return true
        } else if (rishService.onTransact(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        @JvmField
        val LOGGER: Logger = Logger("Service")
    }
}
