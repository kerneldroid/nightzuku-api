package rikka.shizuku.server.runtime

import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.ServerPolicy
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

/**
 * [ServerPolicy] for the standalone runtime, bound to one immutable [ServerConfig].
 *
 * The manager is identified by the app id of [ServerConfig.managerPackageName] in user 0. Every
 * lookup failure is reported as "not the manager" so an unavailable PackageManager can never be
 * interpreted as permission. The manager app id is deliberately not cached: reinstalling the
 * manager can change it, and a stale cache would either lock out or over-trust a process.
 */
class NightzukuServerPolicy(
    private val config: ServerConfig,
    private val appIdResolver: (String) -> Int? = { packageName ->
        PackageManagerApis.getApplicationInfoNoThrow(packageName, 0L, MANAGER_USER_ID)
            ?.let { UserHandleCompat.getAppId(it.uid) }
    },
    private val signatureVerifier: () -> Boolean = { true }
) : ServerPolicy {

    override val managerPackageName: String
        get() = config.managerPackageName

    override val apiPermission: String
        get() = config.apiPermission

    override val legacyPermission: String?
        get() = config.legacyPermission

    override val managerExitCode: Int
        get() = config.managerExitCode

    override fun isManager(callingUid: Int, callingPid: Int): Boolean {
        if (UserHandleCompat.getUserId(callingUid) != MANAGER_USER_ID) {
            return false
        }
        val managerAppId = resolveManagerAppId() ?: return false
        return UserHandleCompat.getAppId(callingUid) == managerAppId
    }

    /**
     * Re-verifies the installed manager package against the signing baseline captured at startup.
     *
     * The runtime installs a verifier that terminates on a positively detected identity change;
     * deployments that only need app-id matching keep the permissive default.
     */
    override fun verifyManagerSignature(): Boolean = signatureVerifier()

    private fun resolveManagerAppId(): Int? {
        val applicationInfoAppId = try {
            appIdResolver(managerPackageName)
        } catch (t: Throwable) {
            LOGGER.w(t, "Unable to resolve app id for manager package %s", managerPackageName)
            null
        } ?: run {
            LOGGER.w("Manager package %s is not installed in user %d", managerPackageName, MANAGER_USER_ID)
            return null
        }
        return applicationInfoAppId.takeIf { it > 0 }
    }

    companion object {
        const val MANAGER_USER_ID: Int = 0

        private val LOGGER: Logger = Logger("NightzukuServerPolicy")
    }
}
