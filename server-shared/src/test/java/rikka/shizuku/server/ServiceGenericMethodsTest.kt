package rikka.shizuku.server

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.ShizukuApiConstants

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServiceGenericMethodsTest {

    private lateinit var service: TestService

    @Before
    fun setUp() {
        // Robolectric's Binder reports the calling uid/pid configured here; make sure that differs
        // from the real server identity so the same-process fast paths are not taken.
        ShadowBinder.setCallingUid(CALLING_UID)
        ShadowBinder.setCallingPid(CALLING_PID)
        service = TestService()
    }

    @Test
    fun `attachApplication validates package ownership and dispatches the legacy reply`() {
        service.packages = listOf(PACKAGE_NAME)
        service.configManager.allow(CALLING_UID)

        val application = FakeApplication()
        val arguments = Bundle().apply {
            putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, PACKAGE_NAME)
            putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION)
        }

        service.attachApplication(application, arguments)

        val reply = application.lastBindBundle
        assertNotNull(reply)
        assertEquals(ShizukuApiConstants.SERVER_VERSION, reply!!.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION))
        assertEquals(
            ShizukuApiConstants.SERVER_PATCH_VERSION,
            reply.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION)
        )
        assertTrue(reply.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, false))

        val record = service.clientManager.findClient(CALLING_UID, CALLING_PID)
        assertNotNull(record)
        assertTrue(record!!.allowed)
        assertEquals(PACKAGE_NAME, record.packageName)
    }

    @Test
    fun `attachApplication rejects a package the caller does not own`() {
        service.packages = listOf(OTHER_PACKAGE)

        val application = FakeApplication()
        val arguments = Bundle().apply {
            putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, PACKAGE_NAME)
            putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, ShizukuApiConstants.SERVER_VERSION)
        }

        assertThrows(SecurityException::class.java) {
            service.attachApplication(application, arguments)
        }
        assertNull(service.clientManager.findClient(CALLING_UID, CALLING_PID))
        assertNull(application.lastBindBundle)
    }

    @Test
    fun `attachApplication rejects clients below the minimum supported API version`() {
        service.packages = listOf(PACKAGE_NAME)
        service.configManager.allow(CALLING_UID)

        val arguments = Bundle().apply {
            putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, PACKAGE_NAME)
            putInt(
                ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION,
                Service.MIN_CLIENT_API_VERSION - 1
            )
        }

        assertThrows(SecurityException::class.java) {
            service.attachApplication(FakeApplication(), arguments)
        }
    }

    @Test
    fun `checkSelfPermission accepts the manager, attached clients and verified runtime grants`() {
        service.manager = true
        assertTrue(service.checkSelfPermission())

        service.manager = false
        service.configManager.allow(CALLING_UID)
        service.clientManager.addClient(
            CALLING_UID,
            CALLING_PID,
            FakeApplication(),
            PACKAGE_NAME,
            ShizukuApiConstants.SERVER_VERSION
        )
        assertTrue(service.checkSelfPermission())
    }

    @Test
    fun `checkSelfPermission falls back to the runtime permission for verified installs`() {
        service.configManager.identityOk = true
        service.packages = listOf(PACKAGE_NAME)
        service.permissionResult = PackageManager.PERMISSION_GRANTED
        service.policy = FakePolicy(manager = false)

        assertTrue(service.checkSelfPermission())
        assertEquals(listOf(CALLING_UID), service.configManager.connections)
    }

    @Test
    fun `checkSelfPermission fails closed when the runtime permission is denied or unverifiable`() {
        service.policy = FakePolicy(manager = false)
        service.packages = listOf(PACKAGE_NAME)

        service.permissionResult = PackageManager.PERMISSION_DENIED
        assertFalse(service.checkSelfPermission())

        service.permissionResult = PackageManager.PERMISSION_GRANTED
        service.configManager.identityOk = false
        assertFalse(service.checkSelfPermission())
    }

    @Test
    fun `flag getters and setters are manager only and report capability changes`() {
        service.manager = true
        val flags = ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_BINDER_PROXY

        service.updateFlagsForUid(
            CALLING_UID,
            ConfigManager.MASK_PERMISSION or NightProtocol.MASK_CAPABILITIES,
            flags
        )

        assertEquals(
            ConfigManager.FLAG_ALLOWED,
            service.getFlagsForUid(CALLING_UID, ConfigManager.MASK_PERMISSION)
        )
        assertEquals(
            NightProtocol.CAP_BINDER_PROXY,
            service.getFlagsForUid(CALLING_UID, NightProtocol.MASK_CAPABILITIES)
        )
        assertEquals(listOf(CALLING_UID to NightProtocol.CAP_BINDER_PROXY), service.capabilityUpdates)

        service.manager = false
        assertThrows(SecurityException::class.java) {
            service.getFlagsForUid(CALLING_UID, ConfigManager.MASK_PERMISSION)
        }
        assertThrows(SecurityException::class.java) {
            service.updateFlagsForUid(CALLING_UID, ConfigManager.MASK_PERMISSION, 0)
        }
    }

    @Test
    fun `permission confirmation replies are matched against the prompt ledger`() {
        service.manager = true
        val application = FakeApplication()
        service.clientManager.addClient(
            CALLING_UID,
            CALLING_PID,
            application,
            PACKAGE_NAME,
            ShizukuApiConstants.SERVER_VERSION
        )

        service.requestPermission(REQUEST_CODE)
        assertEquals(listOf(REQUEST_CODE), service.confirmations)

        // Unsolicited reply for a different request code must be ignored.
        service.dispatchPermissionConfirmationResult(
            CALLING_UID,
            CALLING_PID,
            REQUEST_CODE + 1,
            replyBundle(allowed = true)
        )
        assertNull(service.configManager.find(CALLING_UID))
        assertTrue(application.permissionResults.isEmpty())

        service.dispatchPermissionConfirmationResult(
            CALLING_UID,
            CALLING_PID,
            REQUEST_CODE,
            replyBundle(allowed = true)
        )
        assertNotNull(service.configManager.find(CALLING_UID))
        assertTrue(service.configManager.find(CALLING_UID)!!.isAllowed())
        assertEquals(listOf(REQUEST_CODE to true), application.permissionResults)
    }

    @Test
    fun `expired permission confirmation replies are rejected`() {
        service.manager = true
        val application = FakeApplication()
        service.clientManager.addClient(
            CALLING_UID,
            CALLING_PID,
            application,
            PACKAGE_NAME,
            ShizukuApiConstants.SERVER_VERSION
        )

        service.requestPermission(REQUEST_CODE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(Service.PERMISSION_PROMPT_TTL_MS + 1_000L))

        service.dispatchPermissionConfirmationResult(
            CALLING_UID,
            CALLING_PID,
            REQUEST_CODE,
            replyBundle(allowed = true)
        )

        assertNull(service.configManager.find(CALLING_UID))
        assertEquals(listOf(REQUEST_CODE to false), application.permissionResults)
    }

    @Test
    fun `capability enforcement rejects missing capabilities and accepts defaults`() {
        service.configManager.allowWithFlags(
            CALLING_UID,
            ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC
        )
        service.clientManager.addClient(
            CALLING_UID,
            CALLING_PID,
            FakeApplication(),
            PACKAGE_NAME,
            ShizukuApiConstants.SERVER_VERSION
        )

        assertThrows(SecurityException::class.java) {
            service.getSystemProperty("ro.nightzuku.test", "fallback")
        }

        service.configManager.allowWithFlags(CALLING_UID, ConfigManager.FLAG_ALLOWED)
        assertEquals("fallback", service.getSystemProperty("ro.nightzuku.test", "fallback"))
    }

    @Test
    fun `one time grants satisfy capability checks without a stored decision`() {
        service.clientManager.grantOneTimeAccess(CALLING_UID)
        service.clientManager.addClient(
            CALLING_UID,
            CALLING_PID,
            FakeApplication(),
            PACKAGE_NAME,
            ShizukuApiConstants.SERVER_VERSION
        )
        assertEquals("fallback", service.getSystemProperty("ro.nightzuku.test", "fallback"))

        // Embedding managers may keep the one-time grant in their own ledger; a live allowed
        // record without a stored allow/deny decision must be treated the same way.
        service.clientManager.revokeOneTimeAccess(CALLING_UID)
        val record = service.clientManager.findClient(CALLING_UID, CALLING_PID)!!
        record.allowed = true
        assertEquals("fallback", service.getSystemProperty("ro.nightzuku.test", "fallback"))
    }

    @Test
    fun `exit is manager only, uses the policy code and fails closed without a policy`() {
        service = TestService(policy = FakePolicy(manager = true))
        service.manager = true
        service.exit()
        assertEquals(FakePolicy.EXIT_CODE, service.terminatedWith)

        service = TestService(policy = FakePolicy(manager = false))
        service.manager = false
        assertThrows(SecurityException::class.java) { service.exit() }
        assertNull(service.terminatedWith)

        service = TestService(policy = null)
        service.manager = true
        assertThrows(SecurityException::class.java) { service.exit() }
        assertNull(service.terminatedWith)
    }

    @Test
    fun `package change broadcasts reach the package changed hook`() {
        val changed = ArrayList<Intent>()
        service.onPackageChangedHook = { changed.add(it) }

        val intent = Intent("com.example.PACKAGE_CHANGED")
        service.dispatchPackageChanged(intent)

        assertEquals(listOf(intent), changed)
    }

    private fun replyBundle(allowed: Boolean, oneTime: Boolean = false): Bundle = Bundle().apply {
        putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, oneTime)
    }

    private class TestService(
        var policy: ServerPolicy? = null
    ) : Service<TestUserServiceManager, ClientManager<TestConfigManager>, TestConfigManager>() {

        var manager: Boolean = false
        var packages: List<String> = emptyList()
        var permissionResult: Int = PackageManager.PERMISSION_DENIED
        var terminatedWith: Int? = null
        var onPackageChangedHook: (Intent) -> Unit = {}
        val capabilityUpdates = ArrayList<Pair<Int, Int>>()
        val confirmations = ArrayList<Int>()

        override val serverPolicy: ServerPolicy? get() = policy

        override fun onCreateUserServiceManager(): TestUserServiceManager = TestUserServiceManager()

        override fun onCreateConfigManager(): TestConfigManager = TestConfigManager()

        override fun onCreateClientManager(): ClientManager<TestConfigManager> =
            ClientManager(configManager)

        override fun checkCallerManagerPermission(
            func: String,
            callingUid: Int,
            callingPid: Int
        ): Boolean = manager

        override fun packagesForUid(uid: Int): List<String> = packages

        override fun checkPermissionForUid(permission: String, pid: Int, uid: Int): Int =
            permissionResult

        override fun terminateServer(exitCode: Int) {
            terminatedWith = exitCode
        }

        override fun onCapabilitiesUpdated(uid: Int, flags: Int) {
            capabilityUpdates.add(uid to flags)
        }

        override fun onPackageChanged(intent: Intent) {
            onPackageChangedHook(intent)
        }

        override fun showPermissionConfirmation(
            requestCode: Int,
            clientRecord: ClientRecord,
            callingUid: Int,
            callingPid: Int,
            userId: Int
        ) {
            confirmations.add(requestCode)
        }
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
        ): String = ""
    }

    private class TestConfigManager : ConfigManager(), ConfigManager.ClientAccessVerifier {

        private class Entry(var flagsValue: Int) : ConfigPackageEntry(), ConfigFlagsEntry {
            override fun isAllowed(): Boolean =
                (flagsValue and ConfigManager.FLAG_ALLOWED) != 0

            override fun isDenied(): Boolean =
                (flagsValue and ConfigManager.FLAG_DENIED) != 0

            override fun capabilityFlags(): Int = flagsValue
        }

        private val entries = LinkedHashMap<Int, Entry>()
        var paused: Boolean = false
        var identityOk: Boolean = true
        val connections = ArrayList<Int>()

        fun allow(uid: Int) = allowWithFlags(uid, ConfigManager.FLAG_ALLOWED)

        fun allowWithFlags(uid: Int, flags: Int) {
            entries[uid] = Entry(flags)
        }

        override fun find(uid: Int): ConfigPackageEntry? = entries[uid]

        override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) {
            val entry = entries.getOrPut(uid) { Entry(0) }
            entry.flagsValue = (entry.flagsValue and mask.inv()) or (mask and values)
        }

        override fun remove(uid: Int) {
            entries.remove(uid)
        }

        override fun isAccessPaused(): Boolean = paused

        override fun verifyClientIdentity(uid: Int, packageName: String): Boolean = identityOk

        override fun recordConnection(uid: Int) {
            connections.add(uid)
        }
    }

    private class FakePolicy(
        private val manager: Boolean,
        override val managerExitCode: Int = EXIT_CODE
    ) : ServerPolicy {
        override val managerPackageName: String = PACKAGE_NAME
        override val apiPermission: String = "com.example.permission.API"
        override val legacyPermission: String? = null

        override fun isManager(callingUid: Int, callingPid: Int): Boolean = manager

        companion object {
            const val EXIT_CODE = 77
        }
    }

    private class FakeApplication : IShizukuApplication.Stub() {
        var lastBindBundle: Bundle? = null
        val permissionResults = ArrayList<Pair<Int, Boolean>>()

        override fun bindApplication(data: Bundle?) {
            lastBindBundle = data
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
            permissionResults.add(
                requestCode to (data?.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false) ?: false)
            )
        }
    }

    companion object {
        private const val CALLING_UID = 10_234
        private const val CALLING_PID = 4_242
        private const val REQUEST_CODE = 0x51
        private const val PACKAGE_NAME = "com.example.client"
        private const val OTHER_PACKAGE = "com.example.other"
    }
}
