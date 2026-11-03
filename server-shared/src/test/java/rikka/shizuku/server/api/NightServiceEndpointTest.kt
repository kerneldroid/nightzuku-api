package rikka.shizuku.server.api

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigFlagsEntry
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ConfigPackageEntry
import rikka.shizuku.server.ServerPolicy
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.OsUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NightServiceEndpointTest {

    private lateinit var host: FakeHost
    private lateinit var endpoint: TestEndpoint

    @Before
    fun setUp() {
        ShadowBinder.setCallingUid(CALLING_UID)
        ShadowBinder.setCallingPid(CALLING_PID)
        host = FakeHost()
        endpoint = TestEndpoint(host, listOf(PACKAGE_NAME))
    }

    @Test
    fun `attach handshake accepts compatible clients and returns the grant state`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)

        val callback = CallbackBinder()
        val reply = attach(NightProtocol.PROTOCOL_VERSION, callback)
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(NightProtocol.ATTACH_STATUS_OK, reply.readInt())
        assertEquals(NightProtocol.PROTOCOL_VERSION, reply.readInt())
        assertEquals(NightProtocol.MIN_PROTOCOL_VERSION, reply.readInt())
        assertEquals(OsUtils.getUid(), reply.readInt())
        assertEquals(NightProtocol.STATE_GRANTED, reply.readInt())
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, reply.readInt())
        reply.readString()
        reply.recycle()

        assertEquals(listOf(PACKAGE_NAME), host.attachCalls.map { it.third })
        assertTrue(endpoint.hasNightCallback(CALLING_PID))
    }

    @Test
    fun `attach handshake rejects clients that are too old`() {
        val reply = attach(NightProtocol.MIN_PROTOCOL_VERSION - 1)
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(NightProtocol.ATTACH_STATUS_CLIENT_TOO_OLD, reply.readInt())
        assertEquals(NightProtocol.PROTOCOL_VERSION, reply.readInt())
        assertEquals(NightProtocol.MIN_PROTOCOL_VERSION, reply.readInt())
        reply.recycle()

        assertTrue(host.attachCalls.isEmpty())
        assertFalse(endpoint.hasNightCallback(CALLING_PID))
    }

    @Test
    fun `attach handshake reports when the server is too old`() {
        val reply = attach(NightProtocol.PROTOCOL_VERSION + 1)
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(NightProtocol.ATTACH_STATUS_SERVER_TOO_OLD, reply.readInt())
        reply.recycle()

        assertTrue(host.attachCalls.isEmpty())
    }

    @Test
    fun `attach rejects packages the caller does not own`() {
        endpoint = TestEndpoint(host, listOf(OTHER_PACKAGE))

        val reply = attach(NightProtocol.PROTOCOL_VERSION)
        assertThrows(SecurityException::class.java) { reply.readException() }
        reply.recycle()
        assertTrue(host.attachCalls.isEmpty())
    }

    @Test
    fun `checkPermission reports granted and denied clients with capabilities`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)
        attach(NightProtocol.PROTOCOL_VERSION).recycle()

        var reply = writeAndTransact(NightProtocol.TRANSACTION_CHECK_PERMISSION) {}
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(1, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, reply.readInt())
        reply.recycle()

        // A fresh server instance attached without a grant must report denied.
        host = FakeHost()
        endpoint = TestEndpoint(host, listOf(PACKAGE_NAME))
        attach(NightProtocol.PROTOCOL_VERSION).recycle()
        reply = writeAndTransact(NightProtocol.TRANSACTION_CHECK_PERMISSION) {}
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals(0, reply.readInt())
        reply.recycle()
    }

    @Test
    fun `execFast returns the exit code and captured output`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)

        val reply = writeAndTransact(NightProtocol.TRANSACTION_EXEC_FAST) { data ->
            data.writeStringArray(SHELL_WITH_OUTPUT)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }

        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(3, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals("stdout", String(reply.createByteArray()!!, Charsets.UTF_8))
        assertEquals("stderr", String(reply.createByteArray()!!, Charsets.UTF_8))
        reply.recycle()
    }

    @Test
    fun `execAsync delivers the result through the callback binder`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)
        val callback = CallbackBinder()
        attach(NightProtocol.PROTOCOL_VERSION, callback).recycle()

        writeAndTransact(NightProtocol.TRANSACTION_EXEC_ASYNC) { data ->
            data.writeLong(CALL_ID)
            data.writeStringArray(SHELL_WITH_OUTPUT)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }.recycle()

        val result = callback.execResults.poll(5, TimeUnit.SECONDS)
        assertNotNull(result)
        assertEquals(CALL_ID, result!!.callId)
        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("stdout", String(result.stdout, Charsets.UTF_8))
        assertEquals("stderr", String(result.stderr, Charsets.UTF_8))
    }

    @Test
    fun `cancelExec stops an in-flight async call and reports cancellation exit code`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)
        val callback = CallbackBinder()
        attach(NightProtocol.PROTOCOL_VERSION, callback).recycle()

        writeAndTransact(NightProtocol.TRANSACTION_EXEC_ASYNC) { data ->
            data.writeLong(CALL_ID)
            data.writeStringArray(SLEEPING_SHELL)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }.recycle()

        writeAndTransact(NightProtocol.TRANSACTION_CANCEL_EXEC) { data ->
            data.writeLong(CALL_ID)
        }.recycle()

        val result = callback.execResults.poll(5, TimeUnit.SECONDS)
        assertNotNull(result)
        assertEquals(CALL_ID, result!!.callId)
        assertEquals(137, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `transactRemote rejects forwarding to the server binder`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)

        val reply = writeAndTransact(NightProtocol.TRANSACTION_TRANSACT_REMOTE) { data ->
            data.writeStrongBinder(host.serverBinder)
            data.writeInt(TARGET_CODE)
            data.writeInt(0)
        }
        assertThrows(SecurityException::class.java) { reply.readException() }
        reply.recycle()
    }

    @Test
    fun `transactRemote forwards the transaction to an unrelated binder`() {
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)
        val target = RecordingBinder()

        val reply = writeAndTransact(NightProtocol.TRANSACTION_TRANSACT_REMOTE) { data ->
            data.writeStrongBinder(target)
            data.writeInt(TARGET_CODE)
            data.writeInt(0)
        }

        assertEquals(TARGET_CODE, target.lastCode)
        reply.recycle()
    }

    @Test
    fun `unknown transaction codes are not handled`() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            data.setDataPosition(0)
            assertFalse(endpoint.transact(UNKNOWN_CODE, data, reply, 0))
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun `user service transactions are delegated to the host`() {
        val connection = object : IShizukuServiceConnection.Stub() {
            override fun connected(service: IBinder?) = Unit

            override fun died() = Unit
        }
        val options = Bundle().apply { putString("tag", "test") }

        var reply = writeAndTransact(NightProtocol.TRANSACTION_ADD_USER_SERVICE) { data ->
            data.writeStrongBinder(connection.asBinder())
            data.writeBundle(options)
        }
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(0, reply.readInt())
        reply.recycle()

        reply = writeAndTransact(NightProtocol.TRANSACTION_REMOVE_USER_SERVICE) { data ->
            data.writeStrongBinder(connection.asBinder())
            data.writeBundle(options)
        }
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(0, reply.readInt())
        reply.recycle()

        assertEquals(1, host.addedUserServices)
        assertEquals(1, host.removedUserServices)
    }

    @Test
    fun `capability transactions read and update the config manager`() {
        host.manager = true
        host.config.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)

        var reply = writeAndTransact(NightProtocol.TRANSACTION_GET_UID_CAPABILITIES) { data ->
            data.writeInt(CALLING_UID)
        }
        assertEquals(NO_EXCEPTION, reply.readInt())
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, reply.readInt())
        reply.recycle()

        reply = writeAndTransact(NightProtocol.TRANSACTION_SET_UID_CAPABILITIES) { data ->
            data.writeInt(CALLING_UID)
            data.writeInt(NightProtocol.CAP_SHELL_EXEC)
        }
        assertEquals(NO_EXCEPTION, reply.readInt())
        reply.recycle()

        val entry = host.config.find(CALLING_UID) as ConfigFlagsEntry
        assertEquals(
            ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC,
            entry.capabilityFlags()
        )
        assertEquals(listOf(CALLING_UID), host.capabilityNotifications)
    }

    private fun attach(version: Int, callback: IBinder? = null): Parcel =
        writeAndTransact(NightProtocol.TRANSACTION_ATTACH) { data ->
            data.writeStrongBinder(callback)
            data.writeInt(version)
            data.writeString(PACKAGE_NAME)
        }

    private fun writeAndTransact(code: Int, write: (Parcel) -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            write(data)
            data.setDataPosition(0)
            assertTrue(endpoint.transact(code, data, reply, 0))
        } finally {
            data.recycle()
        }
        reply.setDataPosition(0)
        return reply
    }

    private class TestEndpoint(
        host: FakeHost,
        private val installedPackages: List<String>
    ) : NightServiceEndpoint(host) {
        override fun packagesForUid(uid: Int): List<String> = installedPackages
    }

    private class FakeHost : NightServerHost {
        val config = TestConfigManager()
        val clients = ClientManager<ConfigManager>(config)
        val userServices = TestUserServiceManager()

        override val configManager: ConfigManager get() = config

        override val clientManager: ClientManager<out ConfigManager> get() = clients

        override val userServiceManager: UserServiceManager get() = userServices

        override val serverPolicy: ServerPolicy? get() = null

        private val binder = Binder()

        override val serverBinder: IBinder get() = binder

        var manager: Boolean = false
        var addedUserServices: Int = 0
        var removedUserServices: Int = 0
        val attachCalls = ArrayList<Triple<Int, Int, String>>()
        val capabilityNotifications = ArrayList<Int>()

        override fun attachNightClient(
            callingUid: Int,
            callingPid: Int,
            bridgeApp: IShizukuApplication,
            packageName: String,
            clientProtocolVersion: Int
        ): ClientRecord? {
            attachCalls.add(Triple(callingUid, callingPid, packageName))
            return clients.addClient(
                callingUid,
                callingPid,
                bridgeApp,
                packageName,
                clientProtocolVersion
            )
        }

        override fun onClientProcessDied(uid: Int, pid: Int) = Unit

        override fun requestPermissionFromNightWire(uid: Int, pid: Int, requestCode: Int) = Unit

        override fun notifyCapabilitiesUpdated(uid: Int) {
            capabilityNotifications.add(uid)
        }

        override fun setAccessPaused(paused: Boolean) {
            config.paused = paused
        }

        override fun getNightDogEnabled(default: Boolean): Boolean = default

        override fun checkCallerManagerPermission(
            func: String,
            callingUid: Int,
            callingPid: Int
        ): Boolean = manager

        override fun enforceCallingPermission(func: String) = Unit

        override fun enforceManagerPermission(func: String) {
            if (!manager) {
                throw SecurityException("Permission Denial: $func requires manager")
            }
        }

        override fun addUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
            addedUserServices++
            return 0
        }

        override fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int {
            removedUserServices++
            return 0
        }

        override fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess =
            FakeRemoteProcess

        override fun onTransactRish(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    private class TestUserServiceManager : UserServiceManager() {
        override fun getUserServiceStartCmd(
            record: UserServiceRecord,
            key: String,
            token: String,
            packageName: String,
            classname: String,
            processNameSuffix: String,
            callingUid: Int,
            use32Bits: Boolean,
            debug: Boolean
        ): String = "/system/bin/true"
    }

    private class TestEntry(val uid: Int, var rawFlags: Int) : ConfigPackageEntry(), ConfigFlagsEntry {
        override fun isAllowed(): Boolean = (rawFlags and ConfigManager.FLAG_ALLOWED) != 0

        override fun isDenied(): Boolean = (rawFlags and ConfigManager.FLAG_DENIED) != 0

        override fun capabilityFlags(): Int = rawFlags
    }

    private class TestConfigManager : ConfigManager() {
        private val entries = HashMap<Int, TestEntry>()
        var paused: Boolean = false

        fun allowWithFlags(uid: Int, flags: Int) {
            entries[uid] = TestEntry(uid, flags)
        }

        override fun find(uid: Int): ConfigPackageEntry? = entries[uid]

        override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) {
            val current = entries[uid]?.rawFlags ?: 0
            entries[uid] = TestEntry(uid, (current and mask.inv()) or (values and mask))
        }

        override fun remove(uid: Int) {
            entries.remove(uid)
        }

        override fun isAccessPaused(): Boolean = paused

        override fun setAccessPaused(paused: Boolean) {
            this.paused = paused
        }
    }

    private object FakeRemoteProcess : IRemoteProcess.Stub() {
        override fun getOutputStream(): ParcelFileDescriptor? = null

        override fun getInputStream(): ParcelFileDescriptor? = null

        override fun getErrorStream(): ParcelFileDescriptor? = null

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun alive(): Boolean = false

        override fun waitForTimeout(timeout: Long, unit: String?): Boolean = true

        override fun getExitFd(): ParcelFileDescriptor? = null
    }

    private data class ExecCallback(
        val callId: Long,
        val exitCode: Int,
        val timedOut: Boolean,
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    private class CallbackBinder : Binder() {
        val execResults = LinkedBlockingQueue<ExecCallback>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == NightProtocol.CALLBACK_ON_EXEC_RESULT) {
                data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                execResults.add(
                    ExecCallback(
                        callId = data.readLong(),
                        exitCode = data.readInt(),
                        timedOut = data.readInt() != 0,
                        stdout = data.createByteArray() ?: ByteArray(0),
                        stderr = data.createByteArray() ?: ByteArray(0)
                    )
                )
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class RecordingBinder : Binder() {
        var lastCode: Int = -1

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            lastCode = code
            return true
        }
    }

    companion object {
        private const val NO_EXCEPTION = 0
        private const val CALLING_UID = 10123
        private const val CALLING_PID = 4321
        private const val PACKAGE_NAME = "com.example.client"
        private const val OTHER_PACKAGE = "com.example.other"
        private const val CALL_ID = 77L
        private const val TARGET_CODE = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val UNKNOWN_CODE = IBinder.FIRST_CALL_TRANSACTION + 999

        private val SHELL_WITH_OUTPUT = arrayOf(
            "/bin/sh",
            "-c",
            "printf stdout; printf stderr >&2; exit 3"
        )
        private val SLEEPING_SHELL = arrayOf("/bin/sh", "-c", "sleep 5")
    }
}
