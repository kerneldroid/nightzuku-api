package rikka.shizuku.server.util

import android.os.Build

object AbiUtil {

    private val has32BitCached: Boolean by lazy {
        Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
    }

    @JvmStatic
    fun has32Bit(): Boolean = has32BitCached
}
