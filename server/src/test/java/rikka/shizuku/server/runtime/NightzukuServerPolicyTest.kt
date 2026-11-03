package rikka.shizuku.server.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NightzukuServerPolicyTest {

    private val config = ServerConfig.fromProperties(
        properties = mapOf(
            ServerConfig.PROP_MANAGER_PACKAGE to "com.example.manager",
            ServerConfig.PROP_API_PERMISSION to "com.example.permission.API",
            ServerConfig.PROP_LEGACY_PERMISSION to "com.example.permission.LEGACY",
            ServerConfig.PROP_MANAGER_EXIT_CODE to "66"
        )
    )

    @Test
    fun `policy exposes the configured deployment identity`() {
        val policy = NightzukuServerPolicy(config, appIdResolver = { MANAGER_APP_ID })

        assertEquals("com.example.manager", policy.managerPackageName)
        assertEquals("com.example.permission.API", policy.apiPermission)
        assertEquals("com.example.permission.LEGACY", policy.legacyPermission)
        assertEquals(66, policy.managerExitCode)
    }

    @Test
    fun `caller in user zero with the manager app id is the manager`() {
        val policy = NightzukuServerPolicy(config, appIdResolver = { MANAGER_APP_ID })

        assertTrue(policy.isManager(uidFor(0, MANAGER_APP_ID), 42))
        assertTrue(policy.isManager(uidFor(0, MANAGER_APP_ID), 9_999))
    }

    @Test
    fun `different app id is not the manager`() {
        val policy = NightzukuServerPolicy(config, appIdResolver = { MANAGER_APP_ID })

        assertFalse(policy.isManager(uidFor(0, MANAGER_APP_ID + 1), 42))
    }

    @Test
    fun `same app id in a secondary user is not the manager`() {
        val policy = NightzukuServerPolicy(config, appIdResolver = { MANAGER_APP_ID })

        assertFalse(policy.isManager(uidFor(10, MANAGER_APP_ID), 42))
    }

    @Test
    fun `package lookup failure fails closed`() {
        val missing = NightzukuServerPolicy(config, appIdResolver = { null })
        assertFalse(missing.isManager(uidFor(0, MANAGER_APP_ID), 42))

        val throwing = NightzukuServerPolicy(config, appIdResolver = { error("package service unavailable") })
        assertFalse(throwing.isManager(uidFor(0, MANAGER_APP_ID), 42))
    }

    @Test
    fun `non positive app id fails closed`() {
        val policy = NightzukuServerPolicy(config, appIdResolver = { 0 })
        assertFalse(policy.isManager(uidFor(0, 0), 42))
    }

    @Test
    fun `signature verifier is an overridable hook`() {
        val trusted = NightzukuServerPolicy(config, { MANAGER_APP_ID }, { true })
        val revoked = NightzukuServerPolicy(config, { MANAGER_APP_ID }, { false })

        assertTrue(trusted.verifyManagerSignature())
        assertFalse(revoked.verifyManagerSignature())
    }

    private fun uidFor(userId: Int, appId: Int): Int = userId * 100_000 + appId

    companion object {
        private const val MANAGER_APP_ID = 10_234
    }
}
