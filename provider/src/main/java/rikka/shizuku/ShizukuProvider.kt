/*
 * Copyright (c) 2021 RikkaW
 * Portions adapted from d4rken-org/porter-api (PorterApiProvider) under Apache-2.0 / MIT:
 * - Single cached BroadcastReceiver registered on applicationContext to prevent Activity context leaks
 * - Provider authority ownership check before cross-process ContentResolver.call
 */

package rikka.shizuku

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import moe.shizuku.api.BinderContainer

/**
 * Dual-wire [ContentProvider] that receives privileged server binders for both the native
 * Nightzuku protocol ([NightProtocol] -> [NightClient]) and the legacy Shizuku wire ([Shizuku]).
 */
open class ShizukuProvider : ContentProvider() {

    private var providerAuthority: String? = null

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        check(!info.multiprocess) { "android:multiprocess must be false" }
        check(info.exported) { "android:exported must be true" }
        providerAuthority = info.authority
        isProviderProcess = true
    }

    override fun onCreate(): Boolean = true

    private fun enforcePrivilegedOrSelfCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid != 0 && callingUid != 2000 && callingUid != Process.myUid()) {
            throw SecurityException("Unauthorized binder delivery from uid=$callingUid")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            NightProtocol.METHOD_SEND_BINDER -> {
                if (extras == null) return null
                enforcePrivilegedOrSelfCaller()
                handleSendNightBinder(extras)
                Bundle()
            }
            NightProtocol.METHOD_GET_BINDER -> {
                if (extras == null) return null
                enforcePrivilegedOrSelfCaller()
                val reply = Bundle()
                if (handleGetNightBinder(reply)) reply else null
            }
            NightProtocol.METHOD_REQUEST_BINDER,
            NightProtocol.LEGACY_METHOD_REQUEST_BINDER -> handleRequestBinder(method, arg, extras)
            METHOD_SEND_BINDER -> {
                if (extras == null) return null
                enforcePrivilegedOrSelfCaller()
                extras.classLoader = BinderContainer::class.java.classLoader
                handleSendLegacyBinder(extras)
                Bundle()
            }
            METHOD_GET_BINDER -> {
                if (extras == null) return null
                enforcePrivilegedOrSelfCaller()
                val reply = Bundle()
                if (handleGetLegacyBinder(reply)) reply else null
            }
            else -> null
        }
    }

    /**
     * Serves a pull request from [NightClient.connectFast].
     *
     * `requestNightBinder` maps to the native Nightzuku wire and `requestBinder` maps to the
     * legacy Shizuku wire. This mirrors the client fallback order: it first asks for the native
     * binder and only falls back to the legacy request method when the native reply is absent.
     */
    private fun handleRequestBinder(method: String, arg: String?, extras: Bundle?): Bundle? {
        val legacyWire = method == NightProtocol.LEGACY_METHOD_REQUEST_BINDER
        val permission = if (legacyWire) PERMISSION else NIGHT_PERMISSION
        if (!callerHasPermission(permission)) {
            Log.w(
                TAG,
                "Rejected binder request from uid=${Binder.getCallingUid()}: missing $permission"
            )
            return null
        }

        val cached = if (legacyWire) cachedLegacyBinder() else cachedNightBinder()
        if (cached != null) {
            return createBinderReply(legacyWire, cached)
        }

        val hookExtras = Bundle().apply {
            extras?.let { putAll(it) }
            if (arg != null && !containsKey(EXTRA_REQUEST_PACKAGE_NAME)) {
                putString(EXTRA_REQUEST_PACKAGE_NAME, arg)
            }
        }
        val requested = try {
            onBinderRequested(providerAuthority.orEmpty(), hookExtras)
        } catch (t: Throwable) {
            Log.w(TAG, "onBinderRequested failed for authority=${providerAuthority.orEmpty()}", t)
            null
        } ?: return null

        if (!requested.pingBinder()) return null
        return createBinderReply(legacyWire, requested)
    }

    /**
     * Extension point for an embedding manager that can hand out its privileged binder when
     * nothing has been pushed to this provider yet.
     *
     * Implementations may return a live [IBinder] directly, or trigger an asynchronous server
     * push (for example through [NightProtocol.METHOD_SEND_BINDER]) and return `null`. The
     * default returns `null`, so a provider that has already been seeded through `sendBinder`
     * still serves the cached path without any override.
     *
     * @param authority authority of this provider instance
     * @param extras extras supplied by the requesting client (`packageName` when available)
     */
    protected open fun onBinderRequested(authority: String, extras: Bundle?): IBinder? = null

    private fun callerHasPermission(permission: String): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.w(TAG, "Permission check for $permission failed", t)
            false
        }
    }

    private fun cachedNightBinder(): IBinder? =
        NightClient.getBinder()?.takeIf { it.pingBinder() }

    private fun cachedLegacyBinder(): IBinder? {
        // Check the legacy slot directly so Shizuku.getBinder()'s NightClient fallback cannot
        // leak a native binder through the legacy wire.
        if (!Shizuku.pingBinder()) return null
        return Shizuku.getBinder()?.takeIf { it.pingBinder() }
    }

    private fun createBinderReply(legacyWire: Boolean, binder: IBinder): Bundle {
        return Bundle().apply {
            if (legacyWire) {
                putParcelable(EXTRA_BINDER, BinderContainer(binder))
            } else {
                putBinder(NightProtocol.EXTRA_BINDER, binder)
            }
        }
    }

    private fun handleSendNightBinder(extras: Bundle) {
        if (NightClient.pingBinder()) {
            Log.d(TAG, "sendNightBinder called when NightClient already holds a live binder")
            return
        }
        val binder = LegacyParcelCodec.extractBinderFromBundle(extras) ?: extras.getBinder(NightProtocol.EXTRA_BINDER)
        val ctx = context
        if (binder != null && ctx != null) {
            NightClient.onBinderReceived(binder, ctx.packageName)
            if (enableMultiProcess) {
                val intent = Intent(ACTION_NIGHT_BINDER_RECEIVED)
                    .setPackage(ctx.packageName)
                ctx.sendBroadcast(intent)
            }
        }
    }

    private fun handleGetNightBinder(reply: Bundle): Boolean {
        val binder = NightClient.getBinder()?.takeIf { it.pingBinder() }
            ?: Shizuku.getBinder()?.takeIf { it.pingBinder() }
            ?: return false
        reply.putBinder(NightProtocol.EXTRA_BINDER, binder)
        return true
    }

    private fun handleSendLegacyBinder(extras: Bundle) {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "sendBinder is called when already a living binder")
            return
        }
        val binder = LegacyParcelCodec.extractBinderFromBundle(extras) ?: run {
            @Suppress("DEPRECATION")
            extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder
        }
        val ctx = context
        if (binder != null && ctx != null) {
            Shizuku.onBinderReceived(binder, ctx.packageName)
            if (enableMultiProcess) {
                val intent = Intent(ACTION_BINDER_RECEIVED)
                    .setPackage(ctx.packageName)
                ctx.sendBroadcast(intent)
            }
        }
    }

    private fun handleGetLegacyBinder(reply: Bundle): Boolean {
        val binder: IBinder = Shizuku.getBinder() ?: return false
        if (!binder.pingBinder()) return false
        reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "ShizukuProvider"

        const val METHOD_SEND_BINDER: String = "sendBinder"
        const val METHOD_GET_BINDER: String = "getBinder"
        const val ACTION_BINDER_RECEIVED: String = "moe.shizuku.api.action.BINDER_RECEIVED"
        const val ACTION_NIGHT_BINDER_RECEIVED: String = "kerneldroid.nightzuku.api.action.BINDER_RECEIVED"
        private const val EXTRA_BINDER: String = LegacyParcelCodec.LEGACY_BINDER_EXTRA_KEY
        private const val EXTRA_REQUEST_PACKAGE_NAME: String = "packageName"

        const val PERMISSION: String = "moe.shizuku.manager.permission.API_V23"
        const val NIGHT_PERMISSION: String = "kerneldroid.nightzuku.permission.API"
        const val MANAGER_APPLICATION_ID: String = "kerneldroid.nightzuku"

        private val receiverRegistrationLock = Any()

        @Volatile
        private var enableMultiProcess: Boolean = false

        @Volatile
        private var isProviderProcess: Boolean = false

        @Volatile
        private var nonProviderReceiver: BroadcastReceiver? = null

        @JvmStatic
        fun setIsProviderProcess(isProviderProcess: Boolean) {
            Companion.isProviderProcess = isProviderProcess
        }

        @JvmStatic
        fun enableMultiProcessSupport(isProviderProcess: Boolean) {
            Companion.isProviderProcess = isProviderProcess
            enableMultiProcess = true
        }

        /**
         * Kept as a no-op for backward source/binary compatibility; Sui is not used in Nightzuku.
         */
        @JvmStatic
        fun disableAutomaticSuiInitialization() {
        }

        /**
         * Proactively requests the privileged binder for the current process from the Nightzuku Manager.
         */
        @JvmStatic
        fun connectFast(context: Context): Boolean = NightClient.connectFast(context)

        @JvmStatic
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun requestBinderForNonProviderProcess(context: Context) {
            if (isProviderProcess) return

            val appContext = context.applicationContext ?: context

            synchronized(receiverRegistrationLock) {
                if (nonProviderReceiver == null) {
                    val created = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            when (intent.action) {
                                ACTION_NIGHT_BINDER_RECEIVED,
                                ACTION_BINDER_RECEIVED -> {
                                    fetchBinderFromOwnProvider(appContext)
                                }
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(ACTION_NIGHT_BINDER_RECEIVED)
                        addAction(ACTION_BINDER_RECEIVED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        appContext.registerReceiver(created, filter)
                    }
                    nonProviderReceiver = created
                }
            }

            fetchBinderFromOwnProvider(appContext)
        }

        private fun fetchBinderFromOwnProvider(appContext: Context) {
            val pkg = appContext.packageName
            val nightAuthority = "$pkg${NightProtocol.PROVIDER_SUFFIX}"
            if (ownsProviderAuthority(appContext, nightAuthority)) {
                val nightReply = runCatching {
                    appContext.contentResolver.call(
                        Uri.parse("content://$nightAuthority"),
                        NightProtocol.METHOD_GET_BINDER,
                        null,
                        Bundle()
                    )
                }.getOrNull()
                val nightBinder = LegacyParcelCodec.extractBinderFromBundle(nightReply)
                    ?: nightReply?.getBinder(NightProtocol.EXTRA_BINDER)
                if (nightBinder != null && nightBinder.pingBinder()) {
                    NightClient.onBinderReceived(nightBinder, pkg)
                }
            }

            val legacyAuthority = "$pkg${NightProtocol.LEGACY_PROVIDER_SUFFIX}"
            if (ownsProviderAuthority(appContext, legacyAuthority)) {
                val legacyReply = runCatching {
                    appContext.contentResolver.call(
                        Uri.parse("content://$legacyAuthority"),
                        METHOD_GET_BINDER,
                        null,
                        Bundle()
                    )
                }.getOrNull()
                if (legacyReply != null) {
                    val binder = LegacyParcelCodec.extractBinderFromBundle(legacyReply)
                    if (binder != null && binder.pingBinder()) {
                        Shizuku.onBinderReceived(binder, pkg)
                    }
                }
            }
        }

        private fun ownsProviderAuthority(context: Context, authority: String): Boolean {
            return try {
                context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName
            } catch (_: Throwable) {
                false
            }
        }
    }
}
