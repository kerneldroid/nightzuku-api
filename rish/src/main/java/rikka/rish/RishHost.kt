/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (PorshHost) under Apache-2.0 / MIT:
 * - Synchronized ptmx lifecycle (`ptmxLock` + `closePtmx()`) so window resizes never hit a closed/reused fd
 * - CountDownLatch exit synchronization (`awaitExitCode`, `hasExited`, `exitedAtMillis`)
 */

package rikka.rish

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class RishHost(
    private val args: Array<String>?,
    private val env: Array<String>?,
    private val dir: String?,
    private val tty: Byte,
    stdin: ParcelFileDescriptor?,
    stdout: ParcelFileDescriptor?,
    stderr: ParcelFileDescriptor?
) {

    data class SpawnedProcessGroup(
        val pid: Int,
        val stdinPfd: ParcelFileDescriptor,
        val stdoutPfd: ParcelFileDescriptor,
        val stderrPfd: ParcelFileDescriptor
    )

    private val stdinFd: Int = detachFd(stdin)
    private val stdoutFd: Int = detachFd(stdout)
    private val stderrFd: Int = detachFd(stderr)
    private val exited = CountDownLatch(1)
    private val ptmxLock = Any()

    @Volatile
    var pid: Int = 0
        private set

    private var ptmx: Int = -1

    @Volatile
    var exitCode: Int = Int.MAX_VALUE
        private set

    @Volatile
    internal var exitedAtMillis: Long = 0L
        private set

    open fun start() {
        Log.d(TAG, "start")
        RishConfig.ensureLibraryLoaded()

        val argBlock = createCBytesForStringArray(args)
        val envBlock = createCBytesForStringArray(env)
        val dirBlock = createCBytesForString(dir)

        val result = start(
            argBlock,
            args?.size ?: 0,
            envBlock,
            env?.size ?: -1,
            dirBlock,
            tty,
            stdinFd,
            stdoutFd,
            stderrFd
        )

        pid = result[0]
        synchronized(ptmxLock) {
            ptmx = result[1]
        }

        val waitThread = Thread({ onExited(waitFor(pid)) }, "rish-wait-$pid")
        waitThread.isDaemon = true
        waitThread.start()
    }

    internal fun onExited(code: Int) {
        closePtmx()
        exitCode = code
        exitedAtMillis = SystemClock.elapsedRealtime()
        exited.countDown()
    }

    internal fun hasExited(): Boolean = exited.count == 0L

    internal fun awaitExitCode(timeoutMillis: Long): Int {
        try {
            if (!exited.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for $pid to exit")
                return -1
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
        }
        return exitCode
    }

    open fun setWindowSize(size: Long) {
        Log.d(TAG, "setWindowSize")
        synchronized(ptmxLock) {
            if (ptmx < 0) return
            RishConfig.ensureLibraryLoaded()
            setWindowSize(ptmx, size)
        }
    }

    private fun closePtmx() {
        synchronized(ptmxLock) {
            if (ptmx < 0) return
            try {
                ParcelFileDescriptor.adoptFd(ptmx).close()
            } catch (e: IOException) {
                Log.w(TAG, "close ptmx", e)
            }
            ptmx = -1
        }
    }

    companion object {
        private const val TAG = "RishHost"

        private fun createCBytesForStringArray(array: Array<String>?): ByteArray? {
            if (array == null) return null

            val bytes = Array(array.size) { i -> array[i].toByteArray(StandardCharsets.UTF_8) }
            var count = bytes.size
            for (b in bytes) {
                count += b.size
            }
            val block = ByteArray(count)
            var offset = 0
            for (arg in bytes) {
                System.arraycopy(arg, 0, block, offset, arg.size)
                offset += arg.size + 1
            }
            return block
        }

        private fun createCBytesForString(s: String?): ByteArray? {
            if (s == null) return null
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            val result = ByteArray(bytes.size + 1)
            System.arraycopy(bytes, 0, result, 0, bytes.size)
            result[result.size - 1] = 0
            return result
        }

        private fun detachFd(pfd: ParcelFileDescriptor?): Int {
            return pfd?.detachFd() ?: -1
        }

        /**
         * Spawns a command in a new session and process group (`setsid`, so `pgid == pid`)
         * via `librish.so` (`nativeSpawnGroup`) and wraps the returned pipe descriptors.
         */
        @JvmStatic
        fun spawnProcessGroup(
            cmd: Array<String>,
            env: Array<String>?,
            dir: String?
        ): SpawnedProcessGroup {
            require(cmd.isNotEmpty()) { "Command array must not be empty" }
            RishConfig.ensureLibraryLoaded()
            val fds = nativeSpawnGroup(cmd, env, dir)
            check(fds.size >= 4 && fds[0] > 0) { "nativeSpawnGroup returned invalid result" }
            return SpawnedProcessGroup(
                pid = fds[0],
                stdinPfd = ParcelFileDescriptor.adoptFd(fds[1]),
                stdoutPfd = ParcelFileDescriptor.adoptFd(fds[2]),
                stderrPfd = ParcelFileDescriptor.adoptFd(fds[3])
            )
        }

        /**
         * Attempts to spawn a command in its own `setsid` process group via `librish.so`,
         * returning `null` if the native library is unavailable (e.g., in host JVM unit tests).
         */
        @JvmStatic
        fun spawnProcessGroupOrNull(
            cmd: Array<String>,
            env: Array<String>?,
            dir: String?
        ): SpawnedProcessGroup? {
            return try {
                spawnProcessGroup(cmd, env, dir)
            } catch (_: UnsatisfiedLinkError) {
                null
            }
        }

        /**
         * Waits for a child process spawned via [spawnProcessGroup] and returns its normalized exit code.
         */
        @JvmStatic
        fun waitForPid(pid: Int): Int {
            if (pid <= 0) return -1
            RishConfig.ensureLibraryLoaded()
            return waitFor(pid)
        }

        /**
         * Immediately kills the entire process group `-pid` (`SIGKILL`), falling back to `pid` if needed.
         */
        @JvmStatic
        fun killProcessGroup(pid: Int) {
            if (pid <= 0) return
            try {
                Os.kill(-pid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ESRCH) {
                    try {
                        Os.kill(pid, OsConstants.SIGKILL)
                    } catch (_: ErrnoException) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        @JvmStatic
        private external fun start(
            argBlock: ByteArray?,
            argc: Int,
            envBlock: ByteArray?,
            envc: Int,
            dirBlock: ByteArray?,
            tty: Byte,
            stdin: Int,
            stdout: Int,
            stderr: Int
        ): IntArray

        @JvmStatic
        external fun nativeSpawnGroup(
            cmd: Array<String>,
            env: Array<String>?,
            dir: String?
        ): IntArray

        @JvmStatic
        private external fun setWindowSize(ptmx: Int, size: Long)

        @JvmStatic
        private external fun waitFor(pid: Int): Int
    }
}
