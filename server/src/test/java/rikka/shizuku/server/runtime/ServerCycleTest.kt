package rikka.shizuku.server.runtime

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.server.api.NightServiceEndpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * End-to-end, in-process exercise of the server half:
 * attach on the Night wire -> permission check -> synchronous exec -> async exec/cancel.
 *
 * Everything below the manager provider (binder publishing) is real: a concrete
 * [NightzukuServerService], its [rikka.shizuku.server.api.NightServiceEndpoint], real `sh`
 * processes, and Parcel transactions through the service binder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServerCycleTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: TestServer
    private lateinit var callback: ExecCallback

    @Before
    fun setUp() {
        ShadowBinder.setCallingUid(CALLING_UID)
        ShadowBinder.setCallingPid(CALLING_PID)
        val config = ServerConfig.fromProperties(
            properties = mapOf(
                ServerConfig.PROP_CONFIG_DIR to temporaryFolder.newFolder("config").absolutePath,
                ServerConfig.PROP_CONFIG_FILE to
                    File(temporaryFolder.root, "config/nightzuku.json").absolutePath
            )
        )
        server = NightzukuServerService.withConfig(config) { TestServer() }
        callback = ExecCallback()
        server.clientManager.grantOneTimeAccess(CALLING_UID)
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
    }

    @Test
    fun `attach negotiates the protocol and registers a granted client`() {
        val reply = attach()

        reply.readException()
        assertEquals(NightProtocol.ATTACH_STATUS_OK, reply.readInt())
        assertEquals(NightProtocol.PROTOCOL_VERSION, reply.readInt())
        assertEquals(NightProtocol.MIN_PROTOCOL_VERSION, reply.readInt())
        reply.readInt()
        assertEquals(NightProtocol.STATE_GRANTED, reply.readInt())
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, reply.readInt())
        reply.readString()
        reply.recycle()

        val record = server.clientManager.findClient(CALLING_UID, CALLING_PID)
        assertNotNull(record)
        assertTrue(record!!.allowed)
    }

    @Test
    fun `checkPermission reports the granted state and capabilities`() {
        attach().recycle()

        val reply = transact(NightProtocol.TRANSACTION_CHECK_PERMISSION) { }

        assertEquals(0, reply.readInt())
        assertEquals(1, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, reply.readInt())
        reply.recycle()
    }

    @Test
    fun `execFast runs echo and returns its exit code and output`() {
        attach().recycle()

        val reply = transact(NightProtocol.TRANSACTION_EXEC_FAST) { data ->
            data.writeStringArray(ECHO_COMMAND)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }

        assertEquals(0, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals("nightzuku\n", String(reply.createByteArray()!!, Charsets.UTF_8))
        assertEquals("", String(reply.createByteArray()!!, Charsets.UTF_8))
        reply.recycle()
    }

    @Test
    fun `execAsync delivers the result through the callback binder`() {
        attach().recycle()

        transact(NightProtocol.TRANSACTION_EXEC_ASYNC) { data ->
            data.writeLong(CALL_ID)
            data.writeStringArray(ECHO_COMMAND)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }.recycle()

        val result = callback.results.poll(10, TimeUnit.SECONDS)
        assertNotNull(result)
        assertEquals(CALL_ID, result!!.callId)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("nightzuku\n", String(result.stdout, Charsets.UTF_8))
        assertEquals("", String(result.stderr, Charsets.UTF_8))
    }

    @Test
    fun `cancelExec stops an in-flight async command without a result`() {
        attach().recycle()

        transact(NightProtocol.TRANSACTION_EXEC_ASYNC) { data ->
            data.writeLong(CALL_ID)
            data.writeStringArray(SLEEPING_COMMAND)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }.recycle()

        transact(NightProtocol.TRANSACTION_CANCEL_EXEC) { data ->
            data.writeLong(CALL_ID)
        }.recycle()

        assertNull(callback.results.poll(2, TimeUnit.SECONDS))
    }

    private fun attach(): Parcel =
        transact(NightProtocol.TRANSACTION_ATTACH) { data ->
            data.writeStrongBinder(callback)
            data.writeInt(NightProtocol.PROTOCOL_VERSION)
            data.writeString(PACKAGE_NAME)
        }

    private fun transact(code: Int, write: (Parcel) -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NightProtocol.DESCRIPTOR)
            write(data)
            data.setDataPosition(0)
            assertTrue(server.transact(code, data, reply, 0))
        } finally {
            data.recycle()
        }
        reply.setDataPosition(0)
        return reply
    }

    class TestServer : NightzukuServerService() {
        override fun packagesForUid(uid: Int): List<String> = listOf(PACKAGE_NAME)

        override fun createNightEndpoint(): NightServiceEndpoint =
            object : NightServiceEndpoint(this@TestServer) {
                override fun packagesForUid(uid: Int): List<String> = listOf(PACKAGE_NAME)
            }
    }

    private class ExecCallback : Binder() {

        data class Result(
            val callId: Long,
            val exitCode: Int,
            val timedOut: Boolean,
            val stdout: ByteArray,
            val stderr: ByteArray
        )

        val results = LinkedBlockingQueue<Result>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == NightProtocol.CALLBACK_ON_EXEC_RESULT) {
                data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                results.put(
                    Result(
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

    companion object {
        private const val CALLING_UID = 10_234
        private const val CALLING_PID = 4_242
        private const val PACKAGE_NAME = "com.example.client"
        private const val CALL_ID = 41L

        private val ECHO_COMMAND = arrayOf("/bin/sh", "-c", "echo nightzuku")
        private val SLEEPING_COMMAND = arrayOf("/bin/sh", "-c", "sleep 30")
    }
}
