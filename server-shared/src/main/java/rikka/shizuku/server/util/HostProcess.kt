/*
 * Adapted with credits from d4rken-org/porter-api (eu.darken.porter.core.HostProcess) under Apache-2.0 / MIT.
 */

package rikka.shizuku.server.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * Pins a spawned UserService host process by `(pid, startTime, uid)` using `/proc/<pid>/stat`
 * and `/proc/<pid>/status` so that a recycled PID is never signaled after the original host exits.
 */
class HostProcess internal constructor(
    val pid: Int,
    val startTime: Long,
    val uid: Int,
    private val procRoot: File = File("/proc")
) {

    fun isSameProcess(): Boolean {
        val now = capture(pid, procRoot) ?: return false
        return now.startTime == startTime && now.uid == uid
    }

    fun killIfSame(): Boolean {
        if (!isSameProcess()) return false
        return try {
            Os.kill(pid, OsConstants.SIGKILL)
            LOGGER.i("Killed user service host pid=%d", pid)
            true
        } catch (e: ErrnoException) {
            LOGGER.w(e, "Failed to kill pid=%d", pid)
            false
        }
    }

    fun verify(pid: Int): Boolean {
        val now = capture(pid, procRoot) ?: return false
        return now.startTime == startTime && now.uid == uid
    }

    fun sameAs(other: HostProcess): Boolean =
        pid == other.pid && startTime == other.startTime && uid == other.uid

    override fun toString(): String = "HostProcess(pid=$pid, startTime=$startTime, uid=$uid)"

    companion object {
        private val LOGGER = Logger("HostProcess")

        @JvmStatic
        @JvmOverloads
        fun capture(pid: Int, procRoot: File = File("/proc")): HostProcess? {
            val startTime = readStartTime(pid, procRoot)
            val uid = readUid(pid, procRoot)
            if (startTime <= 0L || uid < 0) return null
            return HostProcess(pid, startTime, uid, procRoot)
        }

        @JvmStatic
        fun capture(pid: Int, expectedUid: Int): HostProcess? {
            val host = capture(pid) ?: return null
            return host.takeIf { it.uid == expectedUid }
        }

        @JvmStatic
        @JvmOverloads
        fun readStartTime(pid: Int, procRoot: File = File("/proc")): Long {
            if (pid <= 0) return 0L
            return try {
                val stat = File(procRoot, "$pid/stat").readText()
                val closeParen = stat.lastIndexOf(')')
                if (closeParen < 0 || closeParen + 2 >= stat.length) return 0L
                val fields = stat.substring(closeParen + 2).trim().split(' ')
                fields.getOrNull(22 - 3)?.toLongOrNull() ?: 0L
            } catch (_: IOException) {
                0L
            } catch (_: IndexOutOfBoundsException) {
                0L
            }
        }

        @JvmStatic
        @JvmOverloads
        fun readUid(pid: Int, procRoot: File = File("/proc")): Int {
            if (pid <= 0) return -1
            return try {
                File(procRoot, "$pid/status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")
                    ?.trim()
                    ?.split('\t', ' ')
                    ?.firstOrNull()
                    ?.toIntOrNull()
                    ?: -1
            } catch (_: IOException) {
                -1
            }
        }
    }
}
