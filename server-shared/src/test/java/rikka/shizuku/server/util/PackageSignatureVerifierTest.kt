package rikka.shizuku.server.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Suppress("DEPRECATION")
class PackageSignatureVerifierTest {

    @Test
    fun `evaluatePackageInfo classifies null, uninstalled residue and missing signers`() {
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(null, 0) is
                PackageSignatureVerifier.ProbeResult.Absent
        )

        val residue = packageInfo(arrayOf(CERT_ORIGINAL)).apply {
            applicationInfo!!.flags =
                applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED.inv()
        }
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(residue, 0) is
                PackageSignatureVerifier.ProbeResult.Absent
        )

        val missingAi = PackageInfo().apply { packageName = TEST_PACKAGE }
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(missingAi, 0) is
                PackageSignatureVerifier.ProbeResult.LookupError
        )

        val noSigners = packageInfo(emptyArray())
        assertTrue(
            PackageSignatureVerifier.evaluatePackageInfo(noSigners, 0) is
                PackageSignatureVerifier.ProbeResult.LookupError
        )
        assertNull(PackageSignatureVerifier.extractSnapshot(noSigners, 0))
    }

    @Test
    fun `extractSnapshot reports package app id and deterministic signer digest`() {
        val probe = PackageSignatureVerifier.evaluatePackageInfo(
            packageInfo(arrayOf(CERT_ORIGINAL)),
            3
        )
        assertTrue(probe is PackageSignatureVerifier.ProbeResult.Present)
        val snapshot = (probe as PackageSignatureVerifier.ProbeResult.Present).snapshot
        assertEquals(TEST_PACKAGE, snapshot.packageName)
        assertEquals(TEST_USER_ID, snapshot.userId)
        assertEquals(TEST_APP_ID, snapshot.appId)
        assertEquals("/data/app/$TEST_PACKAGE/base.apk", snapshot.sourceDir)
        assertEquals(1, snapshot.certDigests.size)
        assertFalse(snapshot.hasMultipleSigners)
        assertNull(snapshot.rotationLineageDigests)

        val digest = snapshot.certDigests.first()
        assertEquals(64, digest.length)
        assertEquals(digest.lowercase(), digest)
        assertEquals(digest, PackageSignatureVerifier.sha256Hex(CERT_ORIGINAL))
    }

    @Test
    fun `Baseline matches same signer and P+ rotation lineage but rejects foreign signer or app id`() {
        val baseline = PackageSignatureVerifier.Baseline(TEST_PACKAGE, TEST_APP_ID, setOf(DIGEST_ORIGINAL))

        assertTrue(baseline.matches(snapshot(setOf(DIGEST_ORIGINAL))))
        assertTrue(
            baseline.matches(
                snapshot(setOf(DIGEST_ROTATED), lineage = setOf(DIGEST_ORIGINAL, DIGEST_ROTATED))
            )
        )

        assertFalse(baseline.matches(snapshot(setOf(DIGEST_FOREIGN))))
        assertFalse(
            baseline.matches(
                snapshot(setOf(DIGEST_FOREIGN), lineage = setOf(DIGEST_FOREIGN))
            )
        )
        assertFalse(baseline.matches(snapshot(setOf(DIGEST_ORIGINAL), appId = TEST_APP_ID + 1)))
        assertFalse(baseline.matches(null))

        // A multi-signer package must never be accepted through rotation lineage.
        assertFalse(
            baseline.matches(
                snapshot(setOf(DIGEST_ROTATED), multiple = true, lineage = setOf(DIGEST_ORIGINAL))
            )
        )
    }

    @Test
    fun `Baseline for multi-signer package requires the complete co-signer set`() {
        val baseline = PackageSignatureVerifier.Baseline(
            TEST_PACKAGE,
            TEST_APP_ID,
            setOf(DIGEST_ORIGINAL, DIGEST_CO_SIGNER)
        )
        assertTrue(baseline.matches(snapshot(setOf(DIGEST_ORIGINAL, DIGEST_CO_SIGNER), multiple = true)))
        assertTrue(baseline.matches(snapshot(setOf(DIGEST_CO_SIGNER, DIGEST_ORIGINAL), multiple = true)))
        assertFalse(baseline.matches(snapshot(setOf(DIGEST_ORIGINAL), multiple = true)))
    }

    @Test
    fun `signatureLookupFlags includes uninstalled and certificate flags`() {
        val flags = PackageSignatureVerifier.signatureLookupFlags()
        assertTrue(
            (flags and android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()) != 0L
        )
        assertTrue(
            (flags and android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()) != 0L
        )
    }

    private fun packageInfo(signers: Array<Signature>): PackageInfo = PackageInfo().apply {
        packageName = TEST_PACKAGE
        applicationInfo = ApplicationInfo().apply {
            uid = TEST_UID
            flags = ApplicationInfo.FLAG_INSTALLED
            sourceDir = "/data/app/$TEST_PACKAGE/base.apk"
        }
        signatures = signers
    }

    private fun snapshot(
        digests: Set<String>,
        appId: Int = TEST_APP_ID,
        multiple: Boolean = false,
        lineage: Set<String>? = null
    ): PackageSignatureVerifier.Snapshot = PackageSignatureVerifier.Snapshot(
        TEST_PACKAGE,
        TEST_USER_ID,
        appId,
        "/data/app/$TEST_PACKAGE/base.apk",
        digests,
        multiple,
        lineage
    )

    companion object {
        private const val TEST_PACKAGE = "com.example.client"
        private const val TEST_USER_ID = 3
        private const val TEST_APP_ID = 10_234
        private const val TEST_UID = TEST_USER_ID * 100_000 + TEST_APP_ID

        private val CERT_ORIGINAL = Signature("original-certificate".toByteArray())
        private val CERT_ROTATED = Signature("rotated-certificate".toByteArray())
        private val CERT_FOREIGN = Signature("foreign-certificate".toByteArray())
        private val CERT_CO_SIGNER = Signature("co-signer-certificate".toByteArray())

        private val DIGEST_ORIGINAL =
            requireNotNull(PackageSignatureVerifier.sha256Hex(CERT_ORIGINAL))
        private val DIGEST_ROTATED =
            requireNotNull(PackageSignatureVerifier.sha256Hex(CERT_ROTATED))
        private val DIGEST_FOREIGN =
            requireNotNull(PackageSignatureVerifier.sha256Hex(CERT_FOREIGN))
        private val DIGEST_CO_SIGNER =
            requireNotNull(PackageSignatureVerifier.sha256Hex(CERT_CO_SIGNER))
    }
}
