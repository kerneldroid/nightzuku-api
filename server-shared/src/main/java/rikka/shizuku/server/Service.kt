/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (PorterCore) under Apache-2.0 / MIT:
 * - Self-referential binder guard in transactRemote (rejecting local Binder instances and server descriptors)
 * - Framework ClassLoader confinement for untrusted incoming Bundles
 */

package rikka.shizuku.server

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SELinux
import android.os.SystemClock
import android.os.SystemProperties
import android.system.Os
import androidx.annotation.CallSuper
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.rish.RishConfig
import rikka.rish.RishHost
import rikka.rish.RishService
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.api.RemoteProcessHolder
import rikka.shizuku.server.security.TransactGuard
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils
import rikka.shizuku.server.util.UserHandleCompat

abstract class Service<
    UserServiceMgr : UserServiceManager,
    ClientMgr : ClientManager<ConfigMgr>,
    ConfigMgr : ConfigManager
> : IShizukuService.Stub() {

    val userServiceManager: UserServiceMgr
    val configManager: ConfigMgr
    val clientManager: ClientMgr
    private val rishService: RishService

    /**
     * Deployment identity policy. When `null`, manager-only transactions and [exit] fail closed.
     */
    open val serverPolicy: ServerPolicy? = null

    /** Pending permission prompts keyed by (uid, pid, requestCode) -> elapsedRealtime when opened. */
    private val openPrompts = ConcurrentHashMap<Triple<Int, Int, Int>, Long>()

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

    open fun checkCallerManagerPermission(func: String, callingUid: Int, callingPid: Int): Boolean {
        val policy = serverPolicy ?: return false
        if (!policy.isManager(callingUid, callingPid)) {
            return false
        }
        return policy.verifyManagerSignature()
    }

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

    protected open fun isCallerAllowedForUserService(callingUid: Int, callingPid: Int): Boolean {
        if (callingUid == OsUtils.getUid()) {
            return true
        }
        val clientRecord = clientManager.findClient(callingUid, callingPid)
        if (checkCallerPermission("addUserService", callingUid, callingPid, clientRecord)) {
            return true
        }
        return clientRecord != null && clientRecord.allowed
    }

    /**
     * Enforces the per-UID capability mask for [func] on behalf of the current Binder caller.
     *
     * Must be called after [enforceCallingPermission] so that unattached callers are rejected
     * before any capability lookup happens. [oneTimeGranted] is an extra hint supplied by the
     * caller; the one-time state recorded in the managers is always considered as well.
     */
    protected fun enforceCapability(func: String, capability: Int, oneTimeGranted: Boolean = false) {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        TransactGuard.enforceCapability(
            configManager,
            callingUid,
            checkCallerManagerPermission(func, callingUid, callingPid),
            capability,
            func,
            oneTimeGranted || isOneTimeGrantedForCaller(callingUid, callingPid)
        )
    }

    /**
     * Whether the caller holds a one-time access grant.
     *
     * Besides the generic manager's grant set this recognizes the "allowed live record without a
     * stored allow decision" shape used by embedding client managers for one-time access, so the
     * generic capability guard never contradicts an override that already approved the call.
     */
    protected open fun isOneTimeGrantedForCaller(uid: Int, pid: Int): Boolean {
        if (clientManager.isOneTimeAccessGranted(uid)) {
            return true
        }
        val entry = configManager.find(uid)
        if (entry != null && (entry.isAllowed() || entry.isDenied())) {
            return false
        }
        return clientManager.findClient(uid, pid)?.allowed == true
    }

    private fun isAccessPausedNow(): Boolean = configManager.isAccessPaused()

    /** Packages installed for [uid]; overridable so embedding servers can supply their own lookup. */
    protected open fun packagesForUid(uid: Int): List<String> = try {
        PackageManagerApis.getPackagesForUidNoThrow(uid)
    } catch (t: Throwable) {
        LOGGER.w(t, "getPackagesForUid(%d)", uid)
        emptyList()
    }

    /**
     * Checks [permission] for [uid] as seen from the privileged server process.
     *
     * Lookup failures are reported as [PackageManager.PERMISSION_DENIED] (fail closed).
     */
    protected open fun checkPermissionForUid(permission: String, pid: Int, uid: Int): Int = try {
        ActivityManagerApis.checkPermission(permission, pid, uid)
    } catch (t: Throwable) {
        LOGGER.w(t, "checkPermission(%s, pid=%d, uid=%d)", permission, pid, uid)
        PackageManager.PERMISSION_DENIED
    }

    /**
     * Terminates the server process with [exitCode].
     *
     * Split out so embedding servers (and tests) can observe or override termination behaviour.
     */
    protected open fun terminateServer(exitCode: Int) {
        System.exit(exitCode)
    }

    protected open fun validateRemoteTransactTarget(targetBinder: IBinder?) {
        if (targetBinder == null) {
            throw IllegalArgumentException("Target binder cannot be null")
        }
        if (targetBinder === this || targetBinder is Binder) {
            throw SecurityException("Permission Denial: transactRemote targets a local binder of the server process")
        }
        val descriptor = try {
            targetBinder.interfaceDescriptor
        } catch (_: Throwable) {
            null
        }
        if (descriptor == ShizukuApiConstants.BINDER_DESCRIPTOR || descriptor == NightProtocol.DESCRIPTOR) {
            throw SecurityException("Forwarding transactions to privileged server interface ($descriptor) is forbidden")
        }
    }

    @Throws(RemoteException::class)
    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        enforceCallingPermission("transactRemote")

        val targetBinder = data.readStrongBinder()
        validateRemoteTransactTarget(targetBinder)

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
            callingUid,
            runCatching { targetBinder.interfaceDescriptor }.getOrDefault("<unavailable>"),
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
        enforceCapability(
            "getSystemProperty",
            NightProtocol.CAP_SYSTEM_PROPS,
            clientManager.isOneTimeAccessGranted(Binder.getCallingUid())
        )
        return try {
            SystemProperties.get(name, defaultValue)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun setSystemProperty(name: String, value: String) {
        enforceCallingPermission("setSystemProperty")
        enforceCapability(
            "setSystemProperty",
            NightProtocol.CAP_SYSTEM_PROPS,
            clientManager.isOneTimeAccessGranted(Binder.getCallingUid())
        )
        try {
            SystemProperties.set(name, value)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        enforceCallingPermission("removeUserService")
        enforceCapability(
            "removeUserService",
            NightProtocol.CAP_USER_SERVICE,
            clientManager.isOneTimeAccessGranted(Binder.getCallingUid())
        )
        options.classLoader = Bundle::class.java.classLoader
        return userServiceManager.removeUserService(conn, options)
    }

    override fun addUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
        enforceCallingPermission("addUserService")
        val callingUid = Binder.getCallingUid()
        enforceCapability(
            "addUserService",
            NightProtocol.CAP_USER_SERVICE,
            clientManager.isOneTimeAccessGranted(callingUid)
        )
        options.classLoader = Bundle::class.java.classLoader

        val callingPid = Binder.getCallingPid()
        val clientRecord = clientManager.findClient(callingUid, callingPid)
        val callingApiVersion = clientRecord?.apiVersion ?: ShizukuApiConstants.SERVER_VERSION
        return userServiceManager.addUserService(conn, options, callingApiVersion) {
            isCallerAllowedForUserService(callingUid, callingPid)
        }
    }

    override fun attachUserService(binder: IBinder, options: Bundle) {
        enforceManagerPermission("attachUserService")
        options.classLoader = Bundle::class.java.classLoader
        userServiceManager.attachUserService(binder, options)
    }

    override fun checkSelfPermission(): Boolean {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true
        }

        if (checkCallerManagerPermission("checkSelfPermission", callingUid, callingPid)) {
            return true
        }

        if (isAccessPausedNow()) {
            return false
        }

        val clientRecord = clientManager.findClient(callingUid, callingPid)
        if (clientRecord != null) {
            return clientRecord.allowed
        }

        // No attached record: fall back to the deployment's runtime permission, but only for a
        // caller whose installation can be matched against a recorded grant. Fail closed.
        val policy = serverPolicy ?: return false
        if (checkPermissionForUid(policy.apiPermission, callingPid, callingUid) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val verifier = configManager as? ConfigManager.ClientAccessVerifier ?: return false
        for (packageName in packagesForUid(callingUid)) {
            if (verifier.verifyClientIdentity(callingUid, packageName)) {
                verifier.recordConnection(callingUid)
                return true
            }
        }
        return false
    }

    override fun requestPermission(requestCode: Int) {
        requestPermissionFor(Binder.getCallingUid(), Binder.getCallingPid(), requestCode)
    }

    /**
     * Opens a permission prompt for an explicitly identified process.
     *
     * [requestPermission] forwards the current Binder caller here; the Night wire uses this entry
     * point so both wires share one prompt ledger and one set of stale-reply checks.
     */
    protected fun requestPermissionFor(callingUid: Int, callingPid: Int, requestCode: Int) {
        val userId = UserHandleCompat.getUserId(callingUid)

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return
        }

        val clientRecord = clientManager.requireClient(callingUid, callingPid)

        if (clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true)
            return
        }

        if (isAccessPausedNow()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        val entry = configManager.find(callingUid)
        if (entry != null && entry.isDenied()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        // Remember the prompt so a later confirmation reply from the manager can be matched
        // against an actually displayed prompt and rejected once it is stale.
        val now = SystemClock.elapsedRealtime()
        openPrompts.entries.removeIf { now - it.value > PERMISSION_PROMPT_TTL_MS }
        openPrompts[Triple(callingUid, callingPid, requestCode)] = now

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
        val callingUid = Binder.getCallingUid()
        enforceCallingPermission("newProcess")
        enforceCapability(
            "newProcess",
            NightProtocol.CAP_SHELL_EXEC,
            clientManager.isOneTimeAccessGranted(callingUid)
        )

        LOGGER.d(
            "newProcess: uid=%d, cmd=%s, env=%s, dir=%s",
            Binder.getCallingUid(),
            cmd.contentToString(),
            env?.contentToString(),
            dir
        )

        val clientRecord = clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid())
        val token = clientRecord?.client?.asBinder()

        val spawned = RishHost.spawnProcessGroupOrNull(cmd, env, dir)
        if (spawned != null) {
            return RemoteProcessHolder(spawned, token)
        }

        val process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }

        return RemoteProcessHolder(process, token)
    }

    /**
     * Registers a legacy-wire client application and dispatches the attach reply.
     *
     * The claimed package must be owned by the calling UID. Client API versions below
     * [MIN_CLIENT_API_VERSION] (13) are rejected: the reply layout below is the Shizuku v13+
     * shape, and the pre-13 reply path is intentionally not ported.
     */
    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        if (application == null || args == null) {
            return
        }

        val requestPackageName =
            args.getString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME) ?: return
        val apiVersion = args.getInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1)
        if (apiVersion < MIN_CLIENT_API_VERSION) {
            val msg = "Unsupported client API version $apiVersion (minimum $MIN_CLIENT_API_VERSION)"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        if (!packagesForUid(callingUid).contains(requestPackageName)) {
            val msg = "Request package $requestPackageName does not belong to uid $callingUid"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        val policy = serverPolicy
        val isManager = policy != null && policy.isManager(callingUid, callingPid) &&
            policy.verifyManagerSignature()

        clientManager.registerLegacyClient(callingUid, callingPid, application)

        val existing = clientManager.findClient(callingUid, callingPid)
        val clientRecord = existing ?: synchronized(this) {
            clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion)
        } ?: run {
            LOGGER.w("attachApplication: addClient failed for uid=%d pid=%d", callingUid, callingPid)
            return
        }

        LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid)

        val denied = configManager.find(callingUid)?.isDenied() == true
        if (isManager) {
            val reply = Bundle().apply {
                putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, OsUtils.getUid())
                putInt(
                    ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION,
                    ShizukuApiConstants.SERVER_VERSION
                )
                putString(
                    ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT,
                    OsUtils.getSELinuxContext()
                )
                putInt(
                    ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION,
                    ShizukuApiConstants.SERVER_PATCH_VERSION
                )
            }
            try {
                application.bindApplication(reply)
            } catch (t: Throwable) {
                LOGGER.w(t, "attachApplication: manager reply failed")
            }
        } else {
            val allowed = !isAccessPausedNow() && clientRecord.allowed
            try {
                LegacyParcelCodec.sendLegacyBindApplication(
                    application.asBinder(),
                    OsUtils.getUid(),
                    ShizukuApiConstants.SERVER_VERSION,
                    ShizukuApiConstants.SERVER_PATCH_VERSION,
                    OsUtils.getSELinuxContext(),
                    allowed,
                    denied
                )
            } catch (t: Throwable) {
                LOGGER.w(t, "attachApplication: legacy reply failed")
            }
        }
    }

    /**
     * Stops the server process on behalf of the manager.
     *
     * Fails closed when no [ServerPolicy] is installed, because the manager exit code is
     * deployment-specific and an unconfigured server has no trustworthy manager identity.
     */
    override fun exit() {
        val policy = serverPolicy
        if (policy == null) {
            LOGGER.w("exit refused: no server policy configured")
            throw SecurityException("Permission Denial: exit requires a configured server policy")
        }

        enforceManagerPermission("exit")
        LOGGER.i("exit")
        terminateServer(policy.managerExitCode)
    }

    /**
     * Forwards a package-change broadcast to [onPackageChanged].
     *
     * The generic server has no package bookkeeping of its own; embedding servers react to
     * installs, uninstalls and replacements by overriding the hook.
     */
    override fun dispatchPackageChanged(intent: Intent) {
        onPackageChanged(intent)
    }

    /**
     * Invoked for every package-change broadcast delivered by the manager.
     *
     * Event hook, not a stub: the default implementation deliberately does nothing because the
     * generic server does not cache package metadata.
     */
    open fun onPackageChanged(intent: Intent) {
    }

    /**
     * Whether every package owned by [uid] is marked as not installed.
     *
     * Returns `false` when the package manager lookup fails so a transient failure can never be
     * reported as "hidden".
     */
    override fun isHidden(uid: Int): Boolean {
        val userId = UserHandleCompat.getUserId(uid)
        var resolved = false
        for (packageName in packagesForUid(uid)) {
            val applicationInfo = try {
                PackageManagerApis.getApplicationInfo(packageName, 0L, userId)
            } catch (t: Throwable) {
                LOGGER.w(t, "isHidden: getApplicationInfo(%s) failed", packageName)
                return false
            }
            if (applicationInfo == null) {
                LOGGER.w("isHidden: no ApplicationInfo for %s in user %d", packageName, userId)
                return false
            }
            resolved = true
            if ((applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0) {
                return false
            }
        }
        return resolved
    }

    /**
     * Applies the manager's answer to a permission prompt opened by [requestPermission].
     *
     * Replies are matched against the prompt ledger, so unsolicited replies and replies that
     * arrive after [PERMISSION_PROMPT_TTL_MS] are discarded instead of granting access.
     */
    override fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle?
    ) {
        enforceManagerPermission("dispatchPermissionConfirmationResult")

        if (data == null) {
            return
        }

        val openedAt = openPrompts.remove(Triple(requestUid, requestPid, requestCode))
        if (openedAt == null) {
            LOGGER.w(
                "Rejected unsolicited permission confirmation for uid=%d pid=%d code=%d (no active prompt)",
                requestUid,
                requestPid,
                requestCode
            )
            return
        }
        if (SystemClock.elapsedRealtime() - openedAt > PERMISSION_PROMPT_TTL_MS) {
            LOGGER.w(
                "Prompt expired for uid=%d pid=%d code=%d",
                requestUid,
                requestPid,
                requestCode
            )
            clientManager.findClient(requestUid, requestPid)
                ?.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        val allowed = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED)
        val onetime = data.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME)

        LOGGER.i(
            "dispatchPermissionConfirmationResult: uid=%d pid=%d code=%d allowed=%s onetime=%s",
            requestUid,
            requestPid,
            requestCode,
            allowed.toString(),
            onetime.toString()
        )

        val uidPackages = packagesForUid(requestUid).toMutableList()
        if (!onetime) {
            clientManager.revokeOneTimeAccess(requestUid)
            configManager.update(
                requestUid,
                uidPackages,
                ConfigManager.MASK_PERMISSION,
                if (allowed) ConfigManager.FLAG_ALLOWED else ConfigManager.FLAG_DENIED
            )
            if (allowed) {
                (configManager as? ConfigManager.ClientAccessVerifier)?.recordConnection(requestUid)
            }
        } else if (allowed) {
            clientManager.grantOneTimeAccess(requestUid)
        } else {
            clientManager.revokeOneTimeAccess(requestUid)
        }

        val effectiveAllowed = allowed && !isAccessPausedNow()
        val capabilities = if (effectiveAllowed) {
            currentCapabilities(requestUid)
        } else {
            0
        }

        val records = clientManager.findClients(requestUid)
        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid)
        }
        for (record in records) {
            record.allowed = effectiveAllowed
            if (record.pid == requestPid) {
                record.dispatchRequestPermissionResult(requestCode, effectiveAllowed)
            }
        }

        onCapabilitiesUpdated(requestUid, capabilities)
    }

    /**
     * Permission and capability flags stored for [uid], restricted to [mask].
     *
     * Manager-only; entries that do not expose raw flags report `0`.
     */
    override fun getFlagsForUid(uid: Int, mask: Int): Int {
        enforceManagerPermission("getFlagsForUid")
        val entry = configManager.find(uid) ?: return 0
        val flags = (entry as? ConfigFlagsEntry)?.capabilityFlags() ?: 0
        return flags and mask
    }

    /**
     * Updates permission and capability flags for [uid]. Manager-only.
     *
     * A permission-mask update also revokes one-time grants and re-evaluates attached records;
     * a capability-mask update is reported through [onCapabilitiesUpdated].
     */
    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        enforceManagerPermission("updateFlagsForUid")

        configManager.update(uid, packagesForUid(uid).toMutableList(), mask, value)

        if ((mask and ConfigManager.MASK_PERMISSION) != 0) {
            clientManager.revokeOneTimeAccess(uid)
            val allowed = (value and ConfigManager.FLAG_ALLOWED) != 0
            val effectiveAllowed = allowed && !isAccessPausedNow()
            for (record in clientManager.findClients(uid)) {
                record.allowed = effectiveAllowed
            }
        }

        if ((mask and NightProtocol.MASK_CAPABILITIES) != 0) {
            onCapabilitiesUpdated(uid, currentCapabilities(uid))
        }
    }

    /**
     * Invoked after capabilities for [uid] changed so clients can be pushed the new state.
     *
     * Event hook: the generic server has no callback channel of its own, embedding servers wire
     * this to their client notifications.
     */
    open fun onCapabilitiesUpdated(uid: Int, flags: Int) {
    }

    private fun currentCapabilities(uid: Int): Int = TransactGuard.effectiveCapabilities(
        configManager.find(uid),
        isAccessPausedNow(),
        clientManager.isOneTimeAccessGranted(uid)
    )

    /**
     * Handles transactions outside the [IShizukuService] and rish ranges.
     *
     * Embedding servers override this to service their private transaction codes from the same
     * binder. Returning `true` means the transaction was consumed.
     */
    open fun onServerTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false

    @Throws(RemoteException::class)
    fun onTransactRish(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return rishService.onTransact(code, data, reply, flags)
    }

    @CallSuper
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            transactRemote(data, reply, flags)
            return true
        } else if (onServerTransact(code, data, reply, flags)) {
            return true
        } else if (rishService.onTransact(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        /** Oldest legacy client API version whose attach reply layout this server produces. */
        const val MIN_CLIENT_API_VERSION: Int = 13

        /** How long a permission prompt may be answered before its reply is rejected. */
        const val PERMISSION_PROMPT_TTL_MS: Long = 5 * 60 * 1000L

        @JvmField
        val LOGGER: Logger = Logger("Service")
    }
}
