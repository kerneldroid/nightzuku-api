package rikka.shizuku.server.api

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.IOException
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.ParcelFileDescriptorUtil

class RemoteProcessHolder(
    private val process: Process,
    private val token: IBinder?
) : IRemoteProcess.Stub() {

    private var deathRecipient: IBinder.DeathRecipient? = null
    private var `in`: ParcelFileDescriptor? = null
    private var out: ParcelFileDescriptor? = null
    private var err: ParcelFileDescriptor? = null

    init {
        if (token != null) {
            try {
                val recipient = IBinder.DeathRecipient {
                    try {
                        if (alive()) {
                            destroy()
                            LOGGER.i("destroy process because the owner is dead")
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
    }

    @Synchronized
    override fun getOutputStream(): ParcelFileDescriptor {
        if (out == null) {
            out = try {
                ParcelFileDescriptorUtil.pipeTo(process.outputStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return out!!
    }

    @Synchronized
    override fun getInputStream(): ParcelFileDescriptor {
        if (`in` == null) {
            `in` = try {
                ParcelFileDescriptorUtil.pipeFrom(process.inputStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return `in`!!
    }

    @Synchronized
    override fun getErrorStream(): ParcelFileDescriptor {
        if (err == null) {
            err = try {
                ParcelFileDescriptorUtil.pipeFrom(process.errorStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return err!!
    }

    override fun waitFor(): Int {
        return try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(e)
        } finally {
            unlinkTokenToDeath()
        }
    }

    override fun exitValue(): Int {
        val code = process.exitValue()
        unlinkTokenToDeath()
        return code
    }

    override fun destroy() {
        try {
            process.destroy()
        } finally {
            closeCachedStreams()
            unlinkTokenToDeath()
        }
    }

    @Throws(RemoteException::class)
    override fun alive(): Boolean {
        return try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    @Throws(RemoteException::class)
    override fun waitForTimeout(timeout: Long, unitName: String): Boolean {
        val unit = TimeUnit.valueOf(unitName)
        val startTime = System.nanoTime()
        var rem = unit.toNanos(timeout)

        do {
            try {
                exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                if (rem > 0) {
                    try {
                        Thread.sleep((TimeUnit.NANOSECONDS.toMillis(rem) + 1).coerceAtMost(100))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException()
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime)
        } while (rem > 0)
        return false
    }

    companion object {
        private val LOGGER = Logger("RemoteProcessHolder")
    }
}
