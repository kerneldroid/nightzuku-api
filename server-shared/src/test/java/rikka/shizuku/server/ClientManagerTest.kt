package rikka.shizuku.server

import android.os.Bundle
import moe.shizuku.server.IShizukuApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientManagerTest {

    private lateinit var config: FakeConfigManager
    private lateinit var manager: TestClientManager

    @Before
    fun setUp() {
        config = FakeConfigManager()
        manager = TestClientManager(config)
    }

    @Test
    fun `addClient registers the record and find helpers locate it`() {
        val client = FakeApplication()
        val record = manager.addClient(TEST_UID, TEST_PID, client, PACKAGE_NAME, API_VERSION)

        assertSame(record, manager.findClient(TEST_UID, TEST_PID))
        assertEquals(listOf(record), manager.findClients(TEST_UID))
        assertSame(record, manager.requireClient(TEST_UID, TEST_PID))
        assertSame(record, manager.attachedClients().single())
    }

    @Test
    fun `permission gate accepts allowed clients and rejects unknown or denied callers`() {
        config.allow(TEST_UID, allowed = true)
        manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)
        manager.requireClient(TEST_UID, TEST_PID, requiresPermission = true)

        manager.addClient(SECOND_UID, TEST_PID, FakeApplication(), OTHER_PACKAGE, API_VERSION)
        val denied = runCatching {
            manager.requireClient(SECOND_UID, TEST_PID, requiresPermission = true)
        }.exceptionOrNull()
        assertTrue(denied is SecurityException)

        val unknown = runCatching { manager.requireClient(UNKNOWN_UID, TEST_PID) }.exceptionOrNull()
        assertTrue(unknown is IllegalStateException)
    }

    @Test
    fun `replacing a client for the same uid and pid notifies removal of the old record`() {
        val first = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)
        val second = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)

        assertEquals(listOf(first), manager.removed)
        assertSame(second, manager.findClient(TEST_UID, TEST_PID))
        assertEquals(1, manager.findClients(TEST_UID).size)
    }

    @Test
    fun `one time grants allow a denied client until revoked`() {
        assertFalse(manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!.allowed)

        manager.grantOneTimeAccess(TEST_UID)
        val granted = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!
        assertTrue(granted.allowed)
        assertTrue(config.connections.contains(TEST_UID))

        assertTrue(manager.revokeOneTimeAccess(TEST_UID))
        assertFalse(manager.revokeOneTimeAccess(TEST_UID))
        val revoked = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!
        assertFalse(revoked.allowed)
    }

    @Test
    fun `paused access overrides stored permission`() {
        config.allow(TEST_UID, allowed = true)
        config.paused = true

        val record = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!

        assertFalse(record.allowed)
    }

    @Test
    fun `identity verification failure clears the allowance and records successful connections`() {
        config.allow(TEST_UID, allowed = true)
        config.identityOk = false
        assertFalse(manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!.allowed)

        config.identityOk = true
        val record = manager.addClient(TEST_UID, TEST_PID, FakeApplication(), PACKAGE_NAME, API_VERSION)!!
        assertTrue(record.allowed)
        assertEquals(listOf(TEST_UID), config.connections)
    }

    @Test
    fun `legacy application registry stores and unregisters applications`() {
        val legacy = FakeApplication()
        manager.registerLegacyClient(TEST_UID, TEST_PID, legacy)
        assertSame(legacy, manager.findLegacyClient(TEST_UID, TEST_PID))

        manager.unregisterLegacyClient(TEST_UID, TEST_PID)
        assertNull(manager.findLegacyClient(TEST_UID, TEST_PID))
    }

    @Test
    fun `notifyProcessDied removes client and legacy registrations and reports the death`() {
        val client = FakeApplication()
        manager.addClient(TEST_UID, TEST_PID, client, PACKAGE_NAME, API_VERSION)
        manager.registerLegacyClient(TEST_UID, TEST_PID, FakeApplication())

        manager.notifyProcessDied(TEST_UID, TEST_PID)

        assertNull(manager.findClient(TEST_UID, TEST_PID))
        assertNull(manager.findLegacyClient(TEST_UID, TEST_PID))
        assertEquals(listOf(Triple(TEST_UID, TEST_PID, PACKAGE_NAME)), manager.died)
        assertEquals(1, manager.removed.size)
    }

    private class TestClientManager(configManager: ConfigManager) :
        ClientManager<ConfigManager>(configManager) {

        val removed = ArrayList<ClientRecord>()
        val died = ArrayList<Triple<Int, Int, String?>>()

        override fun onClientRemoved(clientRecord: ClientRecord) {
            removed.add(clientRecord)
        }

        override fun onClientDied(uid: Int, pid: Int, packageName: String?) {
            died.add(Triple(uid, pid, packageName))
        }

        fun attachedClients(): List<ClientRecord> = super.snapshotClients()
    }

    private class FakeConfigManager : ConfigManager(), ConfigManager.ClientAccessVerifier {
        private class Entry(private var allowed: Boolean) : ConfigPackageEntry() {
            override fun isAllowed(): Boolean = allowed

            override fun isDenied(): Boolean = !allowed
        }

        private val entries = HashMap<Int, Entry>()
        var paused: Boolean = false
        var identityOk: Boolean = true
        val connections = ArrayList<Int>()

        fun allow(uid: Int, allowed: Boolean) {
            entries[uid] = Entry(allowed)
        }

        override fun find(uid: Int): ConfigPackageEntry? = entries[uid]

        override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) = Unit

        override fun remove(uid: Int) {
            entries.remove(uid)
        }

        override fun isAccessPaused(): Boolean = paused

        override fun verifyClientIdentity(uid: Int, packageName: String): Boolean = identityOk

        override fun recordConnection(uid: Int) {
            connections.add(uid)
        }
    }

    private class FakeApplication : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle?) = Unit

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) = Unit
    }

    companion object {
        private const val API_VERSION = 13
        private const val TEST_UID = 10_234
        private const val SECOND_UID = 10_235
        private const val UNKNOWN_UID = 10_999
        private const val TEST_PID = 4_242
        private const val PACKAGE_NAME = "com.example.client"
        private const val OTHER_PACKAGE = "com.example.other"
    }
}
