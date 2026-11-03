package rikka.shizuku.server

import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

/**
 * Deployment-specific identity policy for the privileged server half.
 *
 * The generic [Service] never hard-codes a manager package name, a runtime permission or an exit
 * code. Embedding servers supply those values through this interface; when no policy is installed
 * the manager-only transactions fail closed.
 */
interface ServerPolicy {

    /** Package name of the manager application that owns the server. */
    val managerPackageName: String

    /** Runtime permission that clients declare to be considered for access. */
    val apiPermission: String

    /** Legacy Shizuku runtime permission, when the deployment still accepts it. */
    val legacyPermission: String?

    /** Process exit code used by [Service.exit] when the manager tears the server down. */
    val managerExitCode: Int

    /** Whether the caller is the manager app of the current user. */
    fun isManager(callingUid: Int, callingPid: Int): Boolean

    /**
     * Re-verifies that the installed manager package is still the one the server started with.
     *
     * Implementations that pin a signing baseline should override this and return `false`
     * (or terminate the server) when the installed package no longer matches.
     */
    fun verifyManagerSignature(): Boolean = true
}

/**
 * [ServerPolicy] that resolves the manager app id from the package manager.
 *
 * The manager is identified by the app id of [managerPackageName] in user 0. Every lookup
 * failure is treated as "not the manager" (fail closed); a transient failure is retried on the
 * next call instead of being cached.
 */
class DefaultServerPolicy(
    override val managerPackageName: String,
    override val apiPermission: String,
    override val legacyPermission: String? = null,
    override val managerExitCode: Int = MANAGER_APP_NOT_FOUND
) : ServerPolicy {

    @Volatile
    private var resolvedManagerAppId: Int? = null

    override fun isManager(callingUid: Int, callingPid: Int): Boolean {
        if (UserHandleCompat.getUserId(callingUid) != 0) {
            return false
        }
        val managerAppId = resolvedManagerAppId ?: resolveManagerAppId()
        if (managerAppId == null) {
            return false
        }
        resolvedManagerAppId = managerAppId
        return UserHandleCompat.getAppId(callingUid) == managerAppId
    }

    private fun resolveManagerAppId(): Int? {
        val applicationInfo = try {
            PackageManagerApis.getApplicationInfoNoThrow(managerPackageName, 0L, 0)
        } catch (t: Throwable) {
            LOGGER.w(t, "Unable to look up manager package %s", managerPackageName)
            null
        } ?: run {
            LOGGER.w("Manager package %s is not installed in user 0", managerPackageName)
            return null
        }
        return UserHandleCompat.getAppId(applicationInfo.uid)
    }

    companion object {
        /** Conventional exit code for "manager app not found". */
        const val MANAGER_APP_NOT_FOUND: Int = 50

        private val LOGGER: Logger = Logger("DefaultServerPolicy")
    }
}
