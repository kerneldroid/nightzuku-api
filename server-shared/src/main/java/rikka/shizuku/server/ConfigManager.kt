package rikka.shizuku.server

import rikka.shizuku.server.util.Logger

abstract class ConfigManager {

    abstract fun find(uid: Int): ConfigPackageEntry?

    abstract fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int)

    abstract fun remove(uid: Int)

    /**
     * Optional access-control surface implemented by config managers that can verify a client
     * installation and persist connection metadata (see [JsonConfigManager]).
     *
     * [ClientManager] upgrades to this interface when available; custom [ConfigManager]
     * subclasses keep their existing behaviour without implementing it.
     */
    interface ClientAccessVerifier {

        /** Whether all client access is administratively paused. */
        fun isAccessPaused(): Boolean

        /**
         * Verifies that the stored grant for [uid] was issued to the installation of
         * [packageName] that is currently attached.
         */
        fun verifyClientIdentity(uid: Int, packageName: String): Boolean

        /** Records a successful client connection for [uid]. */
        fun recordConnection(uid: Int)
    }

    companion object {
        @JvmField
        val LOGGER: Logger = Logger("ConfigManager")

        const val FLAG_ALLOWED: Int = 1 shl 1
        const val FLAG_DENIED: Int = 1 shl 2
        const val MASK_PERMISSION: Int = FLAG_ALLOWED or FLAG_DENIED
    }
}

/**
 * Whether client access is globally paused.
 *
 * [ConfigManager] implementations that support the access-control surface implement
 * [ConfigManager.ClientAccessVerifier]; for every other manager access is never considered paused.
 */
fun ConfigManager.isAccessPaused(): Boolean =
    (this as? ConfigManager.ClientAccessVerifier)?.isAccessPaused() ?: false
