package kerneldroid.nightzuku.api

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/**
 * Native Nightzuku [IBinder] wrapper that routes system service transactions over
 * [NightClient.transactRemote] on the flat Parcel wire, falling back to [Shizuku.transactRemote]
 * when only the legacy Shizuku wire is connected.
 */
class NightBinderWrapper(
    private val original: IBinder
) : IBinder {

    @Throws(RemoteException::class)
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (NightClient.pingBinder()) {
            NightClient.transactRemote(original, code, data, reply, flags)
            return true
        }
        val wrapped = Parcel.obtain()
        try {
            wrapped.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            wrapped.writeStrongBinder(original)
            wrapped.writeInt(code)
            wrapped.writeInt(flags)
            wrapped.appendFrom(data, 0, data.dataSize())
            Shizuku.transactRemote(wrapped, reply, 0)
        } finally {
            wrapped.recycle()
        }
        return true
    }

    @Throws(RemoteException::class)
    override fun getInterfaceDescriptor(): String? = original.interfaceDescriptor

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    @Throws(RemoteException::class)
    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        original.dump(fd, args)
    }

    @Throws(RemoteException::class)
    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        original.dumpAsync(fd, args)
    }

    @Throws(RemoteException::class)
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        original.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
        return original.unlinkToDeath(recipient, flags)
    }
}
