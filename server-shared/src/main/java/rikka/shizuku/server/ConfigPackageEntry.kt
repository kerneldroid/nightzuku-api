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
