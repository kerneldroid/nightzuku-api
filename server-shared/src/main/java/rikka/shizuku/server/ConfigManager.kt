package rikka.shizuku.server

import rikka.shizuku.server.util.Logger

abstract class ConfigManager {

    abstract fun find(uid: Int): ConfigPackageEntry?

    abstract fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int)

    abstract fun remove(uid: Int)

    companion object {
        @JvmField
        val LOGGER: Logger = Logger("ConfigManager")

        const val FLAG_ALLOWED: Int = 1 shl 1
        const val FLAG_DENIED: Int = 1 shl 2
        const val MASK_PERMISSION: Int = FLAG_ALLOWED or FLAG_DENIED
    }
}
