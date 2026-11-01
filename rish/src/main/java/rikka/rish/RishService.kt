package rikka.rish

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.util.Collections
import java.util.HashMap

abstract class RishService {

    private fun createHost(
        args: Array<String>?,
        env: Array<String>?,
        dir: String?,
        tty: Byte,
        stdin: ParcelFileDescriptor?,
        stdout: ParcelFileDescriptor?,
        stderr: ParcelFileDescriptor?
    ) {
        val callingPid = Binder.getCallingPid()

        var allowEnv = IS_ROOT
        if (env != null) {
            for (e in env) {
                if ("RISH_PRESERVE_ENV=1" == e) {
                    allowEnv = true
                    break
                } else if ("RISH_PRESERVE_ENV=0" == e) {
                    allowEnv = false
                    break
                }
            }
        }
        val effectiveEnv = if (allowEnv) env else null

        val host = RishHost(args, effectiveEnv, dir, tty, stdin, stdout, stderr)
        host.start()
        Log.d(TAG, "Forked ${host.pid}")

        HOSTS[callingPid] = host
    }

    private fun setWindowSize(size: Long) {
        val callingPid = Binder.getCallingPid()
        val host = HOSTS[callingPid]
        if (host == null) {
            Log.d(TAG, "Not existing host created by $callingPid")
            return
        }
        host.setWindowSize(size)
    }

    private fun getExitCode(): Int {
        val callingPid = Binder.getCallingPid()
        val host = HOSTS[callingPid]
        if (host == null) {
            Log.d(TAG, "Not existing host created by $callingPid")
            return -1
        }
        return host.exitCode
    }

    abstract fun enforceCallingPermission(func: String)

    open fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_createHost)) {
            Log.d(TAG, "TRANSACTION_createHost")
            enforceCallingPermission("createHost")

            if (reply == null || (flags and IBinder.FLAG_ONEWAY) != 0) {
                return true
            }

            data.enforceInterface(RishConfig.getInterfaceToken()!!)
            val tty = data.readByte()
            val stdin = data.readFileDescriptor()
            val stdout = data.readFileDescriptor()
            val stderr = if ((tty.toInt() and RishConstants.ATTY_ERR) == 0) {
                data.readFileDescriptor()
            } else {
                null
            }
            val args = data.createStringArray()
            val env = data.createStringArray()
            val dir = data.readString()
            createHost(args, env, dir, tty, stdin, stdout, stderr)
            reply.writeNoException()
            return true
        } else if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_setWindowSize)) {
            Log.d(TAG, "TRANSACTION_setWindowSize")
            enforceCallingPermission("setWindowSize")

            data.enforceInterface(RishConfig.getInterfaceToken()!!)
            val size = data.readLong()
            setWindowSize(size)
            reply?.writeNoException()
            return true
        } else if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode)) {
            Log.d(TAG, "TRANSACTION_getExitCode")
            enforceCallingPermission("getExitCode")

            data.enforceInterface(RishConfig.getInterfaceToken()!!)
            val exitCode = getExitCode()
            if (reply != null) {
                reply.writeNoException()
                reply.writeInt(exitCode)
            }
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "RishService"
        private val HOSTS: MutableMap<Int, RishHost> = Collections.synchronizedMap(HashMap())
        private val IS_ROOT: Boolean = Os.getuid() == 0
    }
}
