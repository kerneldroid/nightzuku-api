package rikka.shizuku

import android.app.Application
import android.content.ContentProvider
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.FileDescriptor
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import kerneldroid.nightzuku.api.NightProvider
import moe.shizuku.api.BinderContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuProviderTest {

    private lateinit var context: Application
    private lateinit var provider: ShizukuProvider

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).denyPermissions(
            ShizukuProvider.NIGHT_PERMISSION,
            ShizukuProvider.PERMISSION
        )
        NightClient.onBinderReceived(null, null)
        Shizuku.onBinderReceived(null, null)
        provider = createProvider(ShizukuProvider(), NIGHT_AUTHORITY)
    }

    @After
    fun tearDown() {
        NightClient.onBinderReceived(null, null)
        Shizuku.onBinderReceived(null, null)
    }

    @Test
    fun `unknown method is rejected`() {
        assertNull(provider.call("notAProtocolMethod", null, Bundle()))
    }

    @Test
    fun `sendBinder then getBinder round-trips the legacy binder`() {
        val binder = LegacyServiceBinder()
        pushLegacy(provider, binder)

        val reply = provider.call(ShizukuProvider.METHOD_GET_BINDER, null, Bundle())

        assertSame(binder, legacyBinderFrom(reply))
    }

    @Test
    fun `sendNightBinder then getNightBinder round-trips the night binder`() {
        val binder = NightServiceBinder()
        pushNight(provider, binder)

        val reply = provider.call(NightProtocol.METHOD_GET_BINDER, null, Bundle())

        assertSame(binder, reply?.getBinder(NightProtocol.EXTRA_BINDER))
    }

    @Test
    fun `getNightBinder keeps legacy fallback for backward compatibility`() {
        val binder = LegacyServiceBinder()
        pushLegacy(provider, binder)

        val reply = provider.call(NightProtocol.METHOD_GET_BINDER, null, Bundle())

        assertSame(binder, reply?.getBinder(NightProtocol.EXTRA_BINDER))
    }

    @Test
    fun `requestNightBinder returns the cached live night binder`() {
        grantNightPermission()
        val binder = NightServiceBinder()
        pushNight(provider, binder)

        val reply = provider.call(
            NightProtocol.METHOD_REQUEST_BINDER,
            context.packageName,
            Bundle().apply { putString("packageName", context.packageName) }
        )

        assertSame(binder, reply?.getBinder(NightProtocol.EXTRA_BINDER))
        assertFalse(reply!!.containsKey(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY))
        assertSame(binder, reparse(reply).getBinder(NightProtocol.EXTRA_BINDER))
    }

    @Test
    fun `requestBinder returns the cached live legacy binder`() {
        grantLegacyPermission()
        val binder = LegacyServiceBinder()
        pushLegacy(provider, binder)

        val reply = provider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, Bundle())

        assertNotNull(reply)
        assertTrue(reply!!.containsKey(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY))
        assertSame(binder, legacyBinderFrom(reply))
    }

    @Test
    fun `request methods return null when no binder is cached and hook is not overridden`() {
        grantNightPermission()
        grantLegacyPermission()

        assertNull(provider.call(NightProtocol.METHOD_REQUEST_BINDER, null, Bundle()))
        assertNull(provider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, Bundle()))
    }

    @Test
    fun `requestNightBinder invokes hook with authority and returns its binder`() {
        grantNightPermission()
        val binder = Binder()
        var seenAuthority: String? = null
        var seenPackageName: String? = null

        val hooked = object : NightProvider() {
            override fun onBinderRequested(authority: String, extras: Bundle?): IBinder {
                seenAuthority = authority
                seenPackageName = extras?.getString("packageName")
                return binder
            }
        }
        val hookedProvider = createProvider(hooked, NIGHT_AUTHORITY)

        val reply = hookedProvider.call(
            NightProtocol.METHOD_REQUEST_BINDER,
            "com.example.client",
            Bundle().apply { putString("packageName", "com.example.client") }
        )

        assertEquals(NIGHT_AUTHORITY, seenAuthority)
        assertEquals("com.example.client", seenPackageName)
        assertSame(binder, reply?.getBinder(NightProtocol.EXTRA_BINDER))
    }

    @Test
    fun `requestBinder invokes hook and encodes the legacy BinderContainer`() {
        grantLegacyPermission()
        val binder = Binder()
        var seenAuthority: String? = null

        val hooked = object : ShizukuProvider() {
            override fun onBinderRequested(authority: String, extras: Bundle?): IBinder {
                seenAuthority = authority
                return binder
            }
        }
        val hookedProvider = createProvider(hooked, LEGACY_AUTHORITY)

        val reply = hookedProvider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, null)

        assertEquals(LEGACY_AUTHORITY, seenAuthority)
        assertNotNull(reply)
        assertTrue(reply!!.containsKey(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY))
        assertSame(binder, legacyBinderFrom(reply))
        assertSame(binder, legacyBinderFrom(reparse(reply)))
    }

    @Test
    fun `requestNightBinder denies callers without the night permission`() {
        val binder = NightServiceBinder()
        pushNight(provider, binder)
        var hookCalls = 0

        val hooked = object : ShizukuProvider() {
            override fun onBinderRequested(authority: String, extras: Bundle?): IBinder {
                hookCalls++
                return binder
            }
        }
        val hookedProvider = createProvider(hooked, NIGHT_AUTHORITY)

        val reply = hookedProvider.call(NightProtocol.METHOD_REQUEST_BINDER, null, Bundle())

        assertNull(reply)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `requestBinder denies callers without the legacy permission`() {
        val binder = LegacyServiceBinder()
        pushLegacy(provider, binder)
        var hookCalls = 0

        val hooked = object : ShizukuProvider() {
            override fun onBinderRequested(authority: String, extras: Bundle?): IBinder {
                hookCalls++
                return binder
            }
        }
        val hookedProvider = createProvider(hooked, LEGACY_AUTHORITY)

        val reply = hookedProvider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, Bundle())

        assertNull(reply)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `requestBinder does not return a dead cached binder`() {
        grantLegacyPermission()
        pushLegacy(provider, DeadBinder())

        // Shizuku cached the pushed binder, but it is no longer alive.
        assertFalse(Shizuku.pingBinder())
        assertNull(provider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, Bundle()))
    }

    @Test
    fun `legacy request does not expose the cached night binder`() {
        grantNightPermission()
        grantLegacyPermission()
        pushNight(provider, NightServiceBinder())

        assertNull(provider.call(NightProtocol.LEGACY_METHOD_REQUEST_BINDER, null, Bundle()))
    }

    @Test
    fun `night request does not expose the cached legacy binder`() {
        grantNightPermission()
        grantLegacyPermission()
        pushLegacy(provider, LegacyServiceBinder())

        assertNull(provider.call(NightProtocol.METHOD_REQUEST_BINDER, null, Bundle()))
    }

    private fun grantNightPermission() {
        Shadows.shadowOf(context).grantPermissions(ShizukuProvider.NIGHT_PERMISSION)
    }

    private fun grantLegacyPermission() {
        Shadows.shadowOf(context).grantPermissions(ShizukuProvider.PERMISSION)
    }

    private fun pushNight(target: ContentProvider, binder: IBinder) {
        val extras = Bundle().apply { putBinder(NightProtocol.EXTRA_BINDER, binder) }
        assertNotNull(target.call(NightProtocol.METHOD_SEND_BINDER, null, extras))
    }

    private fun pushLegacy(target: ContentProvider, binder: IBinder) {
        val extras = Bundle().apply {
            putParcelable(LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY, BinderContainer(binder))
        }
        assertNotNull(target.call(ShizukuProvider.METHOD_SEND_BINDER, null, extras))
    }

    private fun legacyBinderFrom(reply: Bundle?): IBinder? =
        reply?.getParcelable(
            LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY,
            BinderContainer::class.java
        )?.binder

    private fun reparse(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun <T : ContentProvider> createProvider(instance: T, authority: String): T {
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = context.packageName
            exported = true
            multiprocess = false
        }
        return ContentProviderController.of(instance).create(info).get()
    }

    /**
     * Minimal privileged service binder that answers the native attach handshake so
     * [NightClient] caches it as a live session.
     */
    private class NightServiceBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == NightProtocol.TRANSACTION_ATTACH) {
                data.enforceInterface(NightProtocol.DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(NightProtocol.ATTACH_STATUS_OK)
                reply?.writeInt(NightProtocol.PROTOCOL_VERSION)
                reply?.writeInt(NightProtocol.MIN_PROTOCOL_VERSION)
                reply?.writeInt(2000)
                reply?.writeInt(NightProtocol.STATE_GRANTED)
                reply?.writeInt(NightProtocol.DEFAULT_CAPABILITIES)
                reply?.writeString("u:r:shell:s0")
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class LegacyServiceBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }

    private class DeadBinder : IBinder {
        override fun getInterfaceDescriptor(): String? = null

        override fun pingBinder(): Boolean = false

        override fun isBinderAlive(): Boolean = false

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = false

        override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit

        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit
    }

    private companion object {
        val NIGHT_AUTHORITY = "com.example.client" + NightProtocol.PROVIDER_SUFFIX
        val LEGACY_AUTHORITY = "com.example.client" + NightProtocol.LEGACY_PROVIDER_SUFFIX
    }
}
