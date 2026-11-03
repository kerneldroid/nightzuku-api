package rikka.shizuku.server.runtime

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.io.File
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IShizukuApplication
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
import rikka.shizuku.ShizukuApiConstants

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NightzukuServerServiceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var config: ServerConfig
    private lateinit var server: TestServer

    @Before
    fun setUp() {
        ShadowBinder.setCallingUid(CALLING_UID)
        ShadowBinder.setCallingPid(CALLING_PID)
        config = configFor(temporaryFolder.newFolder("config"))
        server = NightzukuServerService.withConfig(config) { TestServer() }
        server.installedPackages = listOf(PACKAGE_NAME)
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
    }

    @Test
    fun `attachApplication over the legacy wire records the client`() {
        val application = RecordingApplication()
        val arguments = Bundle().apply {
            putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, PACKAGE_NAME)
            putInt(
                ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION,
                ShizukuApiConstants.SERVER_VERSION
            )
        }

        val reply = transact(
            server,
            TRANSACTION_ATTACH_APPLICATION,
            ShizukuApiConstants.BINDER_DESCRIPTOR
        ) { data ->
            data.writeStrongBinder(application)
            data.writeTypedObject(arguments, 0)
        }

        reply.readException()
        reply.recycle()

        val record = server.clientManager.findClient(CALLING_UID, CALLING_PID)
        assertNotNull(record)
        assertEquals(PACKAGE_NAME, record!!.packageName)
        assertEquals(ShizukuApiConstants.SERVER_VERSION, record.apiVersion)
    }

    @Test
    fun `attachApplication rejects packages the caller does not own`() {
        server.installedPackages = listOf("com.example.other")
        val arguments = Bundle().apply {
            putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, PACKAGE_NAME)
            putInt(
                ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION,
                ShizukuApiConstants.SERVER_VERSION
            )
        }

        val reply = transact(
            server,
            TRANSACTION_ATTACH_APPLICATION,
            ShizukuApiConstants.BINDER_DESCRIPTOR
        ) { data ->
            data.writeStrongBinder(RecordingApplication())
            data.writeTypedObject(arguments, 0)
        }

        val error = runCatching { reply.readException() }.exceptionOrNull()
        reply.recycle()
        assertTrue(error is SecurityException)
        assertNull(server.clientManager.findClient(CALLING_UID, CALLING_PID))
    }

    @Test
    fun `night attach handshake is routed through the service onTransact`() {
        val reply = attachToNight(ExecCallback())

        reply.readException()
        assertEquals(NightProtocol.ATTACH_STATUS_OK, reply.readInt())
        assertEquals(NightProtocol.PROTOCOL_VERSION, reply.readInt())
        assertEquals(NightProtocol.MIN_PROTOCOL_VERSION, reply.readInt())
        reply.readInt()
        reply.readInt()
        reply.readInt()
        reply.readString()
        reply.recycle()

        assertTrue(server.nightEndpoint.hasNightCallback(CALLING_PID))
        assertNotNull(server.clientManager.findClient(CALLING_UID, CALLING_PID))
    }

    @Test
    fun `execFast is served by the Night endpoint`() {
        server.clientManager.grantOneTimeAccess(CALLING_UID)
        attachToNight().recycle()

        val reply = transact(server, NightProtocol.TRANSACTION_EXEC_FAST, NightProtocol.DESCRIPTOR) { data ->
            data.writeStringArray(SHELL_WITH_OUTPUT)
            data.writeStringArray(null)
            data.writeString(null)
            data.writeLong(NightProtocol.EXEC_FAST_DEFAULT_TIMEOUT_MS)
        }

        reply.readException()
        assertEquals(3, reply.readInt())
        assertEquals(0, reply.readInt())
        assertEquals("stdout", String(reply.createByteArray()!!, Charsets.UTF_8))
        assertEquals("stderr", String(reply.createByteArray()!!, Charsets.UTF_8))
        reply.recycle()
    }

    @Test
    fun `permission prompt intent carries the manager request extras`() {
        attachToNight().recycle()

        server.requestPermissionFromNightWire(CALLING_UID, CALLING_PID, REQUEST_CODE)

        assertEquals(1, server.permissionIntents.size)
        val intent = server.permissionIntents.first()
        assertEquals(config.requestPermissionAction, intent.action)
        assertEquals(config.managerPackageName, intent.`package`)
        assertEquals(CALLING_UID, intent.getIntExtra(NightzukuServerService.EXTRA_UID, -1))
        assertEquals(CALLING_PID, intent.getIntExtra(NightzukuServerService.EXTRA_PID, -1))
        assertEquals(
            REQUEST_CODE,
            intent.getIntExtra(NightzukuServerService.EXTRA_REQUEST_CODE, -1)
        )
        assertEquals(0, intent.getIntExtra(NightzukuServerService.EXTRA_USER_ID, -1))
    }

    @Test
    fun `access pause propagates to the config and user service manager`() {
        server.clientManager.grantOneTimeAccess(CALLING_UID)
        attachToNight().recycle()
        val record = server.clientManager.findClient(CALLING_UID, CALLING_PID)!!
        assertTrue(record.allowed)

        server.setAccessPaused(true)

        assertTrue(server.configManager.isAccessPaused())
        assertTrue(server.userServiceManager.isAccessPaused())
        assertFalse(record.allowed)

        server.setAccessPaused(false)
        assertFalse(server.configManager.isAccessPaused())
        assertFalse(server.userServiceManager.isAccessPaused())
    }

    @Test
    fun `nightdog is explicitly unavailable in this runtime`() {
        assertFalse(server.getNightDogEnabled(true))
        assertFalse(server.getNightDogEnabled(false))
    }

    @Test
    fun `client process death removes the record`() {
        server.clientManager.grantOneTimeAccess(CALLING_UID)
        attachToNight().recycle()
        assertNotNull(server.clientManager.findClient(CALLING_UID, CALLING_PID))

        server.onClientProcessDied(CALLING_UID, CALLING_PID)

        assertNull(server.clientManager.findClient(CALLING_UID, CALLING_PID))
    }

    private fun attachToNight(callback: IBinder? = null): Parcel =
        transact(server, NightProtocol.TRANSACTION_ATTACH, NightProtocol.DESCRIPTOR) { data ->
            data.writeStrongBinder(callback)
            data.writeInt(NightProtocol.PROTOCOL_VERSION)
            data.writeString(PACKAGE_NAME)
        }

    private fun transact(
        binder: IBinder,
        code: Int,
        descriptor: String,
        write: (Parcel) -> Unit
    ): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            data.setDataPosition(0)
            assertTrue(binder.transact(code, data, reply, 0))
        } finally {
            data.recycle()
        }
        reply.setDataPosition(0)
        return reply
    }

    class TestServer : NightzukuServerService() {

        var installedPackages: List<String> = emptyList()

        val permissionIntents = ArrayList<Intent>()

        override fun packagesForUid(uid: Int): List<String> = installedPackages

        override fun createNightEndpoint(): NightServiceEndpoint =
            object : NightServiceEndpoint(this@TestServer) {
                override fun packagesForUid(uid: Int): List<String> = installedPackages
            }

        override fun startPermissionRequest(intent: Intent, userId: Int) {
            permissionIntents.add(intent)
        }
    }

    private class RecordingApplication : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle?) = Unit

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) = Unit
    }

    private class ExecCallback : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == NightProtocol.CALLBACK_ON_EXEC_RESULT) {
                data.enforceInterface(NightProtocol.CALLBACK_DESCRIPTOR)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val CALLING_UID = 10_234
        private const val CALLING_PID = 4_242
        private const val PACKAGE_NAME = "com.example.client"
        private const val REQUEST_CODE = 0x51

        /**
         * `IBinder.FIRST_CALL_TRANSACTION + 17` from `IShizukuService.aidl`; the generated Stub
         * constant is package-private.
         */
        private const val TRANSACTION_ATTACH_APPLICATION = 18

        private val SHELL_WITH_OUTPUT = arrayOf(
            "/bin/sh",
            "-c",
            "printf stdout; printf stderr 1>&2; exit 3"
        )

        fun configFor(dir: File): ServerConfig = ServerConfig.fromProperties(
            properties = mapOf(
                ServerConfig.PROP_CONFIG_DIR to dir.absolutePath,
                ServerConfig.PROP_CONFIG_FILE to File(dir, "nightzuku.json").absolutePath
            )
        )
    }
}
