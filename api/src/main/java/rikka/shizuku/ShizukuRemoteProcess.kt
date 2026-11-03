package rikka.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.ArraySet
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IRemoteProcess

class ShizukuRemoteProcess : Process, Parcelable {

    @Volatile
    private var remote: IRemoteProcess?
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var os: OutputStream? = null
    private var `is`: InputStream? = null
    private var es: InputStream? = null

    @Volatile
    private var exitPipe: ParcelFileDescriptor? = null

    @Volatile
    private var exitPipeResolved: Boolean = false

    @Volatile
    private var cachedExitCode: Int? = null

    internal constructor(remote: IRemoteProcess, exitPipe: ParcelFileDescriptor? = null) {
        this.remote = remote
        this.exitPipe = exitPipe
        this.exitPipeResolved = exitPipe != null
        linkRemoteToDeath(remote)
    }

    private constructor(parcel: Parcel) {
        val parsed = IRemoteProcess.Stub.asInterface(parcel.readStrongBinder())
        this.remote = parsed
        if (parcel.dataAvail() > 0 && parcel.readInt() != 0) {
            this.exitPipe = ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
            this.exitPipeResolved = true
        }
        if (parsed != null) {
            linkRemoteToDeath(parsed)
        }
    }

    @Synchronized
    private fun linkRemoteToDeath(target: IRemoteProcess) {
        try {
            val recipient = IBinder.DeathRecipient {
                synchronized(this@ShizukuRemoteProcess) {
                    this.remote = null
                    this.deathRecipient = null
                }
                Log.v(TAG, "remote process is dead")
                CACHE.remove(this@ShizukuRemoteProcess)
            }
            target.asBinder().linkToDeath(recipient, 0)
            this.deathRecipient = recipient
            CACHE.add(this)
        } catch (e: RemoteException) {
            Log.e(TAG, "linkToDeath", e)
        }
    }

    @Synchronized
    private fun unlinkToDeathAndRemove() {
        val currentRemote = remote
        val recipient = deathRecipient
        if (currentRemote != null && recipient != null) {
            try {
                currentRemote.asBinder().unlinkToDeath(recipient, 0)
            } catch (_: Throwable) {
            }
            deathRecipient = null
        }
        CACHE.remove(this)
    }

    @Synchronized
    private fun closeExitPipe() {
        val pipe = exitPipe
        exitPipe = null
        if (pipe != null) {
            try {
                pipe.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    private fun obtainExitPipe(): ParcelFileDescriptor? {
        if (exitPipe != null) return exitPipe
        if (exitPipeResolved) return null
        exitPipeResolved = true
        val current = remote ?: return null
        exitPipe = try {
            current.exitFd
        } catch (_: Throwable) {
            null
        }
        return exitPipe
    }

    private fun readExitCodeFromPipeOrRemote(pipe: ParcelFileDescriptor): Int {
        cachedExitCode?.let { return it }
        synchronized(this) {
            cachedExitCode?.let { return it }
            try {
                val buf = ByteArray(4)
                var offset = 0
                while (offset < 4) {
                    val read = Os.read(pipe.fileDescriptor, buf, offset, 4 - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == 4) {
                    val code = ((buf[0].toInt() and 0xFF) shl 24) or
                        ((buf[1].toInt() and 0xFF) shl 16) or
                        ((buf[2].toInt() and 0xFF) shl 8) or
                        (buf[3].toInt() and 0xFF)
                    cachedExitCode = code
                    closeExitPipe()
                    return code
                }
            } catch (_: Throwable) {
            }
            closeExitPipe()
            val fallback = try {
                requireRemote().waitFor()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
            cachedExitCode = fallback
            return fallback
        }
    }

    /**
     * Waits on the kernel pipe EOF descriptor (`exitPipe[0]`) using `Os.poll` with zero Binder polling.
     */
    @Throws(InterruptedException::class)
    private fun pollExitPipe(pipe: ParcelFileDescriptor, timeoutNanos: Long?): Boolean {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }
        val deadlineNanos = if (timeoutNanos != null) {
            System.nanoTime() + timeoutNanos.coerceAtLeast(0L)
        } else {
            0L
        }

        while (true) {
            if (cachedExitCode != null) return true
            val fd = pipe.fileDescriptor
            if (fd == null || !fd.valid()) {
                return cachedExitCode != null
            }

            val pollTimeoutMs: Int = if (timeoutNanos == null) {
                -1
            } else {
                val remainingNs = deadlineNanos - System.nanoTime()
                if (remainingNs <= 0L) {
                    0
                } else {
                    TimeUnit.NANOSECONDS.toMillis(remainingNs + 999_999L)
                        .coerceIn(1L, Int.MAX_VALUE.toLong())
                        .toInt()
                }
            }

            val pollfd = StructPollfd().apply {
                this.fd = fd
                this.events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
            }

            val ready = try {
                Os.poll(arrayOf(pollfd), pollTimeoutMs)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) {
                    if (Thread.interrupted()) {
                        throw InterruptedException()
                    }
                    continue
                }
                throw RuntimeException("Os.poll on exitPipe failed", e)
            }

            if (Thread.interrupted()) {
                throw InterruptedException()
            }

            if (ready > 0 || (pollfd.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR)) != 0) {
                readExitCodeFromPipeOrRemote(pipe)
                return true
            }

            if (timeoutNanos != null && (pollTimeoutMs == 0 || System.nanoTime() >= deadlineNanos)) {
                return false
            }
        }
    }

    private fun requireRemote(): IRemoteProcess {
        return remote ?: throw IllegalStateException("Remote process binder is dead")
    }

    @Synchronized
    override fun getOutputStream(): OutputStream {
        if (os == null) {
            os = try {
                ParcelFileDescriptor.AutoCloseOutputStream(requireRemote().outputStream)
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
        return os!!
    }

    @Synchronized
    override fun getInputStream(): InputStream {
        if (`is` == null) {
            `is` = try {
                ParcelFileDescriptor.AutoCloseInputStream(requireRemote().inputStream)
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
        return `is`!!
    }

    @Synchronized
    override fun getErrorStream(): InputStream {
        if (es == null) {
            es = try {
                ParcelFileDescriptor.AutoCloseInputStream(requireRemote().errorStream)
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
        return es!!
    }

    @Throws(InterruptedException::class)
    override fun waitFor(): Int {
        cachedExitCode?.let {
            unlinkToDeathAndRemove()
            return it
        }
        val pipe = obtainExitPipe()
        if (pipe != null) {
            return try {
                pollExitPipe(pipe, timeoutNanos = null)
                cachedExitCode ?: requireRemote().waitFor()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            } finally {
                unlinkToDeathAndRemove()
            }
        }
        return try {
            val code = requireRemote().waitFor()
            cachedExitCode = code
            code
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            unlinkToDeathAndRemove()
        }
    }

    override fun exitValue(): Int {
        cachedExitCode?.let {
            unlinkToDeathAndRemove()
            return it
        }
        val pipe = obtainExitPipe()
        if (pipe != null) {
            try {
                if (pollExitPipe(pipe, timeoutNanos = 0L)) {
                    val code = cachedExitCode ?: requireRemote().exitValue()
                    unlinkToDeathAndRemove()
                    return code
                } else {
                    throw IllegalThreadStateException("Process has not exited")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalThreadStateException("Interrupted while checking exitValue")
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
        val code = try {
            requireRemote().exitValue()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
        cachedExitCode = code
        unlinkToDeathAndRemove()
        return code
    }

    override fun destroy() {
        try {
            requireRemote().destroy()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            closeExitPipe()
            unlinkToDeathAndRemove()
        }
    }

    fun alive(): Boolean {
        if (cachedExitCode != null) return false
        val pipe = obtainExitPipe()
        if (pipe != null) {
            return try {
                !pollExitPipe(pipe, timeoutNanos = 0L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                true
            }
        }
        return try {
            requireRemote().alive()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    @Throws(InterruptedException::class)
    fun waitForTimeout(timeout: Long, unit: TimeUnit): Boolean {
        if (cachedExitCode != null) {
            unlinkToDeathAndRemove()
            return true
        }
        val pipe = obtainExitPipe()
        if (pipe != null) {
            val exited = pollExitPipe(pipe, unit.toNanos(timeout.coerceAtLeast(0L)))
            if (exited) {
                unlinkToDeathAndRemove()
            }
            return exited
        }
        val exited = try {
            requireRemote().waitForTimeout(timeout, unit.toString())
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
        if (exited) {
            unlinkToDeathAndRemove()
        }
        return exited
    }

    fun asBinder(): IBinder = requireRemote().asBinder()

    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(remote?.asBinder())
        val pipe = exitPipe
        if (pipe != null) {
            dest.writeInt(1)
            pipe.writeToParcel(dest, flags)
        } else {
            dest.writeInt(0)
        }
    }

    companion object {
        private const val TAG = "ShizukuRemoteProcess"
        private val CACHE: MutableSet<ShizukuRemoteProcess> = Collections.synchronizedSet(ArraySet())

        @JvmField
        val CREATOR: Parcelable.Creator<ShizukuRemoteProcess> = object : Parcelable.Creator<ShizukuRemoteProcess> {
            override fun createFromParcel(source: Parcel): ShizukuRemoteProcess = ShizukuRemoteProcess(source)
            override fun newArray(size: Int): Array<ShizukuRemoteProcess?> = arrayOfNulls(size)
        }
    }
}
