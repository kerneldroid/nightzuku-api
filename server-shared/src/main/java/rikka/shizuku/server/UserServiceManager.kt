package rikka.shizuku.server

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.util.ArrayMap
import java.util.Collections
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DAEMON
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_REMOVE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TAG
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE
import rikka.shizuku.server.util.AbiUtil
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

abstract class UserServiceManager {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val userServiceRecords: MutableMap<String, UserServiceRecord> =
        Collections.synchronizedMap(ArrayMap())
    private val packageUserServiceRecords: MutableMap<String, MutableList<UserServiceRecord>> =
        Collections.synchronizedMap(ArrayMap())

    fun ensureCallingPackageForUserService(packageName: String, appId: Int, userId: Int): PackageInfo {
        @SuppressLint("UnsafeOptInUsageError")
        val packageInfo = PackageManagerApis.getPackageInfoNoThrow(
            packageName,
            0x00002000 /* PackageManager.MATCH_UNINSTALLED_PACKAGES */,
            userId
        )
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            throw SecurityException("unable to find package $packageName")
        }
        if (UserHandleCompat.getAppId(packageInfo.applicationInfo!!.uid) != appId) {
            throw SecurityException("package $packageName is not owned by $appId")
        }
        return packageInfo
    }

    fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        @Suppress("DEPRECATION")
        val componentName = Objects.requireNonNull(
            options.getParcelable<ComponentName>(USER_SERVICE_ARG_COMPONENT),
            "component is null"
        )!!

        val uid = Binder.getCallingUid()
        val appId = UserHandleCompat.getAppId(uid)
        val userId = UserHandleCompat.getUserId(uid)

        val packageName = componentName.packageName
        ensureCallingPackageForUserService(packageName, appId, userId)

        val className = Objects.requireNonNull(componentName.className, "class is null")
        val tag = options.getString(USER_SERVICE_ARG_TAG)
        val key = "$packageName:${tag ?: className}"

        val remove = if (options.containsKey(USER_SERVICE_ARG_REMOVE)) {
            options.getBoolean(USER_SERVICE_ARG_REMOVE)
        } else {
            true
        }

        synchronized(this) {
            val record = getUserServiceRecordLocked(key) ?: return 1
            if (remove) {
                removeUserServiceLocked(record)
            } else {
                record.callbacks.unregister(conn)
            }
        }
        return 0
    }

    private fun removeUserServiceLocked(record: UserServiceRecord) {
        if (userServiceRecords.values.remove(record)) {
            synchronized(packageUserServiceRecords) {
                val it = packageUserServiceRecords.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    val list = entry.value
                    list.remove(record)
                    if (list.isEmpty()) {
                        it.remove()
                    }
                }
            }
            record.destroy()
            onUserServiceRecordRemoved(record)
        }
    }

    fun addUserService(
        conn: IShizukuServiceConnection?,
        options: Bundle?,
        callingApiVersion: Int
    ): Int {
        Objects.requireNonNull(conn, "connection is null")
        Objects.requireNonNull(options, "options is null")
        val validConn = conn!!
        val validOptions = options!!

        val uid = Binder.getCallingUid()
        val appId = UserHandleCompat.getAppId(uid)
        val userId = UserHandleCompat.getUserId(uid)

        @Suppress("DEPRECATION")
        val componentName = Objects.requireNonNull(
            validOptions.getParcelable<ComponentName>(USER_SERVICE_ARG_COMPONENT),
            "component is null"
        )!!
        val packageName = Objects.requireNonNull(componentName.packageName, "package is null")
        val packageInfo = ensureCallingPackageForUserService(packageName, appId, userId)

        val className = Objects.requireNonNull(componentName.className, "class is null")
        Objects.requireNonNull(packageInfo.applicationInfo!!.sourceDir, "apk path is null")

        val versionCode = validOptions.getInt(USER_SERVICE_ARG_VERSION_CODE, 1)
        val tag = validOptions.getString(USER_SERVICE_ARG_TAG)
        val processNameSuffix = validOptions.getString(USER_SERVICE_ARG_PROCESS_NAME) ?: "user_service"
        val debug = validOptions.getBoolean(USER_SERVICE_ARG_DEBUGGABLE, false)
        val noCreate = validOptions.getBoolean(USER_SERVICE_ARG_NO_CREATE, false)
        val daemon = validOptions.getBoolean(USER_SERVICE_ARG_DAEMON, true)
        val use32Bits = validOptions.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false)
        val key = "$packageName:${tag ?: className}"

        synchronized(this) {
            val record = getUserServiceRecordLocked(key)
            if (noCreate) {
                if (record != null) {
                    record.callbacks.register(validConn)
                    val activeBinder = record.service
                    if (activeBinder != null && activeBinder.pingBinder()) {
                        record.broadcastBinderReceived()
                        return if (callingApiVersion >= 13) record.versionCode else 0
                    }
                }
                return if (callingApiVersion >= 13) -1 else 1
            } else {
                val newRecord = createUserServiceRecordIfNeededLocked(record, key, versionCode, daemon, packageInfo)
                newRecord.callbacks.register(validConn)

                val activeBinder = newRecord.service
                if (activeBinder != null && activeBinder.pingBinder()) {
                    newRecord.broadcastBinderReceived()
                } else if (!newRecord.starting) {
                    newRecord.setStartingTimeout(DateUtils.SECOND_IN_MILLIS * 30)
                    executor.execute {
                        startUserService(
                            newRecord,
                            key,
                            newRecord.token,
                            packageName,
                            className,
                            processNameSuffix,
                            uid,
                            use32Bits,
                            debug
                        )
                    }
                    return 0
                }
                return 0
            }
        }
    }

    private fun getUserServiceRecordLocked(key: String): UserServiceRecord? = userServiceRecords[key]

    private fun createUserServiceRecordIfNeededLocked(
        record: UserServiceRecord?,
        key: String,
        versionCode: Int,
        daemon: Boolean,
        packageInfo: PackageInfo
    ): UserServiceRecord {
        if (record != null) {
            val activeBinder = record.service
            if (record.versionCode != versionCode) {
                LOGGER.v(
                    "Remove service record %s (%s) because version code not matched (old=%d, new=%d)",
                    key,
                    record.token,
                    record.versionCode,
                    versionCode
                )
            } else if (!record.starting && (activeBinder == null || !activeBinder.pingBinder())) {
                LOGGER.v("Service in record %s (%s) is dead", key, record.token)
            } else {
                LOGGER.i("Found existing service record %s (%s)", key, record.token)
                if (record.daemon != daemon) {
                    record.daemon = daemon
                }
                return record
            }

            removeUserServiceLocked(record)
        }

        val created = object : UserServiceRecord(versionCode, daemon) {
            override fun removeSelf() {
                synchronized(this@UserServiceManager) {
                    removeUserServiceLocked(this)
                }
            }
        }

        val packageName = packageInfo.packageName
        val list = packageUserServiceRecords.getOrPut(packageName) {
            Collections.synchronizedList(ArrayList())
        }
        list.add(created)

        onUserServiceRecordCreated(created, packageInfo)

        userServiceRecords[key] = created
        LOGGER.i(
            "New service record %s (%s): version=%d, daemon=%s, apk=%s",
            key,
            created.token,
            versionCode,
            daemon.toString(),
            packageInfo.applicationInfo?.sourceDir
        )
        return created
    }

    private fun startUserService(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean
    ) {
        LOGGER.v("Starting process for service record %s (%s)...", key, token)

        val cmd = getUserServiceStartCmd(
            record,
            key,
            token,
            packageName,
            classname,
            processNameSuffix,
            callingUid,
            use32Bits && AbiUtil.has32Bit(),
            debug
        )
        var process: Process? = null
        val exitCode = try {
            process = Runtime.getRuntime().exec("sh")
            process.outputStream.use { os ->
                os.write(cmd.toByteArray())
                os.flush()
            }
            process.waitFor()
        } catch (e: Throwable) {
            process?.destroy()
            throw IllegalStateException(e.message, e)
        }
        if (exitCode != 0) {
            throw IllegalStateException("sh exited with $exitCode")
        }
    }

    abstract fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean
    ): String

    private fun sendUserServiceLocked(binder: IBinder, token: String) {
        val record = userServiceRecords.values.firstOrNull { it.token == token }
            ?: throw IllegalArgumentException("unable to find token $token")

        LOGGER.v("Received binder for service record %s", token)
        record.setBinder(binder)
    }

    fun attachUserService(binder: IBinder?, options: Bundle?) {
        val validBinder = Objects.requireNonNull(binder, "binder is null")!!
        val validOptions = Objects.requireNonNull(options, "options is null")!!
        val token = Objects.requireNonNull(
            validOptions.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN),
            "token is null"
        )!!

        synchronized(this) {
            sendUserServiceLocked(validBinder, token)
        }
    }

    open fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
    }

    open fun onUserServiceRecordRemoved(record: UserServiceRecord) {
    }

    fun removeUserServicesForPackage(packageName: String) {
        val list = packageUserServiceRecords.remove(packageName) ?: return
        val snapshot = synchronized(list) { ArrayList(list) }
        for (record in snapshot) {
            record.removeSelf()
            LOGGER.i("Remove user service %s for package %s", record.token, packageName)
        }
    }

    companion object {
        @JvmField
        val LOGGER: Logger = Logger("UserServiceManager")
    }
}
