package kerneldroid.nightzuku.api

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LegacyParcelCodecTest {

    @Test
    fun `extractBinderFromBundle extracts direct NightProtocol EXTRA_BINDER`() {
        val expected = Binder()
        val bundle = Bundle().apply {
            putBinder(NightProtocol.EXTRA_BINDER, expected)
        }
        assertSame(expected, LegacyParcelCodec.extractBinderFromBundle(bundle))
    }

    @Test
    fun `extractBinderFromBundle extracts legacy BinderContainer from parcelled Bundle without ClassLoader crash`() {
        val expected = Binder()
        // Construct a raw parcelled Bundle containing a fake moe.shizuku.api.BinderContainer entry
        // that is NOT loadable by the default Bundle ClassLoader.
        val rawBundleParcel = Parcel.obtain()
        val unparcelledBundle: Bundle
        try {
            val contentParcel = Parcel.obtain()
            try {
                contentParcel.writeInt(1) // 1 key-value pair
                contentParcel.writeString(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY)
                contentParcel.writeInt(4) // VAL_PARCELABLE
                contentParcel.writeString("moe.shizuku.api.BinderContainer")
                contentParcel.writeStrongBinder(expected)

                val payloadSize = contentParcel.dataPosition()
                rawBundleParcel.writeInt(payloadSize)
                rawBundleParcel.writeInt(0x4C444E42) // BUNDLE_MAGIC ('B' 'N' 'D' 'L')
                rawBundleParcel.appendFrom(contentParcel, 0, payloadSize)
                rawBundleParcel.setDataPosition(0)
                unparcelledBundle = Bundle.CREATOR.createFromParcel(rawBundleParcel)
            } finally {
                contentParcel.recycle()
            }
        } finally {
            rawBundleParcel.recycle()
        }

        val extracted = LegacyParcelCodec.extractBinderFromBundle(unparcelledBundle)
        assertSame(expected, extracted)
    }

    @Test
    fun `sendLegacyBindApplication writes valid IShizukuApplication bindApplication transaction`() {
        var receivedCode = -1
        var receivedDescriptor: String? = null
        var receivedBundle: Bundle? = null

        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                receivedCode = code
                data.enforceInterface(LegacyParcelCodec.DESCRIPTOR_ISHIZUKU_APPLICATION)
                receivedDescriptor = LegacyParcelCodec.DESCRIPTOR_ISHIZUKU_APPLICATION
                if (data.readInt() != 0) {
                    receivedBundle = Bundle.CREATOR.createFromParcel(data)
                }
                return true
            }
        }

        val sent = LegacyParcelCodec.sendLegacyBindApplication(
            applicationBinder = target,
            serverUid = 2000,
            serverVersion = 13,
            serverPatchVersion = 6,
            seContext = "u:r:shell:s0",
            permissionGranted = true,
            shouldShowRationale = false
        )

        assertTrue(sent)
        assertEquals(LegacyParcelCodec.TRANSACTION_BIND_APPLICATION, receivedCode)
        assertEquals(LegacyParcelCodec.DESCRIPTOR_ISHIZUKU_APPLICATION, receivedDescriptor)
        val bundle = receivedBundle!!
        assertEquals(2000, bundle.getInt("shizuku:attach-reply-uid"))
        assertEquals(13, bundle.getInt("shizuku:attach-reply-version"))
        assertEquals(6, bundle.getInt("shizuku:attach-reply-patch-version"))
        assertEquals("u:r:shell:s0", bundle.getString("shizuku:attach-reply-secontext"))
        assertTrue(bundle.getBoolean("shizuku:attach-reply-permission-granted"))
    }

    @Test
    fun `NightProtocol transaction codes and capability masks are mutually distinct`() {
        val transactions = listOf(
            NightProtocol.TRANSACTION_ATTACH,
            NightProtocol.TRANSACTION_TRANSACT_REMOTE,
            NightProtocol.TRANSACTION_EXEC_FAST,
            NightProtocol.TRANSACTION_CHECK_PERMISSION,
            NightProtocol.TRANSACTION_REQUEST_PERMISSION,
            NightProtocol.TRANSACTION_GET_SYSTEM_PROPERTY,
            NightProtocol.TRANSACTION_SET_SYSTEM_PROPERTY,
            NightProtocol.TRANSACTION_CLAIM_USER_SERVICE,
            NightProtocol.TRANSACTION_GET_UID_CAPABILITIES,
            NightProtocol.TRANSACTION_SET_UID_CAPABILITIES,
            NightProtocol.TRANSACTION_GET_ACCESS_PAUSED,
            NightProtocol.TRANSACTION_SET_ACCESS_PAUSED,
            NightProtocol.TRANSACTION_GET_SERVER_DIAGNOSTICS,
            NightProtocol.TRANSACTION_ADD_USER_SERVICE,
            NightProtocol.TRANSACTION_REMOVE_USER_SERVICE,
            NightProtocol.TRANSACTION_NEW_PROCESS,
            NightProtocol.TRANSACTION_EXEC_ASYNC,
            NightProtocol.TRANSACTION_CANCEL_EXEC
        )
        assertEquals(transactions.size, transactions.distinct().size)

        val callbacks = listOf(
            NightProtocol.CALLBACK_ON_PERMISSION_CHANGED,
            NightProtocol.CALLBACK_ON_REQUEST_PERMISSION_RESULT,
            NightProtocol.CALLBACK_ON_EXEC_RESULT
        )
        assertEquals(callbacks.size, callbacks.distinct().size)

        val statuses = listOf(
            NightProtocol.ATTACH_STATUS_OK,
            NightProtocol.ATTACH_STATUS_CLIENT_TOO_OLD,
            NightProtocol.ATTACH_STATUS_SERVER_TOO_OLD
        )
        assertEquals(statuses.size, statuses.distinct().size)

        val allCaps = NightProtocol.CAP_SHELL_EXEC or
            NightProtocol.CAP_BINDER_PROXY or
            NightProtocol.CAP_USER_SERVICE or
            NightProtocol.CAP_SYSTEM_PROPS
        assertEquals(NightProtocol.MASK_CAPABILITIES, allCaps)
        assertEquals(NightProtocol.DEFAULT_CAPABILITIES, allCaps)
        assertNotEquals(NightProtocol.METHOD_SEND_BINDER, NightProtocol.METHOD_REQUEST_BINDER)
    }
}
