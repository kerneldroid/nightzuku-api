package rikka.shizuku.server.api

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import moe.shizuku.server.IRemoteProcess
import rikka.rish.RishHost
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.ParcelFileDescriptorUtil
import rikka.shizuku.server.util.ProcessCompat

class RemoteProcessHolder private constructor(
    private val process: Process?,
    private val token: IBinder?,
    val pid: Int,
    private var `in`: ParcelFileDescriptor?,
    private var out: ParcelFileDescriptor?,
    private var err: ParcelFileDescriptor?
) : IRemoteProcess.Stub() {

    constructor(process: Process, token: IBinder?) : this(
        process = process,
        token = token,
        pid = extractPid(process),
        `in` = null,
        out = null,
        err = null
    )

    constructor(spawned: RishHost.SpawnedProcessGroup, token: IBinder?) : this(
        process = null,
        token = token,
        pid = spawned.pid,
        `in` = spawned.stdoutPfd,
        out = spawned.stdinPfd,
        err = spawned.stderrPfd
    )

    private var deathRecipient: IBinder.DeathRecipient? = null
    private val exitPipe: Array<ParcelFileDescriptor>? = runCatching {
        ParcelFileDescriptor.createPipe()
    }.getOrNull()
    private var exitReadPfd: ParcelFileDescriptor? = exitPipe?.getOrNull(0)
    private var exitWritePfd: ParcelFileDescriptor? = exitPipe?.getOrNull(1)
    private val exitLatch = CountDownLatch(1)
    private val exitSignaled = AtomicBoolean(false)

    @Volatile
    private var cachedExitCode: Int = -1

    init {
        if (token != null) {
            try {
                val recipient = IBinder.DeathRecipient {
                    try {
                        if (alive()) {
                            destroy()
                            LOGGER.i("destroy process (pid=%d) because the owner is dead", pid)
                        }
                    } catch (e: Throwable) {
                        LOGGER.w(e, "failed to destroy process")
                    }
                }
                token.linkToDeath(recipient, 0)
                this.deathRecipient = recipient
            } catch (e: Throwable) {
                LOGGER.w(e, "linkToDeath")
            }
        }

        val watcher = Thread({
            val code = try {
                when {
                    process != null -> process.waitFor()
                    pid > 0 -> RishHost.waitForPid(pid)
                    else -> -1
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                128 + OsConstants.SIGKILL
            } catch (_: Throwable) {
                -1
            }
            signalProcessExited(code)
        }, "nightzuku-proc-exit-${if (pid > 0) pid else System.identityHashCode(this)}")
        watcher.isDaemon = true
        watcher.start()
    }

    private fun signalProcessExited(code: Int) {
        if (!exitSignaled.compareAndSet(false, true)) {
            return
        }
        cachedExitCode = code
        exitLatch.countDown()

        val writeEnd = synchronized(this) {
            // Once the child process has terminated, its stdin write pipe is no longer needed
            runCatching { out?.close() }
            out = null
            val w = exitWritePfd
            exitWritePfd = null
            w
        }
        if (writeEnd != null) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { stream ->
                    stream.write(
                        byteArrayOf(
                            (code ushr 24).toByte(),
                            (code ushr 16).toByte(),
                            (code ushr 8).toByte(),
                            code.toByte()
                        )
                    )
                    stream.flush()
                }
            } catch (_: Throwable) {
                runCatching { writeEnd.close() }
            }
        }
        unlinkTokenToDeath()
    }

    @Synchronized
    private fun unlinkTokenToDeath() {
        val currentToken = token
        val recipient = deathRecipient
        if (currentToken != null && recipient != null) {
            try {
                currentToken.unlinkToDeath(recipient, 0)
            } catch (_: Throwable) {
            }
            deathRecipient = null
        }
    }

    @Synchronized
    private fun closeCachedStreams() {
        runCatching { `in`?.close() }
        runCatching { out?.close() }
        runCatching { err?.close() }
        runCatching { exitReadPfd?.close() }
        `in` = null
        out = null
        err = null
        exitReadPfd = null
    }

    @Synchronized
    override fun getOutputStream(): ParcelFileDescriptor {
        if (out == null) {
            val proc = process ?: throw IllegalStateException("Process output stream is unavailable")
            out = try {
                ParcelFileDescriptorUtil.pipeTo(proc.outputStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return out!!
    }

    @Synchronized
    override fun getInputStream(): ParcelFileDescriptor {
        if (`in` == null) {
            val proc = process ?: throw IllegalStateException("Process input stream is unavailable")
            `in` = try {
                ParcelFileDescriptorUtil.pipeFrom(proc.inputStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return `in`!!
    }

    @Synchronized
    override fun getErrorStream(): ParcelFileDescriptor {
        if (err == null) {
            val proc = process ?: throw IllegalStateException("Process error stream is unavailable")
            err = try {
                ParcelFileDescriptorUtil.pipeFrom(proc.errorStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return err!!
    }

    @Synchronized
    override fun getExitFd(): ParcelFileDescriptor? {
        val readEnd = exitReadPfd ?: return null
        exitReadPfd = null
        return readEnd
    }

    override fun waitFor(): Int {
        return try {
            exitLatch.await()
            cachedExitCode
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(e)
        } finally {
            unlinkTokenToDeath()
        }
    }

    override fun exitValue(): Int {
        if (exitLatch.count > 0L) {
            if (process != null) {
                val code = process.exitValue()
                signalProcessExited(code)
                return code
            }
            throw IllegalThreadStateException("Process (pid=$pid) has not exited")
        }
        unlinkTokenToDeath()
        return cachedExitCode
    }

    override fun destroy() {
        try {
            if (pid > 0) {
                killProcessGroup(pid)
            }
            process?.let { ProcessCompat.destroyForcibly(it) }
        } finally {
            if (exitLatch.count > 0L) {
                runCatching { exitLatch.await(50, TimeUnit.MILLISECONDS) }
                if (exitLatch.count > 0L) {
                    signalProcessExited(128 + OsConstants.SIGKILL)
                }
            }
            closeCachedStreams()
            unlinkTokenToDeath()
        }
    }

    @Throws(RemoteException::class)
    override fun alive(): Boolean {
        if (exitLatch.count == 0L) return false
        if (process != null) {
            return try {
                val code = process.exitValue()
                signalProcessExited(code)
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
        return true
    }

    @Throws(RemoteException::class)
    override fun waitForTimeout(timeout: Long, unitName: String): Boolean {
        val unit = TimeUnit.valueOf(unitName)
        return try {
            val exited = exitLatch.await(timeout.coerceAtLeast(0L), unit)
            if (exited) {
                unlinkTokenToDeath()
            }
            exited
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(e)
        }
    }

    companion object {
        private val LOGGER = Logger("RemoteProcessHolder")

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

        private fun extractPid(process: Process): Int {
            return try {
                val pidMethod = Process::class.java.getMethod("pid")
                (pidMethod.invoke(process) as? Long)?.toInt() ?: -1
            } catch (_: Throwable) {
                try {
                    val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
                    field.getInt(process)
                } catch (_: Throwable) {
                    -1
                }
            }
        }
    }
}
