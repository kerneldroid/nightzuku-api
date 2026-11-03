package rikka.shizuku.server

import android.content.pm.PackageManager
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.PackageSignatureVerifier

/**
 * Platform abstraction used by [JsonConfigManager] to resolve package ownership and signing
 * identity. Tests supply a deterministic fake instead of talking to the real PackageManager.
 */
interface ClientIdentityResolver {

    /**
     * Returns the package names currently owned by [uid], or `null` when the lookup failed
     * transiently. An empty set means the lookup succeeded and the UID owns no packages.
     */
    fun packagesForUid(uid: Int): Set<String>?

    /**
     * Looks up [packageName] for Android user [userId] and classifies the result as present,
     * confirmed absent, or a transient lookup failure.
     */
    fun probePackage(packageName: String, userId: Int): PackageSignatureVerifier.ProbeResult
}

/**
 * Default resolver backed by the hidden PackageManager service.
 *
 * Transient Binder/PMS failures are reported as [PackageSignatureVerifier.ProbeResult.LookupError]
 * (and `null` package ownership) instead of being flattened into "not installed", so callers can
 * avoid purging grants on a flaky lookup.
 */
class AndroidClientIdentityResolver(
    private val signatureLookupFlags: Long = PackageSignatureVerifier.signatureLookupFlags()
) : ClientIdentityResolver {

    override fun packagesForUid(uid: Int): Set<String>? {
        return try {
            PackageManagerApis.getPackagesForUid(uid)?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            LOGGER.w(t, "getPackagesForUid(%d) failed", uid)
            null
        }
    }

    override fun probePackage(
        packageName: String,
        userId: Int
    ): PackageSignatureVerifier.ProbeResult {
        val packageInfo = try {
            PackageManagerApis.getPackageInfo(packageName, signatureLookupFlags, userId)
        } catch (e: PackageManager.NameNotFoundException) {
            return PackageSignatureVerifier.ProbeResult.Absent(e.message ?: "package is not installed")
        } catch (t: Throwable) {
            LOGGER.w(t, "getPackageInfo(%s, user %d) failed", packageName, userId)
            return PackageSignatureVerifier.ProbeResult.LookupError(t)
        }
        return PackageSignatureVerifier.evaluatePackageInfo(packageInfo, userId)
    }

    companion object {
        private val LOGGER = Logger("ClientIdentity")
    }
}
