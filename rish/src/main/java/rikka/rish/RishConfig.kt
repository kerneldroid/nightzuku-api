package rikka.rish

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log

object RishConfig {

    private const val TAG = "RISHConfig"

    internal const val TRANSACTION_createHost: Int = 0
    internal const val TRANSACTION_setWindowSize: Int = 1
    internal const val TRANSACTION_getExitCode: Int = 2

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var interfaceToken: String? = null

    @Volatile
    private var transactionCodeStart: Int = 0

    @Volatile
    private var libraryPath: String? = null

    @JvmStatic
    internal fun getBinder(): IBinder? = binder

    @JvmStatic
    internal fun getInterfaceToken(): String? = interfaceToken

    @JvmStatic
    internal fun getTransactionCode(code: Int): Int = transactionCodeStart + code

    @JvmStatic
    fun setLibraryPath(path: String?) {
        libraryPath = path
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadLibrary() {
        val path = libraryPath
        if (path == null) {
            System.loadLibrary("rish")
        } else {
            System.load("$path/librish.so")
        }
    }

    @JvmStatic
    fun init(interfaceToken: String, transactionCodeStart: Int) {
        Log.d(TAG, "init (server) $interfaceToken $transactionCodeStart")
        RishConfig.interfaceToken = interfaceToken
        RishConfig.transactionCodeStart = transactionCodeStart
        loadLibrary()
    }

    @JvmStatic
    fun init(binder: IBinder, interfaceToken: String, transactionCodeStart: Int) {
        Log.d(TAG, "init (client) $binder $interfaceToken $transactionCodeStart")
        RishConfig.binder = binder
        RishConfig.interfaceToken = interfaceToken
        RishConfig.transactionCodeStart = transactionCodeStart
        loadLibrary()
    }
}
