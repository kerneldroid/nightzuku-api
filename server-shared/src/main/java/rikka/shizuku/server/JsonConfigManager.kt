package rikka.shizuku.server

import android.content.pm.PackageManager
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.util.PackageSignatureVerifier
import rikka.shizuku.server.util.UserHandleCompat

/**
 * Concrete [ConfigManager] backed by a versioned JSON file.
 *
 * The manager is parameterized by the file it persists to, the runtime permission it imports from
 * the system, and a [ClientIdentityResolver] used to validate package ownership and signing
 * certificates. All mutations are serialized on an internal monitor and every successful mutation
 * is written through [AtomicFile], so a crash during a write can never leave a truncated config.
 */
open class JsonConfigManager @JvmOverloads constructor(
    val configFile: File,
    val apiPermission: String,
    private val identityResolver: ClientIdentityResolver = AndroidClientIdentityResolver(),
    private val importSystemGrantsOnLoad: Boolean = true
) : ConfigManager(), ConfigManager.ClientAccessVerifier {

    /** On-disk schema. Unknown or older versions start from an empty config. */
    internal class Store(
        @SerializedName("version")
        var version: Int = SCHEMA_VERSION,
        @SerializedName("packages")
        var packages: MutableList<JsonConfigPackageEntry>? = ArrayList(),
        @SerializedName("accessPaused")
        var accessPaused: Boolean = false
    )

    private val lock = Any()
    private val atomicFile = AtomicFile(configFile)
    private var config: Store = Store()

    init {
        synchronized(lock) {
            config = loadLocked()
            var changed = repairLocked()
            if (importSystemGrantsOnLoad) {
                changed = importSystemRuntimeGrantsLocked() || changed
            }
            if (changed) {
                persistLocked()
            }
        }
    }

    override fun find(uid: Int): ConfigPackageEntry? = synchronized(lock) { findLocked(uid) }

    override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) {
        synchronized(lock) {
            if (applyUpdateLocked(uid, packages, mask, values)) {
                persistLocked()
            }
        }
    }

    override fun remove(uid: Int) {
        synchronized(lock) {
            val entry = findLocked(uid) ?: return
            config.packages?.remove(entry)
            persistLocked()
        }
    }

    /**
     * Verifies that the grant stored for [uid] belongs to the installation of [packageName]
     * currently attaching. Automatically purges the config entry if the UID no longer owns a
     * recorded package or the package was replaced with a different signing certificate.
     */
    override fun verifyClientIdentity(uid: Int, packageName: String): Boolean {
        val entry: JsonConfigPackageEntry
        val recordedPackages: List<String>
        synchronized(lock) {
            entry = findLocked(uid) ?: return false
            recordedPackages = entry.packages?.toList().orEmpty()
        }
        if (recordedPackages.isEmpty() || packageName !in recordedPackages) {
            LOGGER.w(
                "Rejecting client uid %d (%s): package is not part of its recorded grant",
                uid,
                packageName
            )
            return false
        }

        val ownedPackages = identityResolver.packagesForUid(uid)
        if (ownedPackages == null) {
            LOGGER.w("Rejecting client uid %d: unable to resolve its packages", uid)
            return false
        }
        if (ownedPackages.none { it in recordedPackages }) {
            synchronized(lock) {
                if (findLocked(uid) === entry) {
                    config.packages?.remove(entry)
                    LOGGER.w(
                        "Purging config for uid %d: packages %s no longer belong to uid",
                        uid,
                        recordedPackages
                    )
                    persistLocked()
                }
            }
            return false
        }

        val userId = UserHandleCompat.getUserId(uid)
        val probe = identityResolver.probePackage(packageName, userId)
        val snapshot = when (probe) {
            is PackageSignatureVerifier.ProbeResult.Present -> probe.snapshot
            is PackageSignatureVerifier.ProbeResult.Absent -> {
                LOGGER.w("Client uid %d (%s) is no longer installed", uid, packageName)
                return false
            }
            is PackageSignatureVerifier.ProbeResult.LookupError -> {
                LOGGER.w(probe.cause, "Unable to verify client uid %d (%s)", uid, packageName)
                return false
            }
        }

        synchronized(lock) {
            if (findLocked(uid) !== entry) return false
            val existingSigners = entry.signers
            if (existingSigners.isNullOrEmpty()) {
                entry.signers = snapshot.certDigests.toMutableList()
                persistLocked()
                return true
            }
            val baseline = PackageSignatureVerifier.Baseline(
                packageName,
                UserHandleCompat.getAppId(uid),
                existingSigners.toSet()
            )
            if (baseline.matches(snapshot)) {
                return true
            }
            config.packages?.remove(entry)
            LOGGER.w(
                "Purging config for uid %d (%s): signing certificate mismatch",
                uid,
                packageName
            )
            persistLocked()
            return false
        }
    }

    override fun recordConnection(uid: Int) {
        recordConnection(uid, System.currentTimeMillis())
    }

    /** Records a successful connection at [timestampMs], keeping the newest observed value. */
    fun recordConnection(uid: Int, timestampMs: Long) {
        synchronized(lock) {
            val entry = findLocked(uid) ?: return
            if (timestampMs > entry.lastConnectedAt) {
                entry.lastConnectedAt = timestampMs
                persistLocked()
            }
        }
    }

    fun allowedUids(): List<Int> {
        synchronized(lock) {
            val result = ArrayList<Int>()
            for (entry in config.packages.orEmpty()) {
                if (entry.isAllowed()) {
                    result.add(entry.uid)
                }
            }
            return result
        }
    }

    override fun isAccessPaused(): Boolean = synchronized(lock) { config.accessPaused }

    fun setAccessPaused(paused: Boolean) {
        synchronized(lock) {
            if (config.accessPaused == paused) return
            config.accessPaused = paused
            persistLocked()
        }
    }

    /**
     * Imports pre-existing runtime grants of [apiPermission] for clients that have no explicit
     * decision stored yet. Returns `true` if the config was modified.
     */
    fun importSystemRuntimeGrants(): Boolean {
        synchronized(lock) {
            val changed = importSystemRuntimeGrantsLocked()
            if (changed) {
                persistLocked()
            }
            return changed
        }
    }

    private fun findLocked(uid: Int): JsonConfigPackageEntry? {
        for (entry in config.packages.orEmpty()) {
            if (entry.uid == uid) {
                return entry
            }
        }
        return null
    }

    /**
     * Mutates the in-memory config and returns whether anything changed. The caller is responsible
     * for persisting the result.
     */
    private fun applyUpdateLocked(
        uid: Int,
        packages: MutableList<String>?,
        mask: Int,
        values: Int
    ): Boolean {
        val found = findLocked(uid)
        val entry: JsonConfigPackageEntry
        var changed = false
        if (found == null) {
            entry = JsonConfigPackageEntry(uid, mask and values)
            if (config.packages == null) {
                config.packages = ArrayList()
            }
            config.packages!!.add(entry)
            changed = true
        } else {
            val newValue = (found.flags and mask.inv()) or (mask and values)
            if (newValue != found.flags) {
                found.flags = newValue
                changed = true
            }
            entry = found
        }

        if (packages != null) {
            val merged = LinkedHashSet(entry.packages.orEmpty())
            for (packageName in packages) {
                if (packageName.isNotEmpty()) {
                    merged.add(packageName)
                }
            }
            if (merged.size != (entry.packages?.size ?: 0)) {
                entry.packages = merged.toMutableList()
                changed = true
            }
            if (entry.signers.isNullOrEmpty()) {
                val resolved = resolveSignersForUid(uid, merged)
                if (!resolved.isNullOrEmpty()) {
                    entry.signers = resolved.toMutableList()
                    changed = true
                }
            }
        } else if (changed && entry.packages == null) {
            entry.packages = ArrayList()
        }
        return changed
    }

    private fun resolveSignersForUid(uid: Int, packages: Collection<String>): Set<String>? {
        val userId = UserHandleCompat.getUserId(uid)
        val expectedAppId = UserHandleCompat.getAppId(uid)
        var lookupFailed = false
        for (packageName in packages) {
            when (val probe = identityResolver.probePackage(packageName, userId)) {
                is PackageSignatureVerifier.ProbeResult.Present -> {
                    val snapshot = probe.snapshot
                    if (snapshot.appId == expectedAppId && snapshot.certDigests.isNotEmpty()) {
                        return snapshot.certDigests
                    }
                }
                is PackageSignatureVerifier.ProbeResult.Absent -> Unit
                is PackageSignatureVerifier.ProbeResult.LookupError -> lookupFailed = true
            }
        }
        if (lookupFailed) {
            LOGGER.w("Unable to resolve signing certificates for uid %d", uid)
        }
        return null
    }

    /**
     * Returns `true` when [packageName] matches [signers] (including key rotation lineage),
     * `false` when it is positively signed by a different authority or is absent, and `null`
     * when the lookup failed transiently.
     */
    private fun verifyPackageAgainstSigners(
        uid: Int,
        packageName: String,
        signers: Set<String>
    ): Boolean? {
        val userId = UserHandleCompat.getUserId(uid)
        val expectedAppId = UserHandleCompat.getAppId(uid)
        return when (val probe = identityResolver.probePackage(packageName, userId)) {
            is PackageSignatureVerifier.ProbeResult.Present ->
                PackageSignatureVerifier.Baseline(packageName, expectedAppId, signers)
                    .matches(probe.snapshot)
            is PackageSignatureVerifier.ProbeResult.Absent -> false
            is PackageSignatureVerifier.ProbeResult.LookupError -> null
        }
    }

    /**
     * Load-time repair: drops UIDs that no longer own any package, removes stale package names,
     * deduplicates entries, backfills missing signer digests, and drops grants whose signing
     * certificate changed. Transient PackageManager failures never purge a grant.
     */
    private fun repairLocked(): Boolean {
        if (config.packages == null) {
            config.packages = ArrayList()
            return true
        }
        var changed = false
        val entries = ArrayList(config.packages!!)
        for (entry in entries) {
            val declared = entry.packages
            val normalized = LinkedHashSet<String>()
            if (declared != null) {
                for (packageName in declared) {
                    if (packageName.isNotEmpty()) {
                        normalized.add(packageName)
                    }
                }
            }
            if (normalized.size != (declared?.size ?: 0)) {
                LOGGER.w(
                    "entry.packages for uid %d has duplicates or blanks, shrunk %d -> %d",
                    entry.uid,
                    declared?.size ?: 0,
                    normalized.size
                )
                entry.packages = normalized.toMutableList()
                changed = true
            }

            val owned = identityResolver.packagesForUid(entry.uid)
            if (owned == null) {
                LOGGER.w(
                    "Skipping config validation for uid %d: package lookup failed",
                    entry.uid
                )
                continue
            }
            if (owned.isEmpty()) {
                LOGGER.i("Remove config for uid %d since it has gone", entry.uid)
                config.packages!!.remove(entry)
                changed = true
                continue
            }

            val retained = normalized.filter { it in owned }
            if (retained.isEmpty()) {
                LOGGER.i("Remove config for uid %d since it no longer owns any recorded package", entry.uid)
                config.packages!!.remove(entry)
                changed = true
                continue
            }
            if (retained.size != normalized.size) {
                LOGGER.i(
                    "Remove stale packages for uid %d: %s",
                    entry.uid,
                    (normalized - retained.toSet()).joinToString(", ")
                )
                entry.packages = retained.toMutableList()
                changed = true
            }

            val signers = entry.signers
            if (signers.isNullOrEmpty()) {
                val resolved = resolveSignersForUid(entry.uid, retained)
                if (!resolved.isNullOrEmpty()) {
                    entry.signers = resolved.toMutableList()
                    changed = true
                }
            } else {
                val results = retained.map {
                    verifyPackageAgainstSigners(entry.uid, it, signers.toSet())
                }
                if (results.isNotEmpty() && results.all { it == false }) {
                    LOGGER.w(
                        "Remove config for uid %d since signing certificate changed",
                        entry.uid
                    )
                    config.packages!!.remove(entry)
                    changed = true
                }
            }
        }
        return changed
    }

    private fun importSystemRuntimeGrantsLocked(): Boolean {
        var changed = false
        for (userId in enumerateUserIds()) {
            val installed = try {
                PackageManagerApis.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS.toLong(),
                    userId
                )
            } catch (t: Throwable) {
                LOGGER.w(t, "getInstalledPackages(user %d)", userId)
                continue
            }
            for (packageInfo in installed) {
                val applicationInfo = packageInfo.applicationInfo ?: continue
                val requestedPermissions = packageInfo.requestedPermissions ?: continue
                if (!requestedPermissions.contains(apiPermission)) {
                    continue
                }
                val uid = applicationInfo.uid
                if (findLocked(uid) != null) {
                    continue
                }
                val granted = try {
                    PermissionManagerApis.checkPermission(apiPermission, uid) ==
                        PackageManager.PERMISSION_GRANTED
                } catch (t: Throwable) {
                    LOGGER.w(t, "checkPermission(%s, uid %d)", apiPermission, uid)
                    continue
                }
                if (!granted) {
                    continue
                }
                if (applyUpdateLocked(
                        uid,
                        mutableListOf(packageInfo.packageName),
                        ConfigManager.MASK_PERMISSION,
                        ConfigManager.FLAG_ALLOWED
                    )
                ) {
                    changed = true
                    LOGGER.i("Imported existing runtime grant for uid %d (%s)", uid, packageInfo.packageName)
                }
            }
        }
        return changed
    }

    private fun enumerateUserIds(): List<Int> {
        val ids = try {
            UserManagerApis.getUserIdsNoThrow()
        } catch (t: Throwable) {
            LOGGER.w(t, "Unable to enumerate users; importing grants for the current user only")
            null
        }
        if (!ids.isNullOrEmpty()) {
            return ids.toList()
        }
        return listOf(UserHandleCompat.getUserId(Process.myUid()))
    }

    private fun loadLocked(): Store {
        val stream: FileInputStream = try {
            atomicFile.openRead()
        } catch (_: FileNotFoundException) {
            LOGGER.i("No existing config file %s; starting empty", configFile)
            return Store()
        }
        return try {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                parseReader(reader)
            }
        } catch (t: Throwable) {
            LOGGER.w(t, "load config")
            Store()
        }
    }

    private fun persistLocked() {
        if (!writeLocked(config)) {
            LOGGER.w("Failed to persist config to %s", configFile)
        }
    }

    private fun writeLocked(store: Store): Boolean {
        configFile.parentFile?.mkdirs()
        val stream: FileOutputStream = try {
            atomicFile.startWrite()
        } catch (e: IOException) {
            LOGGER.w(e, "failed to start config write")
            return false
        }
        return try {
            stream.write(toJson(store).toByteArray(StandardCharsets.UTF_8))

            // Ensure the shell user always owns the replacement inode even when the server was
            // previously started as root (uid 0).
            if (Process.myUid() == 0) {
                runCatching { Os.fchown(stream.fd, SHELL_UID, SHELL_UID) }
            }
            runCatching { Os.fchmod(stream.fd, MODE_0600) }

            atomicFile.finishWrite(stream)
            LOGGER.v("config saved")
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "can't save %s, restoring backup", configFile)
            atomicFile.failWrite(stream)
            false
        }
    }

    companion object {
        internal const val SCHEMA_VERSION = 3
        private const val SHELL_UID = 2000
        private const val MODE_0600 = 0x180

        private val GSON_IN: Gson = GsonBuilder().create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(SCHEMA_VERSION.toDouble())
            .create()

        @JvmStatic
        internal fun parseReader(reader: Reader): Store {
            val element = JsonParser.parseReader(reader)
            val obj = element as? JsonObject ?: return Store()
            val version = obj.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
            if (version == null || version != SCHEMA_VERSION) {
                LOGGER.w(
                    "Unsupported or missing config version %s (expected %d); starting empty",
                    version?.toString() ?: "null",
                    SCHEMA_VERSION
                )
                return Store()
            }
            return (GSON_IN.fromJson(element, Store::class.java) ?: Store()).apply {
                if (packages == null) {
                    packages = ArrayList()
                }
            }
        }

        @JvmStatic
        internal fun toJson(store: Store): String = GSON_OUT.toJson(store)
    }
}
