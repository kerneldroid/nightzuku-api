package rikka.shizuku.server.runtime

import android.content.pm.PackageInfo
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.PackageSignatureVerifier
import rikka.shizuku.server.util.UserHandleCompat

/**
 * UserService lifecycle for the standalone runtime.
 *
 * The base [UserServiceManager] already owns the record map, tokens, owner UIDs, and the pinned
 * signing baseline of every record. This subclass only supplies the launch command and the
 * deployment-specific identity checks; it deliberately keeps no second token/UID registry.
 */
open class NightzukuUserServiceManager(
    private val config: ServerConfig,
    private val appProcessExists: (String) -> Boolean = { java.io.File(it).exists() }
) : UserServiceManager() {

    @Volatile
    private var managerBaseline: PackageSignatureVerifier.Baseline? = null

    /** Pins the manager signing identity observed at server startup. */
    fun setManagerBaseline(baseline: PackageSignatureVerifier.Baseline?) {
        managerBaseline = baseline
    }

    override fun canReuseUserServiceRecord(record: UserServiceRecord, packageInfo: PackageInfo): Boolean {
        if (isAccessPaused() || record.pendingDestroy || record.isRemoved) {
            return false
        }
        return super.canReuseUserServiceRecord(record, packageInfo)
    }

    override fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean
    ): String {
        if (isAccessPaused()) {
            throw SecurityException("Client access is globally paused")
        }

        val baseline = recordSigningBaseline(record)
            ?: resolveHostBaseline(packageName, callingUid)
            ?: throw SecurityException(
                "Cannot verify package identity for UserService host $packageName (uid=$callingUid)"
            )

        val appProcess = ServerStarter.appProcessPath(use32Bits, appProcessExists)
        return ServerStarter.commandForUserService(
            appProcess = appProcess,
            managerApkPath = resolveVerifiedManagerApkPath(),
            token = token,
            packageName = packageName,
            classname = classname,
            processNameSuffix = processNameSuffix,
            callingUid = callingUid,
            debug = debug,
            expectedSigners = baseline.certDigests,
            starterClass = config.userServiceStarterClass
        )
    }

    /**
     * Re-validates the host package before the starter is allowed to claim the launch token.
     *
     * Uses the record's own pinned baseline through [recordSigningBaseline] instead of a parallel
     * map, so removing a record also removes its identity state.
     */
    override fun claimUserServiceLaunch(token: String?, pid: Int, expectedUid: Int): Boolean {
        if (token.isNullOrEmpty() || isAccessPaused()) {
            return false
        }
        val record = findRecordByToken(token) ?: return false
        val ownerUid = recordUid(record)
        val baseline = recordSigningBaseline(record)
        if (baseline != null && ownerUid >= 0) {
            val userId = UserHandleCompat.getUserId(ownerUid)
            val packageInfo = try {
                PackageManagerApis.getPackageInfoNoThrow(
                    baseline.packageName,
                    PackageSignatureVerifier.signatureLookupFlags(),
                    userId
                )
            } catch (t: Throwable) {
                LOGGER.w(t, "Unable to verify UserService host %s", baseline.packageName)
                null
            }
            val snapshot = packageInfo?.let {
                PackageSignatureVerifier.extractSnapshot(it, userId)
            }
            if (!baseline.matches(snapshot)) {
                LOGGER.w(
                    "Rejecting UserService launch claim for token %s: package %s failed identity verification",
                    token,
                    baseline.packageName
                )
                runCatching { record.removeSelf() }
                return false
            }
        }
        return super.claimUserServiceLaunch(token, pid, expectedUid)
    }

    private fun resolveHostBaseline(
        packageName: String,
        callingUid: Int
    ): PackageSignatureVerifier.Baseline? {
        val userId = UserHandleCompat.getUserId(callingUid)
        val packageInfo = try {
            PackageManagerApis.getPackageInfoNoThrow(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "Unable to resolve UserService host %s", packageName)
            null
        } ?: return null
        val snapshot = PackageSignatureVerifier.extractSnapshot(packageInfo, userId) ?: return null
        if (snapshot.appId != UserHandleCompat.getAppId(callingUid)) {
            return null
        }
        return PackageSignatureVerifier.Baseline.fromSnapshot(snapshot)
    }

    /**
     * Resolves the manager APK the UserService code is loaded from.
     *
     * When a signing baseline is pinned, the installed manager package must still match it. A
     * configured [ServerConfig.managerApkPath] must equal the installed source directory, so a
     * stale or tampered property cannot redirect `app_process` at an arbitrary APK.
     */
    private fun resolveVerifiedManagerApkPath(): String {
        val configured = config.managerApkPath
        val packageInfo = try {
            PackageManagerApis.getPackageInfoNoThrow(
                config.managerPackageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                0
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "Unable to resolve manager package %s", config.managerPackageName)
            null
        }

        if (packageInfo == null || packageInfo.applicationInfo == null) {
            return configured ?: throw SecurityException(
                "Manager package ${config.managerPackageName} is not installed and no APK path is configured"
            )
        }

        val expected = managerBaseline
        if (expected != null) {
            val snapshot = PackageSignatureVerifier.extractSnapshot(packageInfo, 0)
            if (!expected.matches(snapshot)) {
                throw SecurityException("Manager APK signature mismatch against server baseline")
            }
        }

        val installedPath = packageInfo.applicationInfo!!.sourceDir
            ?: throw SecurityException("Manager ApplicationInfo has no sourceDir")
        if (configured != null) {
            if (configured != installedPath) {
                throw SecurityException(
                    "Configured manager APK $configured does not match the installed package at $installedPath"
                )
            }
            return configured
        }
        return installedPath
    }

    companion object {
        private val LOGGER: Logger = Logger("NightzukuUserServiceManager")
    }
}
