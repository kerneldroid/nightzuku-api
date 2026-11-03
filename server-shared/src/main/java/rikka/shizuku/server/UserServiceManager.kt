/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (UserServiceManager) under Apache-2.0 / MIT:
 * - Per-user record keys (`$userId:$packageName:${tag ?: className}`) and `ownerUid` tracking
 * - Off-monitor `cleanupExecutor` for `destroy()` and `killer` ScheduledExecutorService for `HostProcess` termination
 * - Spawn retry loop (`MAX_SPAWN_RETRIES`), `pendingDestroy`, `destroyUnknownBinder`, and `dropRecordIfNotAttachedLocked`
 */

package rikka.shizuku.server

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.text.format.DateUtils
import android.util.ArrayMap
import java.util.Collections
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
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
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
import rikka.shizuku.server.util.AbiUtil
import rikka.shizuku.server.util.HostProcess
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils
import rikka.shizuku.server.util.PackageSignatureVerifier
import rikka.shizuku.server.util.ProcessCompat
import rikka.shizuku.server.util.UserHandleCompat

abstract class UserServiceManager {

    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "user-service-starter").apply { isDaemon = true }
    }
    private val cleanupExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "user-service-cleanup").apply { isDaemon = true }
    }
    private val killer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "user-service-killer").apply { isDaemon = true }
    }

    private val userServiceRecords: MutableMap<String, UserServiceRecord> =
        Collections.synchronizedMap(ArrayMap())
    private val packageUserServiceRecords: MutableMap<String, MutableList<UserServiceRecord>> =
        Collections.synchronizedMap(ArrayMap())

    /**
     * Serializes subclass hooks. Hooks are invoked outside [UserServiceManager]'s monitor (so
     * they may acquire their own locks or call back into the manager) but must still not run
     * concurrently with each other.
     */
    private val hookLock = Any()

    @Volatile
    private var accessPaused: Boolean = false

    open fun ensureCallingPackageForUserService(packageName: String, appId: Int, userId: Int): PackageInfo {
        @SuppressLint("UnsafeOptInUsageError")
        val packageInfo = PackageManagerApis.getPackageInfoNoThrow(
            packageName,
            userServiceLookupFlags(),
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

    private fun buildRecordKey(userId: Int, packageName: String, tagOrClass: String): String =
        "$userId:$packageName:$tagOrClass"

    private fun getUserServiceRecordLocked(userId: Int, packageName: String, tagOrClass: String): UserServiceRecord? {
        return userServiceRecords[buildRecordKey(userId, packageName, tagOrClass)]
            ?: userServiceRecords["$packageName:$tagOrClass"]
    }

    fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        options.classLoader = Bundle::class.java.classLoader
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

        val remove = if (options.containsKey(USER_SERVICE_ARG_REMOVE)) {
            options.getBoolean(USER_SERVICE_ARG_REMOVE)
        } else {
            true
        }

        if (conn == null && !remove) {
            throw IllegalArgumentException("connection is null and the service is not being removed")
        }

        var removed: UserServiceRecord? = null
        synchronized(this) {
            val record = getUserServiceRecordLocked(userId, packageName, tag ?: className) ?: return 1
            if (remove) {
                removed = removeUserServiceLocked(record)
            } else {
                record.callbacks.unregister(conn)
            }
        }
        removed?.let(::finishRemoval)
        return 0
    }

    /**
     * Removes [record] from the bookkeeping maps and marks it removed. The caller must invoke
     * [finishRemoval] after leaving the manager monitor.
     */
    private fun removeUserServiceLocked(record: UserServiceRecord): UserServiceRecord? =
        detachUserServiceLocked(record)

    private fun detachUserServiceLocked(record: UserServiceRecord): UserServiceRecord? {
        val removed = userServiceRecords.values.remove(record)
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
        if (!removed) return null
        record.markRemoved()
        return record
    }

    /**
     * Runs the externally visible removal side effects. Must be called outside the manager monitor:
     * [onUserServiceRecordRemoved] is a subclass hook and may acquire its own locks.
     */
    private fun finishRemoval(record: UserServiceRecord) {
        try {
            synchronized(hookLock) {
                onUserServiceRecordRemoved(record)
            }
        } finally {
            record.host?.let(::scheduleHostKill)
            cleanupExecutor.execute { record.destroy() }
        }
    }

    private fun dropRecordIfNotAttached(record: UserServiceRecord) {
        val removed = synchronized(this) {
            if (record.service == null && !record.isRemoved) {
                removeUserServiceLocked(record)
            } else {
                null
            }
        }
        removed?.let(::finishRemoval)
    }

    private fun destroyUnknownBinder(binder: IBinder, interfaceDescriptor: String?) {
        cleanupExecutor.execute {
            if (!binder.pingBinder()) return@execute
            val descriptor = interfaceDescriptor ?: getInterfaceDescriptor(binder)
            if (descriptor.isNullOrEmpty()) return@execute
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                binder.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY)
                LOGGER.v("Sent destroy to unattached/orphaned UserService binder (%s)", descriptor)
            } catch (t: Throwable) {
                LOGGER.w(t, "Failed to destroy unknown UserService binder")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    /**
     * Returns whether an existing record may be reused for the current installation.
     *
     * The base implementation compares the owning UID and, when a signing baseline was captured
     * at creation time, the current signing certificate snapshot (including P+ key rotation
     * lineage). Subclasses may override to add stricter checks.
     */
    open fun canReuseUserServiceRecord(record: UserServiceRecord, packageInfo: PackageInfo): Boolean {
        val applicationInfo = packageInfo.applicationInfo ?: return false
        if (record.ownerUid >= 0 && record.ownerUid != applicationInfo.uid) return false
        val baseline = record.signingBaseline ?: return true
        val userId = UserHandleCompat.getUserId(applicationInfo.uid)
        val snapshot = PackageSignatureVerifier.extractSnapshot(packageInfo, userId) ?: return true
        return baseline.matches(snapshot)
    }

    @JvmOverloads
    fun addUserService(
        conn: IShizukuServiceConnection?,
        options: Bundle?,
        callingApiVersion: Int,
        isCallerStillAllowed: () -> Boolean = { true }
    ): Int {
        Objects.requireNonNull(conn, "connection is null")
        Objects.requireNonNull(options, "options is null")
        val validConn = conn!!
        val validOptions = options!!.apply {
            classLoader = Bundle::class.java.classLoader
        }

        if (isAccessPaused()) {
            throw SecurityException("Client access is globally paused")
        }

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
        val key = buildRecordKey(userId, packageName, tag ?: className)

        val pendingRemovals = ArrayList<UserServiceRecord>()
        val createdRecords = ArrayList<UserServiceRecord>()
        var startRecord: UserServiceRecord? = null
        val result: Int
        synchronized(this) {
            if (!isCallerStillAllowed()) {
                throw SecurityException("Permission revoked for uid=$uid before starting UserService")
            }

            var record = getUserServiceRecordLocked(userId, packageName, tag ?: className)
            if (record != null && !canReuseUserServiceRecord(record, packageInfo)) {
                LOGGER.w(
                    "Service record %s (%s) does not belong to current installation of %s",
                    key,
                    record.token,
                    packageName
                )
                removeUserServiceLocked(record)?.let(pendingRemovals::add)
                record = null
            }

            if (noCreate) {
                if (record != null) {
                    val activeBinder = record.service
                    if (activeBinder != null && activeBinder.pingBinder()) {
                        record.callbacks.register(validConn)
                        record.broadcastBinderReceived()
                        result = if (callingApiVersion >= 13) record.versionCode else 0
                    } else {
                        result = if (callingApiVersion >= 13) -1 else 1
                    }
                } else {
                    result = if (callingApiVersion >= 13) -1 else 1
                }
            } else {
                val newRecord = createUserServiceRecordIfNeededLocked(
                    record,
                    key,
                    versionCode,
                    daemon,
                    packageInfo,
                    pendingRemovals,
                    createdRecords
                )
                newRecord.callbacks.register(validConn)

                val activeBinder = newRecord.service
                if (activeBinder != null && activeBinder.pingBinder()) {
                    newRecord.broadcastBinderReceived()
                } else if (!newRecord.starting) {
                    newRecord.setStartingTimeout(DateUtils.SECOND_IN_MILLIS * 30)
                    startRecord = newRecord
                }
                result = 0
            }
        }

        // Hooks and process spawning happen outside the monitor.
        for (removed in pendingRemovals) {
            finishRemoval(removed)
        }
        val created = createdRecords.firstOrNull()
        if (created != null) {
            try {
                synchronized(hookLock) {
                    onUserServiceRecordCreated(created, packageInfo)
                }
            } catch (t: Throwable) {
                val removed = synchronized(this) { removeUserServiceLocked(created) }
                removed?.let(::finishRemoval)
                throw t
            }
        }
        startRecord?.let { record ->
            executor.execute {
                startUserServiceWithRetry(
                    record,
                    key,
                    record.token,
                    packageName,
                    className,
                    processNameSuffix,
                    uid,
                    use32Bits,
                    debug
                )
            }
        }
        return result
    }

    private fun createUserServiceRecordIfNeededLocked(
        record: UserServiceRecord?,
        key: String,
        versionCode: Int,
        daemon: Boolean,
        packageInfo: PackageInfo,
        pendingRemovals: MutableList<UserServiceRecord>,
        createdOut: MutableList<UserServiceRecord>
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
            } else if (!record.isRemoved && !record.pendingDestroy) {
                LOGGER.i("Found existing service record %s (%s)", key, record.token)
                if (record.daemon != daemon) {
                    record.daemon = daemon
                }
                return record
            }

            removeUserServiceLocked(record)?.let(pendingRemovals::add)
        }

        val created = object : UserServiceRecord(versionCode, daemon) {
            override fun removeSelf() {
                val removed = synchronized(this@UserServiceManager) {
                    removeUserServiceLocked(this)
                }
                removed?.let { this@UserServiceManager.finishRemoval(it) }
            }
        }
        created.ownerUid = packageInfo.applicationInfo!!.uid
        created.signingBaseline = PackageSignatureVerifier.extractBaseline(packageInfo)

        val packageName = packageInfo.packageName
        val list = packageUserServiceRecords.getOrPut(packageName) {
            Collections.synchronizedList(ArrayList())
        }
        list.add(created)

        userServiceRecords[key] = created
        createdOut.add(created)
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

    private fun startUserServiceWithRetry(
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
        var lastError: Throwable? = null
        for (attempt in 1..MAX_SPAWN_RETRIES) {
            if (record.isRemoved || record.pendingDestroy) {
                LOGGER.v("Service record %s (%s) was removed before spawn attempt %d", key, token, attempt)
                return
            }
            try {
                startUserService(
                    record,
                    key,
                    token,
                    packageName,
                    classname,
                    processNameSuffix,
                    callingUid,
                    use32Bits,
                    debug
                )
                return
            } catch (tr: Throwable) {
                lastError = tr
                LOGGER.w(
                    tr,
                    "Spawn attempt %d/%d failed for service record %s (%s)",
                    attempt,
                    MAX_SPAWN_RETRIES,
                    key,
                    token
                )
                if (attempt < MAX_SPAWN_RETRIES && !record.isRemoved && !record.pendingDestroy) {
                    try {
                        Thread.sleep(SPAWN_RETRY_DELAY_MS * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        LOGGER.e(lastError, "All %d spawn attempts failed for service record %s (%s)", MAX_SPAWN_RETRIES, key, token)
        dropRecordIfNotAttached(record)
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
        if (record.isRemoved || record.pendingDestroy || record.service != null) {
            return
        }
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
        if (record.isRemoved || record.pendingDestroy) {
            return
        }
        var process: Process? = null
        val exitCode = try {
            process = Runtime.getRuntime().exec("sh")
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.outputStream.use { os ->
                os.write(cmd.toByteArray())
                os.flush()
            }
            if (!ProcessCompat.waitFor(process, START_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ProcessCompat.destroyForcibly(process)
                throw IllegalStateException("Timed out starting UserService")
            }
            process.exitValue()
        } catch (e: Throwable) {
            process?.let { ProcessCompat.destroyForcibly(it) }
            if (e is IllegalStateException) throw e
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

    private fun sendUserServiceLocked(binder: IBinder, token: String, interfaceDescriptor: String?) {
        val record = userServiceRecords.values.firstOrNull { it.token == token }
        if (record == null || record.isRemoved || record.pendingDestroy) {
            LOGGER.w("Received binder for removed or unknown service record token=%s; destroying binder", token)
            destroyUnknownBinder(binder, interfaceDescriptor)
            return
        }
        if (record.service != null && record.service !== binder) {
            LOGGER.w("Service record %s already holds a live binder; rejecting duplicate attach", token)
            destroyUnknownBinder(binder, interfaceDescriptor)
            return
        }

        LOGGER.v("Received binder for service record %s", token)
        record.setBinder(binder, interfaceDescriptor)
    }

    fun attachUserService(binder: IBinder?, options: Bundle?) {
        val validBinder = Objects.requireNonNull(binder, "binder is null")!!
        val validOptions = Objects.requireNonNull(options, "options is null")!!.apply {
            classLoader = Bundle::class.java.classLoader
        }
        val token = Objects.requireNonNull(
            validOptions.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN),
            "token is null"
        )!!
        attachUserService(validBinder, token)
    }

    fun attachUserService(binder: IBinder, token: String) {
        // Resolve interfaceDescriptor outside monitor to avoid blocking under lock
        val descriptor = getInterfaceDescriptor(binder)
        synchronized(this) {
            sendUserServiceLocked(binder, token, descriptor)
        }
    }

    @JvmOverloads
    open fun claimUserServiceLaunch(
        token: String?,
        pid: Int,
        expectedUid: Int = OsUtils.getUid()
    ): Boolean {
        if (isAccessPaused()) return false
        if (token.isNullOrEmpty()) return false
        val host = captureHost(pid) ?: return false
        if (host.uid != expectedUid) return false
        synchronized(this) {
            val record = userServiceRecords.values.firstOrNull { it.token == token } ?: return false
            if (record.isRemoved || record.pendingDestroy || !record.starting || record.service != null) {
                return false
            }
            val claimed = record.host
            if (claimed != null) return claimed.sameAs(host)
            record.host = host
            return true
        }
    }

    protected open fun captureHost(pid: Int): HostProcess? = HostProcess.capture(pid)

    protected open fun scheduleHostKill(host: HostProcess) {
        killer.schedule({ host.killIfSame() }, HOST_KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun isUserServiceTokenLive(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        synchronized(this) {
            val record = userServiceRecords.values.firstOrNull { it.token == token } ?: return false
            return !record.isRemoved && !record.pendingDestroy
        }
    }

    /** Immutable snapshot of every tracked UserService record. */
    fun snapshotRecords(): List<UserServiceRecord> {
        synchronized(this) {
            return ArrayList(userServiceRecords.values)
        }
    }

    /** Finds a record by its attach token, or returns `null` if it is no longer tracked. */
    fun findRecordByToken(token: String?): UserServiceRecord? {
        if (token.isNullOrEmpty()) return null
        synchronized(this) {
            return userServiceRecords.values.firstOrNull { it.token == token }
        }
    }

    /** UID that owns [record], captured when the record was created. */
    fun recordUid(record: UserServiceRecord): Int = record.ownerUid

    /**
     * Signing baseline pinned for [record] when it was created, or `null` when the package had no
     * verifiable signing certificates.
     *
     * Embedding managers read this instead of keeping a second baseline map: the record is the
     * single owner of the per-service identity state.
     */
    fun recordSigningBaseline(record: UserServiceRecord): PackageSignatureVerifier.Baseline? =
        record.signingBaseline

    /** Whether all client access is administratively paused. */
    fun isAccessPaused(): Boolean = accessPaused

    /**
     * Pauses or resumes client access. Pausing also tears down every tracked UserService, since
     * a running service would otherwise keep operating after access was revoked.
     */
    fun updateAccessPaused(paused: Boolean) {
        if (accessPaused == paused) return
        accessPaused = paused
        if (paused) {
            removeAllUserServiceRecords()
        }
    }

    /**
     * Removes every tracked UserService record.
     *
     * This is the member implementation; [removeAllUserServices] is the historical alias.
     */
    fun removeAllUserServiceRecords() {
        val records = snapshotRecords()
        for (record in records) {
            try {
                record.removeSelf()
            } catch (t: Throwable) {
                LOGGER.w(t, "removeAllUserServiceRecords: %s", record.token)
            }
            LOGGER.i("Remove user service %s", record.token)
        }
    }

    open fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
    }

    open fun onUserServiceRecordRemoved(record: UserServiceRecord) {
    }

    fun removeUserServicesForPackage(packageName: String) {
        val snapshot: List<UserServiceRecord>
        synchronized(this) {
            val list = packageUserServiceRecords.remove(packageName) ?: return
            snapshot = synchronized(list) { ArrayList(list) }
        }
        for (record in snapshot) {
            record.removeSelf()
            LOGGER.i("Remove user service %s for package %s", record.token, packageName)
        }
    }

    open fun removeUserServicesForUid(uid: Int): Boolean {
        val snapshot: List<UserServiceRecord>
        synchronized(this) {
            snapshot = userServiceRecords.values.filter { it.ownerUid == uid }
        }
        if (snapshot.isEmpty()) return false
        for (record in snapshot) {
            record.removeSelf()
            LOGGER.i("Remove user service %s for uid %d", record.token, uid)
        }
        return true
    }

    companion object {
        @JvmField
        val LOGGER: Logger = Logger("UserServiceManager")

        const val MAX_SPAWN_RETRIES: Int = 3
        const val SPAWN_RETRY_DELAY_MS: Long = 150L
        const val HOST_KILL_GRACE_MILLIS: Long = 3_000L
        const val START_COMMAND_TIMEOUT_SECONDS: Long = 15L

        @Suppress("DEPRECATION")
        private fun userServiceLookupFlags(): Long {
            val flags = 0x00002000L /* PackageManager.MATCH_UNINSTALLED_PACKAGES */
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            } else {
                flags or PackageManager.GET_SIGNATURES.toLong()
            }
        }

        @JvmStatic
        fun getInterfaceDescriptor(binder: IBinder?): String? {
            if (binder == null) return null
            return try {
                binder.interfaceDescriptor
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getInterfaceDescriptor")
                null
            }
        }
    }
}

/**
 * Compatibility alias for the historical Shizuku UserService manager API.
 *
 * Declared as an extension so that existing subclasses that already expose
 * `removeAllUserServices` keep compiling unchanged; Kotlin resolves the subclass member first.
 */
fun UserServiceManager.removeAllUserServices() = removeAllUserServiceRecords()

/**
 * Compatibility alias for the historical Shizuku UserService manager API.
 *
 * Declared as an extension so that existing subclasses that already expose `setAccessPaused`
 * keep compiling unchanged; Kotlin resolves the subclass member first.
 */
fun UserServiceManager.setAccessPaused(paused: Boolean) = updateAccessPaused(paused)
