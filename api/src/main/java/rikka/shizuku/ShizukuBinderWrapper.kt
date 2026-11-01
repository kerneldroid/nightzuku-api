package rikka.shizuku

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor
import java.util.Objects
import kerneldroid.nightzuku.api.NightClient

/**
 * Binder wrapper that forwards system service transactions through [Shizuku.transactRemote]
 * or seamlessly delegates to [NightClient.transactRemote] when connected solely on the Nightzuku wire.
 */
class ShizukuBinderWrapper(original: IBinder) : IBinder {

    private val original: IBinder = Objects.requireNonNull(original)

    @Throws(RemoteException::class)
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!Shizuku.pingBinder() && NightClient.pingBinder()) {
            NightClient.transactRemote(original, code, data, reply, flags)
            return true
        }

        val atLeast13 = !Shizuku.isPreV11() && Shizuku.getVersion() >= 13
        val newData = Parcel.obtain()
        try {
            newData.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            newData.writeStrongBinder(original)
            newData.writeInt(code)
            if (atLeast13) {
                newData.writeInt(flags)
            }
            newData.appendFrom(data, 0, data.dataSize())
            if (atLeast13) {
                Shizuku.transactRemote(newData, reply, 0)
            } else {
                Shizuku.transactRemote(newData, reply, flags)
            }
        } finally {
            newData.recycle()
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
