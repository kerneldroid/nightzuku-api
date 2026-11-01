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

    private var remote: IRemoteProcess?
    private var os: OutputStream? = null
    private var `is`: InputStream? = null

    internal constructor(remote: IRemoteProcess) {
        this.remote = remote
        try {
            remote.asBinder().linkToDeath({
                this.remote = null
                Log.v(TAG, "remote process is dead")
                CACHE.remove(this@ShizukuRemoteProcess)
            }, 0)
        } catch (e: RemoteException) {
            Log.e(TAG, "linkToDeath", e)
        }
        CACHE.add(this)
    }

    private constructor(parcel: Parcel) {
        remote = IRemoteProcess.Stub.asInterface(parcel.readStrongBinder())
    }

    private fun requireRemote(): IRemoteProcess {
        return remote ?: throw IllegalStateException("Remote process binder is dead")
    }

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

    override fun getErrorStream(): InputStream {
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(requireRemote().errorStream)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    @Throws(InterruptedException::class)
    override fun waitFor(): Int {
        return try {
            requireRemote().waitFor()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    override fun exitValue(): Int {
        return try {
            requireRemote().exitValue()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    override fun destroy() {
        try {
            requireRemote().destroy()
        } catch (e: RemoteException) {
            throw RuntimeException(e)
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
        return try {
            requireRemote().waitForTimeout(timeout, unit.toString())
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
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
