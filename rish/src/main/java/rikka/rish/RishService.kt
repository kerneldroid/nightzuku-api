/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (PorshService, PorshHostRegistry, EnvPolicy) under Apache-2.0 / MIT:
 * - ConcurrentHashMap host registry with stale exited host reaping (UNCOLLECTED_MAX_AGE_MILLIS)
 * - Bounded exit-code await with conditional removal so replacement hosts of the same PID survive
 */

package rikka.rish

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

abstract class RishService {

    private fun resolveEnv(env: Array<String>?): Array<String>? {
        if (env == null) return null
        var nightzukuPref: Boolean? = null
        var rishPref: Boolean? = null

        for (entry in env) {
            when (entry) {
                "NIGHTZUKU_PRESERVE_ENV=1" -> if (nightzukuPref == null) nightzukuPref = true
                "NIGHTZUKU_PRESERVE_ENV=0" -> if (nightzukuPref == null) nightzukuPref = false
                "RISH_PRESERVE_ENV=1" -> if (rishPref == null) rishPref = true
                "RISH_PRESERVE_ENV=0" -> if (rishPref == null) rishPref = false
            }
        }

        val allowEnv = nightzukuPref ?: rishPref ?: IS_ROOT
        return if (allowEnv) env else null
    }

    private fun reapUncollectedHosts() {
        val now = SystemClock.elapsedRealtime()
        for ((pid, host) in HOSTS) {
            if (!host.hasExited()) continue
            if (now - host.exitedAtMillis <= UNCOLLECTED_MAX_AGE_MILLIS) continue
            if (HOSTS.remove(pid, host)) {
                Log.d(TAG, "Reaped uncollected host of pid=$pid")
            }
        }
    }

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
        reapUncollectedHosts()

        val effectiveEnv = resolveEnv(env)
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
        val code = host.awaitExitCode(EXIT_CODE_TIMEOUT_MILLIS)
        if (!host.hasExited()) {
            return code
        }
        HOSTS.remove(callingPid, host)
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

            data.enforceInterface(RishConfig.getInterfaceToken())
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

            data.enforceInterface(RishConfig.getInterfaceToken())
            val size = data.readLong()
            setWindowSize(size)
            reply?.writeNoException()
            return true
        } else if (code == RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode)) {
            Log.d(TAG, "TRANSACTION_getExitCode")
            enforceCallingPermission("getExitCode")

            data.enforceInterface(RishConfig.getInterfaceToken())
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
        private const val EXIT_CODE_TIMEOUT_MILLIS = 5_000L
        private const val UNCOLLECTED_MAX_AGE_MILLIS = 60_000L
        private val HOSTS = ConcurrentHashMap<Int, RishHost>()
        private val IS_ROOT: Boolean = Os.getuid() == 0
    }
}
