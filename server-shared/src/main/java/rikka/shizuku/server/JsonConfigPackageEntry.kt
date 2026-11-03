package rikka.shizuku.server

import com.google.gson.annotations.SerializedName
import kerneldroid.nightzuku.api.NightProtocol

/**
 * Persistent permission record for a client UID, serialized inside [JsonConfigManager]'s JSON file.
 *
 * [uid] is the full Linux UID (Android user id * 100000 + app id), [flags] carries both the
 * permission decision ([ConfigManager.MASK_PERMISSION]) and the fine-grained capability bits
 * ([NightProtocol.MASK_CAPABILITIES]), and [signers] pins the SHA-256 digests of the signing
 * certificates that were observed when the decision was recorded.
 */
class JsonConfigPackageEntry(
    @SerializedName("uid")
    var uid: Int = 0,
    @SerializedName("flags")
    var flags: Int = 0
) : ConfigPackageEntry(), ConfigFlagsEntry {

    @SerializedName("packages")
    var packages: MutableList<String>? = ArrayList()

    /**
     * SHA-256 digests of the signing certificates of the package installation this decision
     * was recorded for. Backfilled automatically on load/attach if absent.
     */
    @SerializedName("signers")
    var signers: MutableList<String>? = null

    /**
     * Epoch timestamp (ms) when this UID last attached to the server.
     */
    @SerializedName("lastConnectedAt")
    var lastConnectedAt: Long = 0L

    override fun isAllowed(): Boolean = (flags and ConfigManager.FLAG_ALLOWED) != 0

    override fun isDenied(): Boolean = (flags and ConfigManager.FLAG_DENIED) != 0

    override fun capabilityFlags(): Int = flags

    /** Capability bits currently granted to this UID. */
    val capabilities: Int
        get() = flags and NightProtocol.MASK_CAPABILITIES

    /** Returns whether every bit in [capability] is currently granted to this UID. */
    fun hasCapability(capability: Int): Boolean =
        (capabilities and capability) == capability

    /** Replaces the capability bits without touching the permission decision flags. */
    fun setCapabilities(capability: Int) {
        flags = (flags and NightProtocol.MASK_CAPABILITIES.inv()) or
            (capability and NightProtocol.MASK_CAPABILITIES)
    }
}
