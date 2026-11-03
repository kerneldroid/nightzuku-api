package rikka.shizuku.server.runtime

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProviderBinderClientTest {

    @Test
    fun `authority appends the wire suffix`() {
        assertEquals(
            "com.example.manager" + NightProtocol.PROVIDER_SUFFIX,
            ProviderBinderClient.authorityFor(
                "com.example.manager",
                ProviderBinderClient.Wire.NIGHTZUKU
            )
        )
        assertEquals(
            "com.example.manager" + NightProtocol.LEGACY_PROVIDER_SUFFIX,
            ProviderBinderClient.authorityFor(
                "com.example.manager",
                ProviderBinderClient.Wire.SHIZUKU
            )
        )
    }

    @Test
    fun `night extras carry the binder under the night key`() {
        val binder = Binder()
        val extras = ProviderBinderClient.serverBinderExtras(
            binder,
            ProviderBinderClient.Wire.NIGHTZUKU
        )

        assertSame(binder, extras.getBinder(NightProtocol.EXTRA_BINDER))
        assertSame(binder, LegacyParcelCodec.extractBinderFromBundle(extras))
    }

    @Test
    fun `legacy extras use the raw BinderContainer parcelable layout`() {
        val binder = Binder()
        val extras = ProviderBinderClient.serverBinderExtras(
            binder,
            ProviderBinderClient.Wire.SHIZUKU
        )

        assertSame(binder, LegacyParcelCodec.extractBinderFromBundle(extras))
    }

    @Test
    fun `user service extras carry both token keys`() {
        val binder = Binder()
        val extras = ProviderBinderClient.userServiceExtras(binder, "launch-token")

        assertSame(binder, extras.getBinder(NightProtocol.EXTRA_BINDER))
        assertEquals(
            "launch-token",
            extras.getString(NightProtocol.EXTRA_USER_SERVICE_TOKEN)
        )
        assertEquals("launch-token", extras.getString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN))
    }

    @Test
    fun `missing reply yields no binder`() {
        assertNull(ProviderBinderClient.parseServerBinderReply(null))
        assertNull(ProviderBinderClient.parseServerBinderReply(Bundle()))
    }

    @Test
    fun `reply binder is extracted and linked`() {
        val binder = Binder()
        val reply = Bundle().apply { putBinder(NightProtocol.EXTRA_BINDER, binder) }
        var died = false

        val parsed = ProviderBinderClient.parseServerBinderReply(reply) { died = true }

        assertSame(binder, parsed)
        assertFalse(died)
    }

    @Test
    fun `legacy reply binder is extracted`() {
        val binder = Binder()
        val parsed = ProviderBinderClient.parseServerBinderReply(
            LegacyParcelCodec.createLegacyBinderBundle(binder)
        )

        assertSame(binder, parsed)
    }

    @Test
    fun `dead reply binder is rejected`() {
        val deadBinder = object : Binder() {
            override fun pingBinder(): Boolean = false
        }
        val reply = Bundle().apply { putBinder(NightProtocol.EXTRA_BINDER, deadBinder) }

        assertNull(ProviderBinderClient.parseServerBinderReply(reply))
    }

    @Test
    fun `death link failure rejects the binder`() {
        val refusingBinder = object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                throw RemoteException("binder is already dead")
            }
        }
        val reply = Bundle().apply { putBinder(NightProtocol.EXTRA_BINDER, refusingBinder) }

        assertNull(ProviderBinderClient.parseServerBinderReply(reply) { })
    }

    @Test
    fun `publishing without a provider reports failure instead of throwing`() {
        val legacy = Binder()
        val night = Binder()
        val config = ServerConfig.fromProperties(
            properties = mapOf(ServerConfig.PROP_MANAGER_PACKAGE to "com.example.manager")
        )

        assertFalse(
            ProviderBinderClient.publishToManager(
                legacyBinder = legacy,
                nightBinder = night,
                config = config,
                attempts = 1
            )
        )
    }

    @Test
    fun `successful user service reply is delivered`() {
        val serviceBinder = Binder()
        val reply = ProviderBinderClient.UserServiceReply(serviceBinder, false)

        assertTrue(reply.delivered)
        assertFalse(reply.linkedToServerDeath)
        assertNotNull(serviceBinder)
    }
}
