package rikka.rish

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log

object RishConfig {

    private const val TAG = "RISHConfig"

    internal const val TRANSACTION_createHost: Int = 0
    internal const val TRANSACTION_setWindowSize: Int = 1
    internal const val TRANSACTION_getExitCode: Int = 2

    private val lock = Any()

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var interfaceToken: String? = null

    @Volatile
    private var transactionCodeStart: Int = 0

    @Volatile
    private var libraryPath: String? = null

    @Volatile
    private var libraryLoaded: Boolean = false

    @JvmStatic
    internal fun getBinder(): IBinder =
        checkNotNull(binder) { "RishConfig.init(binder, ...) was not called" }

    @JvmStatic
    internal fun getInterfaceToken(): String =
        checkNotNull(interfaceToken) { "RishConfig.init(...) was not called" }

    @JvmStatic
    internal fun getTransactionCode(code: Int): Int = transactionCodeStart + code

    @JvmStatic
    fun setLibraryPath(path: String?) {
        synchronized(lock) {
            libraryPath = path
        }
    }

    @JvmStatic
    fun isLibraryLoaded(): Boolean = libraryLoaded

    @JvmStatic
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun ensureLibraryLoaded() {
        if (libraryLoaded) return
        synchronized(lock) {
            if (libraryLoaded) return
            val path = libraryPath
            if (path.isNullOrEmpty()) {
                System.loadLibrary("rish")
            } else {
                System.load("$path/librish.so")
            }
            libraryLoaded = true
        }
    }

    private fun tryLoadLibraryOnInit() {
        try {
            ensureLibraryLoaded()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "librish.so not loaded during init; will retry lazily on shell invocation: ${e.message}")
        }
    }

    @JvmStatic
    fun init(interfaceToken: String, transactionCodeStart: Int) {
        Log.d(TAG, "init (server) $interfaceToken $transactionCodeStart")
        synchronized(lock) {
            RishConfig.interfaceToken = interfaceToken
            RishConfig.transactionCodeStart = transactionCodeStart
        }
        tryLoadLibraryOnInit()
    }

    @JvmStatic
    fun init(binder: IBinder, interfaceToken: String, transactionCodeStart: Int) {
        Log.d(TAG, "init (client) $binder $interfaceToken $transactionCodeStart")
        synchronized(lock) {
            RishConfig.binder = binder
            RishConfig.interfaceToken = interfaceToken
            RishConfig.transactionCodeStart = transactionCodeStart
        }
        tryLoadLibraryOnInit()
    }
}
