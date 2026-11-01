package rikka.shizuku.server

import android.os.IBinder
import android.os.RemoteException
import java.util.Collections
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.server.util.Logger

open class ClientManager<ConfigMgr : ConfigManager>(
    val configManager: ConfigMgr
) {

    private val clientRecords: MutableList<ClientRecord> = Collections.synchronizedList(ArrayList())

    fun findClients(uid: Int): List<ClientRecord> {
        synchronized(clientRecords) {
            val res = ArrayList<ClientRecord>()
            for (clientRecord in clientRecords) {
                if (clientRecord.uid == uid) {
                    res.add(clientRecord)
                }
            }
            return res
        }
    }

    fun findClient(uid: Int, pid: Int): ClientRecord? {
        synchronized(clientRecords) {
            for (clientRecord in clientRecords) {
                if (clientRecord.pid == pid && clientRecord.uid == uid) {
                    return clientRecord
                }
            }
        }
        return null
    }

    @JvmOverloads
    fun requireClient(callingUid: Int, callingPid: Int, requiresPermission: Boolean = false): ClientRecord {
        val clientRecord = findClient(callingUid, callingPid)
        if (clientRecord == null) {
            LOGGER.w("Caller (uid %d, pid %d) is not an attached client", callingUid, callingPid)
            throw IllegalStateException("Not an attached client")
        }
        if (requiresPermission && !clientRecord.allowed) {
            throw SecurityException("Caller has no permission")
        }
        return clientRecord
    }

    open fun addClient(
        uid: Int,
        pid: Int,
        client: IShizukuApplication?,
        packageName: String?,
        apiVersion: Int
    ): ClientRecord? {
        if (client == null || packageName == null) return null
        val clientRecord = ClientRecord(uid, pid, client, packageName, apiVersion)

        val entry = configManager.find(uid)
        if (entry != null && entry.isAllowed()) {
            clientRecord.allowed = true
        }

        val binder = client.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            clientRecords.remove(clientRecord)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            LOGGER.w(e, "addClient: linkToDeath failed")
            return null
        }

        clientRecords.add(clientRecord)
        return clientRecord
    }

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("ClientManager")
    }
}
