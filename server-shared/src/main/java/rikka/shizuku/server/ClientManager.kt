package rikka.shizuku.server

import android.os.IBinder
import android.os.RemoteException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.server.util.Logger

open class ClientManager<ConfigMgr : ConfigManager>(
    val configManager: ConfigMgr
) {

    private class LegacyClient(
        val application: IShizukuApplication,
        val deathRecipient: IBinder.DeathRecipient
    )

    private val clientRecords: MutableList<ClientRecord> = Collections.synchronizedList(ArrayList())

    /** UIDs that were granted one-time access by the manager and may attach until revoked. */
    private val oneTimeGrantedUids: MutableSet<Int> =
        Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /** Applications attached through the legacy Shizuku wire, keyed by uid/pid. */
    private val legacyClients = ConcurrentHashMap<Long, LegacyClient>()

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

    /** Immutable snapshot of the currently attached client records. */
    protected fun snapshotClients(): List<ClientRecord> {
        synchronized(clientRecords) {
            return ArrayList(clientRecords)
        }
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

        val verifier = configManager as? ConfigManager.ClientAccessVerifier
        if (verifier != null) {
            if (verifier.isAccessPaused()) {
                clientRecord.allowed = false
            } else if (isOneTimeAccessGranted(uid)) {
                clientRecord.allowed = true
                verifier.recordConnection(uid)
            } else if (clientRecord.allowed) {
                if (!verifier.verifyClientIdentity(uid, packageName)) {
                    clientRecord.allowed = false
                } else {
                    verifier.recordConnection(uid)
                }
            }
        } else if (isOneTimeAccessGranted(uid)) {
            clientRecord.allowed = true
        }

        val binder = client.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            synchronized(clientRecords) {
                clientRecords.remove(clientRecord)
            }
            onClientRemoved(clientRecord)
            onClientDied(uid, pid, packageName)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
            clientRecord.deathRecipient = deathRecipient
        } catch (e: RemoteException) {
            LOGGER.w(e, "addClient: linkToDeath failed")
            return null
        }

        val replaced = ArrayList<ClientRecord>()
        synchronized(clientRecords) {
            val iterator = clientRecords.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                if (existing.uid == uid && existing.pid == pid) {
                    existing.unlinkToDeath()
                    iterator.remove()
                    replaced.add(existing)
                }
            }
            clientRecords.add(clientRecord)
        }
        // Notify outside the monitor: subclasses may synchronize on other state or query the
        // manager while handling the callback.
        for (existing in replaced) {
            onClientRemoved(existing)
        }
        return clientRecord
    }

    protected open fun onClientRemoved(clientRecord: ClientRecord) {
    }

    /**
     * Invoked when a client process is known to be gone (binder death, explicit detach, or the
     * legacy wire reporting a process death). [packageName] is `null` when the record was already
     * removed before the death notification arrived.
     */
    protected open fun onClientDied(uid: Int, pid: Int, packageName: String?) {
    }

    // --- One-time grants -----------------------------------------------------

    /** Grants [uid] access until it is explicitly revoked or expires by server policy. */
    fun grantOneTimeAccess(uid: Int) {
        oneTimeGrantedUids.add(uid)
    }

    /** Revokes a one-time grant. Returns `true` if a grant was present. */
    fun revokeOneTimeAccess(uid: Int): Boolean = oneTimeGrantedUids.remove(uid)

    fun isOneTimeAccessGranted(uid: Int): Boolean = oneTimeGrantedUids.contains(uid)

    // --- Legacy application registry ----------------------------------------

    /**
     * Records the [IShizukuApplication] attached through the legacy Shizuku wire for [uid]/[pid].
     * A previous application for the same process is unlinked and replaced.
     */
    fun registerLegacyClient(uid: Int, pid: Int, application: IShizukuApplication) {
        val key = legacyKey(uid, pid)
        unregisterLegacyClient(uid, pid)

        lateinit var record: LegacyClient
        val deathRecipient = IBinder.DeathRecipient {
            legacyClients.remove(key, record)
        }
        record = LegacyClient(application, deathRecipient)
        try {
            application.asBinder().linkToDeath(deathRecipient, 0)
        } catch (t: Throwable) {
            LOGGER.w(t, "registerLegacyClient: linkToDeath failed for uid %d pid %d", uid, pid)
            return
        }
        legacyClients[key] = record
    }

    fun findLegacyClient(uid: Int, pid: Int): IShizukuApplication? =
        legacyClients[legacyKey(uid, pid)]?.application

    fun unregisterLegacyClient(uid: Int, pid: Int) {
        val record = legacyClients.remove(legacyKey(uid, pid)) ?: return
        runCatching { record.application.asBinder().unlinkToDeath(record.deathRecipient, 0) }
    }

    /**
     * Clears all per-process state for a process that is known to be dead: the attached client
     * record, its legacy application registration, and any one-shot callbacks.
     */
    fun notifyClientProcessDied(uid: Int, pid: Int) {
        unregisterLegacyClient(uid, pid)
        var removedRecord: ClientRecord? = null
        synchronized(clientRecords) {
            val iterator = clientRecords.iterator()
            while (iterator.hasNext()) {
                val record = iterator.next()
                if (record.uid == uid && record.pid == pid) {
                    removedRecord = record
                    iterator.remove()
                    break
                }
            }
        }
        val record = removedRecord
        record?.unlinkToDeath()
        if (record != null) {
            onClientRemoved(record)
        }
        onClientDied(uid, pid, record?.packageName)
    }

    private fun legacyKey(uid: Int, pid: Int): Long =
        (uid.toLong() shl 32) or (pid.toLong() and 0xFFFFFFFFL)

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("ClientManager")
    }
}

/**
 * Compatibility alias for callers of the historical Shizuku client manager API.
 *
 * This is an extension rather than a member so that existing subclasses that already declare
 * `notifyProcessDied` keep compiling unchanged; Kotlin resolves the subclass member first.
 */
fun ClientManager<*>.notifyProcessDied(uid: Int, pid: Int) = notifyClientProcessDied(uid, pid)
