package rikka.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
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

    internal constructor(remote: IRemoteProcess) {
        this.remote = remote
        linkRemoteToDeath(remote)
    }

    private constructor(parcel: Parcel) {
        val parsed = IRemoteProcess.Stub.asInterface(parcel.readStrongBinder())
        this.remote = parsed
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
        return try {
            requireRemote().waitFor()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            unlinkToDeathAndRemove()
        }
    }

    override fun exitValue(): Int {
        val code = try {
            requireRemote().exitValue()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
        unlinkToDeathAndRemove()
        return code
    }

    override fun destroy() {
        try {
            requireRemote().destroy()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        } finally {
            unlinkToDeathAndRemove()
        }
    }

    fun alive(): Boolean {
        return try {
            requireRemote().alive()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    @Throws(InterruptedException::class)
    fun waitForTimeout(timeout: Long, unit: TimeUnit): Boolean {
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

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(remote?.asBinder())
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
