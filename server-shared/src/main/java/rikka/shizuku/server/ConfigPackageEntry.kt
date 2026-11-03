package rikka.shizuku.server

import rikka.shizuku.server.util.Logger

abstract class ConfigPackageEntry {

    abstract fun isAllowed(): Boolean

    abstract fun isDenied(): Boolean

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("ConfigPackageEntry")
    }
}

/**
 * Optional surface for config entries that store the raw permission/capability flag word.
 *
 * Declared separately from [ConfigPackageEntry] so that entries owned by embedding servers can
 * keep their own `flags` property (with or without the `override` modifier) without a signature
 * clash. Entries that do not implement this interface expose no explicit capability bits, which
 * [rikka.shizuku.server.security.TransactGuard] treats as "use the default capability set".
 */
interface ConfigFlagsEntry {

    /** Raw bits: [ConfigManager.MASK_PERMISSION] plus `NightProtocol.MASK_CAPABILITIES`. */
    fun capabilityFlags(): Int
}
