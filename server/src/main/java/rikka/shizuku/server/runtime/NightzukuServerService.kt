package rikka.shizuku.server.runtime

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IShizukuApplication
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.rish.RishConfig
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.Service
import rikka.shizuku.server.ServerPolicy
import rikka.shizuku.server.api.NightServerHost
import rikka.shizuku.server.api.NightServiceEndpoint
import rikka.shizuku.server.security.TransactGuard
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.PackageSignatureVerifier

/**
 * Concrete privileged server for the standalone Nightzuku runtime.
 *
 * The class owns the deployment wiring only: managers backed by [ServerConfig], the legacy
 * `IShizukuService` binder, and the flat-Parcel [NightServiceEndpoint] served from the same
 * binder. Protocol framing, capability enforcement, prompt ledgers and record bookkeeping stay in
 * the `server-shared` abstractions.
 *
 * The process entrypoint is [main]; the constructor form is also usable when an embedding APK
 * already owns the process lifecycle.
 */
open class NightzukuServerService :
    Service<NightzukuUserServiceManager, NightzukuClientManager, NightzukuConfigManager>(),
    NightServerHost {

    /** Deployment configuration resolved for this process. */
    val config: ServerConfig = resolveProcessConfig()

    private var managerBaseline: PackageSignatureVerifier.Baseline? = null

    /**
     * Night-wire endpoint served on this binder.
     *
     * Created lazily because the [Service] constructor invokes the `onCreate*` factories before
     * subclass fields are initialized. [createNightEndpoint] lets an embedding runtime (or a test)
     * supply an endpoint with deployment-specific package lookups.
     */
    val nightEndpoint: NightServiceEndpoint by lazy { createNightEndpoint() }

    protected open fun createNightEndpoint(): NightServiceEndpoint = NightServiceEndpoint(this)

    override val serverPolicy: ServerPolicy? by lazy {
        NightzukuServerPolicy(
            config = config,
            signatureVerifier = ::verifyManagerSignature
        )
    }

    init {
        val looper = Looper.myLooper() ?: Looper.getMainLooper()
            ?: throw IllegalStateException("NightzukuServerService requires a prepared Looper")
        HandlerUtil.setMainHandler(Handler(looper))
        managerBaseline = runCatching {
            resolveManagerBaseline()
        }.onFailure {
            LOGGER.w(it, "Unable to pin the manager signing baseline")
        }.getOrNull()
        userServiceManager.setManagerBaseline(managerBaseline)
    }

    override fun onCreateUserServiceManager(): NightzukuUserServiceManager =
        NightzukuUserServiceManager(resolveProcessConfig())

    override fun onCreateClientManager(): NightzukuClientManager =
        NightzukuClientManager(configManager)

    override fun onCreateConfigManager(): NightzukuConfigManager =
        NightzukuConfigManager(resolveProcessConfig())

    override val serverBinder: IBinder
        get() = this

    // --- Night wire host -----------------------------------------------------

    override fun attachNightClient(
        callingUid: Int,
        callingPid: Int,
        bridgeApp: IShizukuApplication,
        packageName: String,
        clientProtocolVersion: Int
    ): ClientRecord? {
        val existing = clientManager.findClient(callingUid, callingPid)
        val record = existing ?: synchronized(this) {
            clientManager.addClient(
                callingUid,
                callingPid,
                bridgeApp,
                packageName,
                clientProtocolVersion
            )
        }

        if (record != null && !isManagerUid(callingUid, callingPid)) {
            val oneTimeGranted = clientManager.isOneTimeAccessGranted(callingUid)
            if (configManager.isAccessPaused()) {
                record.allowed = false
            } else if (oneTimeGranted) {
                record.allowed = true
                configManager.recordConnection(callingUid)
            } else if (record.allowed &&
                !configManager.verifyClientIdentity(callingUid, packageName)
            ) {
                record.allowed = false
            } else if (record.allowed) {
                configManager.recordConnection(callingUid)
            }
        }
        return record
    }

    override fun onClientProcessDied(uid: Int, pid: Int) {
        clientManager.notifyClientProcessDied(uid, pid)
    }

    override fun requestPermissionFromNightWire(uid: Int, pid: Int, requestCode: Int) {
        // Reuses the legacy prompt ledger and stale-reply checks; only the entry point differs.
        requestPermissionFor(uid, pid, requestCode)
    }

    override fun notifyCapabilitiesUpdated(uid: Int) {
        val paused = configManager.isAccessPaused()
        val entry = configManager.find(uid)
        val oneTime = clientManager.isOneTimeAccessGranted(uid)
        val allowed = !paused && (entry?.isAllowed() == true || oneTime)
        val rationale = !oneTime && (entry?.isDenied() == true)
        val capabilities = if (allowed) {
            TransactGuard.effectiveCapabilities(
                entry,
                paused,
                oneTime
            )
        } else {
            0
        }
        for (record in clientManager.findClients(uid)) {
            record.allowed = allowed
            nightEndpoint.notifyPermissionChanged(
                record.uid,
                record.pid,
                allowed,
                rationale,
                capabilities
            )
        }
    }

    override fun setAccessPaused(paused: Boolean) {
        LOGGER.i("setAccessPaused: %s", paused)
        configManager.setAccessPaused(paused)
        userServiceManager.updateAccessPaused(paused)

        for (record in clientManager.allClients()) {
            val entry = configManager.find(record.uid)
            val oneTime = clientManager.isOneTimeAccessGranted(record.uid)
            val allowed = !paused && (entry?.isAllowed() == true || oneTime)
            record.allowed = allowed
            val capabilities = if (allowed) {
                TransactGuard.effectiveCapabilities(entry, paused, oneTime)
            } else {
                0
            }
            nightEndpoint.notifyPermissionChanged(
                record.uid,
                record.pid,
                allowed,
                !oneTime && (entry?.isDenied() == true),
                capabilities
            )
        }
    }

    /**
     * The NightDog watchdog is not part of the standalone runtime; callers always see `false`.
     */
    override fun getNightDogEnabled(default: Boolean): Boolean = false

    // --- Permission prompts --------------------------------------------------

    override fun showPermissionConfirmation(
        requestCode: Int,
        clientRecord: ClientRecord,
        callingUid: Int,
        callingPid: Int,
        userId: Int
    ) {
        if (configManager.isAccessPaused()) {
            val notified = nightEndpoint.notifyRequestPermissionResult(
                callingUid,
                callingPid,
                requestCode,
                false,
                0
            )
            if (!notified) {
                clientRecord.dispatchRequestPermissionResult(requestCode, false)
            }
            return
        }

        startPermissionRequest(
            buildPermissionRequestIntent(callingUid, callingPid, userId, requestCode),
            userId
        )
    }

    /** Builds the manager request intent; kept separate so its extras are directly testable. */
    protected open fun buildPermissionRequestIntent(
        callingUid: Int,
        callingPid: Int,
        userId: Int,
        requestCode: Int
    ): Intent = Intent(config.requestPermissionAction)
        .setPackage(config.managerPackageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        .putExtra(EXTRA_UID, callingUid)
        .putExtra(EXTRA_PID, callingPid)
        .putExtra(EXTRA_REQUEST_CODE, requestCode)
        .putExtra(EXTRA_USER_ID, userId)

    /**
     * Delivers the prompt request to the manager.
     *
     * Split out so an embedding manager can bridge to its own UI transport; the standalone
     * runtime asks ActivityManager to start the manager's request screen.
     */
    protected open fun startPermissionRequest(intent: Intent, userId: Int) {
        ActivityManagerApis.startActivityNoThrow(intent, null, userId)
    }

    // --- Service hooks -------------------------------------------------------

    override fun onCapabilitiesUpdated(uid: Int, flags: Int) {
        notifyCapabilitiesUpdated(uid)
    }

    private fun isManagerUid(callingUid: Int, callingPid: Int): Boolean {
        val policy = serverPolicy ?: return false
        return policy.isManager(callingUid, callingPid) && policy.verifyManagerSignature()
    }

    private fun resolveManagerBaseline(): PackageSignatureVerifier.Baseline? {
        val packageInfo = PackageManagerApis.getPackageInfoNoThrow(
            config.managerPackageName,
            PackageSignatureVerifier.signatureLookupFlags(),
            0
        ) ?: return null
        return PackageSignatureVerifier.extractBaseline(packageInfo)
    }

    /**
     * Re-verifies the installed manager package against the startup baseline.
     *
     * A positively detected uninstall/replacement terminates the server with the configured exit
     * code so the manager can restart it. A transient PackageManager failure returns `false`
     * without terminating, and the next transaction retries the lookup.
     */
    private fun verifyManagerSignature(): Boolean {
        val baseline = managerBaseline ?: return true

        val probe = try {
            val packageInfo = PackageManagerApis.getPackageInfoNoThrow(
                config.managerPackageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                0
            )
            PackageSignatureVerifier.evaluatePackageInfo(packageInfo, 0)
        } catch (t: Throwable) {
            PackageSignatureVerifier.ProbeResult.LookupError(t)
        }

        return when (probe) {
            is PackageSignatureVerifier.ProbeResult.Present -> {
                if (baseline.matches(probe.snapshot)) {
                    true
                } else {
                    LOGGER.e("Manager package identity changed, terminating server")
                    terminateServer(config.managerExitCode)
                    false
                }
            }
            is PackageSignatureVerifier.ProbeResult.Absent -> {
                LOGGER.w("Manager package %s is gone in user 0", config.managerPackageName)
                terminateServer(config.managerExitCode)
                false
            }
            is PackageSignatureVerifier.ProbeResult.LookupError -> {
                LOGGER.w(probe.cause, "Transient error probing the manager package")
                false
            }
        }
    }

    // --- Binder dispatch -----------------------------------------------------

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in NightProtocol.TRANSACTION_ATTACH..NightProtocol.TRANSACTION_CANCEL_EXEC &&
            isNightWire(data)
        ) {
            return nightEndpoint.onTransact(code, data, reply, flags)
        }
        if (onTransactRish(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Whether [data] was written for the Night wire.
     *
     * Both wires use overlapping transaction numbers on this binder, so the interface token is the
     * only reliable discriminator. The token is peeked at without consuming the parcel.
     */
    private fun isNightWire(data: Parcel): Boolean {
        val position = data.dataPosition()
        return try {
            data.enforceInterface(NightProtocol.DESCRIPTOR)
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.setDataPosition(position)
        }
    }

    companion object {
        const val SERVER_PROCESS_NAME: String = "nightzuku_server"

        private const val PUBLISH_ATTEMPTS = 10

        const val EXTRA_UID: String = "uid"
        const val EXTRA_PID: String = "pid"
        const val EXTRA_REQUEST_CODE: String = "requestCode"
        const val EXTRA_USER_ID: String = "userId"

        /**
         * Per-construction configuration override.
         *
         * [Service] invokes its `onCreate*` factories from its own constructor, before subclass
         * fields exist, so a constructor parameter cannot be used to inject configuration. A
         * thread-local scope lets unit tests build the real service with a deterministic config
         * without a global mutable singleton.
         */
        private val configScope = ThreadLocal<ServerConfig?>()

        internal fun resolveProcessConfig(): ServerConfig =
            configScope.get() ?: ServerConfig.load()

        @JvmStatic
        internal fun <T> withConfig(config: ServerConfig, block: () -> T): T {
            val previous = configScope.get()
            configScope.set(config)
            try {
                return block()
            } finally {
                if (previous == null) {
                    configScope.remove()
                } else {
                    configScope.set(previous)
                }
            }
        }

        /**
         * `app_process` entrypoint.
         *
         * Publishes both the legacy binder and the Night endpoint to the manager provider and then
         * runs the main looper for the lifetime of the process.
         */
        @JvmStatic
        @SuppressLint("PrivateApi")
        @Suppress("DEPRECATION")
        fun main(args: Array<String>) {
            runCatching {
                Class.forName("android.ddm.DdmHandleAppName")
                    .getMethod("setAppName", String::class.java, Integer.TYPE)
                    .invoke(null, SERVER_PROCESS_NAME, 0)
            }.onFailure {
                LOGGER.w(it, "DdmHandleAppName.setAppName failed")
            }

            RishConfig.setLibraryPath(System.getProperty(ServerConfig.PROP_LIBRARY_PATH))

            Looper.prepareMainLooper()
            val service = NightzukuServerService()
            ProviderBinderClient.publishToManager(
                legacyBinder = service,
                nightBinder = service.nightEndpoint,
                config = service.config,
                attempts = PUBLISH_ATTEMPTS
            )
            Looper.loop()
        }

        @JvmField
        val LOGGER: Logger = Logger("NightzukuServer")
    }
}
