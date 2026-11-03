package rikka.shizuku.server.security

import android.os.Binder
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ConfigFlagsEntry
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ConfigPackageEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TransactGuardTest {

    @Test
    fun `verifyTargetBinder rejects null, self-referential, and privileged descriptor binders`() {
        val legacyEndpoint = Binder()
        val nightEndpoint = Binder()

        assertThrows(IllegalArgumentException::class.java) {
            TransactGuard.verifyTargetBinder(null, legacyEndpoint, nightEndpoint)
        }
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(legacyEndpoint, legacyEndpoint, nightEndpoint)
        }
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(nightEndpoint, legacyEndpoint, nightEndpoint)
        }

        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(
                DescriptorBinder(ShizukuApiConstants.BINDER_DESCRIPTOR),
                legacyEndpoint,
                nightEndpoint
            )
        }
        assertThrows(SecurityException::class.java) {
            TransactGuard.verifyTargetBinder(
                DescriptorBinder(NightProtocol.DESCRIPTOR),
                legacyEndpoint,
                nightEndpoint
            )
        }

        TransactGuard.verifyTargetBinder(
            DescriptorBinder("android.content.pm.IPackageManager"),
            legacyEndpoint,
            nightEndpoint
        )
    }

    @Test
    fun `effectiveCapabilities respects accessPaused, grant status, and explicit capability masks`() {
        val allowedDefault = TestEntry(ConfigManager.FLAG_ALLOWED)
        assertEquals(
            NightProtocol.DEFAULT_CAPABILITIES,
            TransactGuard.effectiveCapabilities(allowedDefault, accessPaused = false)
        )
        assertEquals(
            0,
            TransactGuard.effectiveCapabilities(allowedDefault, accessPaused = true)
        )

        val denied = TestEntry(ConfigManager.FLAG_DENIED)
        assertEquals(0, TransactGuard.effectiveCapabilities(denied, accessPaused = false))
        assertEquals(0, TransactGuard.effectiveCapabilities(null, accessPaused = false))

        val shellOnly = TestEntry(ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC)
        assertEquals(
            NightProtocol.CAP_SHELL_EXEC,
            TransactGuard.effectiveCapabilities(shellOnly, accessPaused = false)
        )
        assertEquals(
            NightProtocol.DEFAULT_CAPABILITIES,
            TransactGuard.effectiveCapabilities(null, accessPaused = false, oneTimeGranted = true)
        )
        assertEquals(
            0,
            TransactGuard.effectiveCapabilities(null, accessPaused = true, oneTimeGranted = true)
        )
    }

    @Test
    fun `enforceCapability blocks paused clients and missing capabilities while exempting manager`() {
        val configManager = TestConfigManager()
        configManager.entries[CLIENT_UID] =
            TestEntry(ConfigManager.FLAG_ALLOWED or NightProtocol.CAP_SHELL_EXEC)

        TransactGuard.enforceCapability(
            configManager,
            callingUid = CLIENT_UID,
            isManager = false,
            requiredCapability = NightProtocol.CAP_SHELL_EXEC,
            operationName = "execFast"
        )

        assertThrows(SecurityException::class.java) {
            TransactGuard.enforceCapability(
                configManager,
                callingUid = CLIENT_UID,
                isManager = false,
                requiredCapability = NightProtocol.CAP_BINDER_PROXY,
                operationName = "transactRemote"
            )
        }

        configManager.paused = true
        assertThrows(SecurityException::class.java) {
            TransactGuard.enforceCapability(
                configManager,
                callingUid = CLIENT_UID,
                isManager = false,
                requiredCapability = NightProtocol.CAP_SHELL_EXEC,
                operationName = "execFast"
            )
        }

        TransactGuard.enforceCapability(
            configManager,
            callingUid = MANAGER_UID,
            isManager = true,
            requiredCapability = NightProtocol.CAP_BINDER_PROXY,
            operationName = "transactRemote"
        )
    }

    private class DescriptorBinder(private val descriptor: String) : Binder() {
        override fun getInterfaceDescriptor(): String = descriptor
    }

    private class TestEntry(private val flagsValue: Int) : ConfigPackageEntry(), ConfigFlagsEntry {
        override fun isAllowed(): Boolean = (flagsValue and ConfigManager.FLAG_ALLOWED) != 0

        override fun isDenied(): Boolean = (flagsValue and ConfigManager.FLAG_DENIED) != 0

        override fun capabilityFlags(): Int = flagsValue
    }

    private class TestConfigManager : ConfigManager(), ConfigManager.ClientAccessVerifier {
        val entries = HashMap<Int, ConfigPackageEntry>()
        var paused: Boolean = false

        override fun find(uid: Int): ConfigPackageEntry? = entries[uid]

        override fun update(uid: Int, packages: MutableList<String>?, mask: Int, values: Int) = Unit

        override fun remove(uid: Int) {
            entries.remove(uid)
        }

        override fun isAccessPaused(): Boolean = paused

        override fun verifyClientIdentity(uid: Int, packageName: String): Boolean = true

        override fun recordConnection(uid: Int) = Unit
    }

    companion object {
        private const val CLIENT_UID = 10_200
        private const val MANAGER_UID = 10_001
    }
}
