package rikka.shizuku.server

import java.io.File
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.util.PackageSignatureVerifier
import rikka.shizuku.server.util.PackageSignatureVerifier.ProbeResult
import rikka.shizuku.server.util.PackageSignatureVerifier.Snapshot
import rikka.shizuku.server.util.UserHandleCompat

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JsonConfigManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `update round-trips flags, capabilities, packages, signers and last connection`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val file = configFile()
        val manager = newManager(file, resolver)

        manager.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )
        manager.update(
            TEST_UID,
            null,
            NightProtocol.MASK_CAPABILITIES,
            NightProtocol.CAP_SHELL_EXEC or NightProtocol.CAP_BINDER_PROXY
        )
        manager.recordConnection(TEST_UID, 123_456_789L)

        val entry = manager.find(TEST_UID) as JsonConfigPackageEntry
        assertTrue(entry.isAllowed())
        assertFalse(entry.isDenied())
        assertEquals(listOf(PACKAGE_A), entry.packages)
        assertEquals(listOf(DIGEST_A), entry.signers)
        assertEquals(
            NightProtocol.CAP_SHELL_EXEC or NightProtocol.CAP_BINDER_PROXY,
            entry.capabilities
        )
        assertTrue(entry.hasCapability(NightProtocol.CAP_SHELL_EXEC))
        assertFalse(entry.hasCapability(NightProtocol.CAP_USER_SERVICE))

        val reloaded = newManager(file, resolver).find(TEST_UID) as JsonConfigPackageEntry
        assertEquals(entry.flags, reloaded.flags)
        assertEquals(listOf(PACKAGE_A), reloaded.packages)
        assertEquals(listOf(DIGEST_A), reloaded.signers)
        assertEquals(123_456_789L, reloaded.lastConnectedAt)
        assertEquals(entry.capabilities, reloaded.capabilities)
    }

    @Test
    fun `save replaces the previous file atomically and leaves no temporary file behind`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.own(SECOND_UID, PACKAGE_B)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        resolver.present(SECOND_UID, PACKAGE_B, DIGEST_B)

        val file = configFile()
        val first = newManager(file, resolver)
        first.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        val second = newManager(file, resolver)
        second.update(
            SECOND_UID,
            mutableListOf(PACKAGE_B),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        assertFalse(File(file.path + ".new").exists())
        val raw = file.readText()
        assertTrue(raw.startsWith("{"))
        assertTrue(raw.contains("\"version\":${JsonConfigManager.SCHEMA_VERSION}"))
        assertTrue(raw.contains(PACKAGE_A))
        assertTrue(raw.contains(PACKAGE_B))

        val reloaded = newManager(file, resolver)
        assertNotNull(reloaded.find(TEST_UID))
        assertNotNull(reloaded.find(SECOND_UID))
        assertTrue(reloaded.find(TEST_UID)!!.isAllowed())
        assertTrue(reloaded.find(SECOND_UID)!!.isAllowed())
    }

    @Test
    fun `missing and corrupt files start empty and recover on the next write`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val file = configFile()

        val missing = newManager(file, resolver)
        assertNull(missing.find(TEST_UID))

        file.writeText("{ this is not valid json")
        val corrupt = newManager(file, resolver)
        assertNull(corrupt.find(TEST_UID))
        corrupt.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        val recovered = newManager(file, resolver)
        assertNotNull(recovered.find(TEST_UID))
        assertTrue(recovered.find(TEST_UID)!!.isAllowed())
    }

    @Test
    fun `load drops an entry for a uid that owns no packages`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, emptySet())
        val file = configFile()
        writeConfig(
            file,
            """
            {"version":${JsonConfigManager.SCHEMA_VERSION},"accessPaused":false,"packages":[
              {"uid":$TEST_UID,"flags":${ConfigManager.FLAG_ALLOWED},"packages":["$PACKAGE_A"],
               "signers":["$DIGEST_A"],"lastConnectedAt":0}
            ]}
            """.trimIndent()
        )

        val manager = newManager(file, resolver)
        assertNull(manager.find(TEST_UID))
        assertFalse(file.readText().contains(PACKAGE_A))
    }

    @Test
    fun `load deduplicates package names and drops stale ones`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, setOf(PACKAGE_A, PACKAGE_B))
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val file = configFile()
        writeConfig(
            file,
            """
            {"version":${JsonConfigManager.SCHEMA_VERSION},"accessPaused":false,"packages":[
              {"uid":$TEST_UID,"flags":${ConfigManager.FLAG_ALLOWED},
               "packages":["$PACKAGE_A","$PACKAGE_A","$STALE_PACKAGE"],
               "signers":["$DIGEST_A"],"lastConnectedAt":0}
            ]}
            """.trimIndent()
        )

        val entry = newManager(file, resolver).find(TEST_UID) as JsonConfigPackageEntry
        assertEquals(listOf(PACKAGE_A), entry.packages)
    }

    @Test
    fun `load backfills missing signer digests`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val file = configFile()
        writeConfig(
            file,
            """
            {"version":${JsonConfigManager.SCHEMA_VERSION},"accessPaused":false,"packages":[
              {"uid":$TEST_UID,"flags":${ConfigManager.FLAG_ALLOWED},"packages":["$PACKAGE_A"],
               "lastConnectedAt":0}
            ]}
            """.trimIndent()
        )

        val entry = newManager(file, resolver).find(TEST_UID) as JsonConfigPackageEntry
        assertEquals(listOf(DIGEST_A), entry.signers)
    }

    @Test
    fun `identity verification accepts the recorded signer and rotation lineage`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val manager = newManager(configFile(), resolver)
        manager.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        assertTrue(manager.verifyClientIdentity(TEST_UID, PACKAGE_A))

        resolver.present(TEST_UID, PACKAGE_A, DIGEST_B, lineage = setOf(DIGEST_A, DIGEST_B))
        assertTrue(manager.verifyClientIdentity(TEST_UID, PACKAGE_A))
        assertNotNull(manager.find(TEST_UID))
    }

    @Test
    fun `identity verification purges a grant after the signing certificate changes`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val manager = newManager(configFile(), resolver)
        manager.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        resolver.present(TEST_UID, PACKAGE_A, DIGEST_FOREIGN)
        assertFalse(manager.verifyClientIdentity(TEST_UID, PACKAGE_A))
        assertNull(manager.find(TEST_UID))
    }

    @Test
    fun `identity verification rejects a uid that no longer owns the package and keeps transient failures`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.present(TEST_UID, PACKAGE_A, DIGEST_A)
        val manager = newManager(configFile(), resolver)
        manager.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )

        resolver.own(TEST_UID, PACKAGE_B)
        assertFalse(manager.verifyClientIdentity(TEST_UID, PACKAGE_A))
        assertNull("configuration is purged once ownership is positively disproven", manager.find(TEST_UID))

        val transient = newManager(configFile(), resolver)
        transient.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )
        resolver.ownershipUnavailable = true
        assertFalse(transient.verifyClientIdentity(TEST_UID, PACKAGE_A))
        assertNotNull("transient lookup failures must not purge the grant", transient.find(TEST_UID))
    }

    @Test
    fun `allowedUids only reports allowed entries`() {
        val resolver = FakeClientIdentityResolver()
        resolver.own(TEST_UID, PACKAGE_A)
        resolver.own(SECOND_UID, PACKAGE_B)
        val manager = newManager(configFile(), resolver)

        manager.update(
            TEST_UID,
            mutableListOf(PACKAGE_A),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_ALLOWED
        )
        manager.update(
            SECOND_UID,
            mutableListOf(PACKAGE_B),
            ConfigManager.MASK_PERMISSION,
            ConfigManager.FLAG_DENIED
        )

        assertEquals(listOf(TEST_UID), manager.allowedUids())

        manager.remove(SECOND_UID)
        assertNull(manager.find(SECOND_UID))
    }

    @Test
    fun `accessPaused state is persisted across reloads`() {
        val resolver = FakeClientIdentityResolver()
        val file = configFile()
        val manager = newManager(file, resolver)
        assertFalse(manager.isAccessPaused())

        manager.setAccessPaused(true)
        assertTrue(manager.isAccessPaused())
        assertTrue(newManager(file, resolver).isAccessPaused())

        manager.setAccessPaused(false)
        assertFalse(newManager(file, resolver).isAccessPaused())
    }

    @Test
    fun `unknown schema versions are discarded`() {
        val resolver = FakeClientIdentityResolver()
        val file = configFile()
        writeConfig(
            file,
            """
            {"version":2,"accessPaused":true,"packages":[
              {"uid":$TEST_UID,"flags":${ConfigManager.FLAG_ALLOWED},"packages":["$PACKAGE_A"]}
            ]}
            """.trimIndent()
        )

        val manager = newManager(file, resolver)
        assertNull(manager.find(TEST_UID))
        assertFalse(manager.isAccessPaused())
    }

    private fun configFile(): File = File(temporaryFolder.newFolder(), "config.json")

    private fun newManager(file: File, resolver: ClientIdentityResolver): JsonConfigManager =
        JsonConfigManager(file, TEST_PERMISSION, resolver, importSystemGrantsOnLoad = false)

    private fun writeConfig(file: File, json: String) {
        file.writeText(json)
    }

    private class FakeClientIdentityResolver : ClientIdentityResolver {
        private val packages = HashMap<Int, Set<String>>()
        private val probes = HashMap<String, ProbeResult>()
        var ownershipUnavailable: Boolean = false

        fun own(uid: Int, vararg packageNames: String) {
            packages[uid] = packageNames.toSet()
        }

        fun own(uid: Int, packageNames: Set<String>) {
            packages[uid] = packageNames
        }

        fun present(uid: Int, packageName: String, digest: String, lineage: Set<String>? = null) {
            probes[packageName] = ProbeResult.Present(
                Snapshot(
                    packageName,
                    UserHandleCompat.getUserId(uid),
                    UserHandleCompat.getAppId(uid),
                    "/data/app/$packageName/base.apk",
                    setOf(digest),
                    false,
                    lineage
                )
            )
        }

        override fun packagesForUid(uid: Int): Set<String>? =
            if (ownershipUnavailable) null else packages[uid]

        override fun probePackage(packageName: String, userId: Int): ProbeResult =
            probes[packageName] ?: ProbeResult.Absent()
    }

    companion object {
        private const val TEST_PERMISSION = "com.example.permission.API"
        private const val TEST_UID = 10_234
        private const val SECOND_UID = 10_235
        private const val PACKAGE_A = "com.example.client"
        private const val PACKAGE_B = "com.example.other"
        private const val STALE_PACKAGE = "com.example.removed"

        private val DIGEST_A =
            requireNotNull(PackageSignatureVerifier.sha256Hex("certificate-a".toByteArray()))
        private val DIGEST_B =
            requireNotNull(PackageSignatureVerifier.sha256Hex("certificate-b".toByteArray()))
        private val DIGEST_FOREIGN =
            requireNotNull(PackageSignatureVerifier.sha256Hex("certificate-foreign".toByteArray()))
    }
}
