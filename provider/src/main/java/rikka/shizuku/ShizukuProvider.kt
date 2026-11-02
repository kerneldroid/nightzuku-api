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
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        check(!info.multiprocess) { "android:multiprocess must be false" }
        check(info.exported) { "android:exported must be true" }
        isProviderProcess = true
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        return when (method) {
            NightProtocol.METHOD_SEND_BINDER -> {
                handleSendNightBinder(extras)
                Bundle()
            }
            NightProtocol.METHOD_GET_BINDER -> {
                val reply = Bundle()
                if (handleGetNightBinder(reply)) reply else null
            }
            METHOD_SEND_BINDER -> {
                extras.classLoader = BinderContainer::class.java.classLoader
                handleSendLegacyBinder(extras)
                Bundle()
            }
            METHOD_GET_BINDER -> {
                val reply = Bundle()
                if (handleGetLegacyBinder(reply)) reply else null
            }
            else -> Bundle()
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
                val broadcastExtras = Bundle().apply {
                    putBinder(NightProtocol.EXTRA_BINDER, binder)
                }
                val intent = Intent(ACTION_NIGHT_BINDER_RECEIVED)
                    .putExtras(broadcastExtras)
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
                    .putExtra(EXTRA_BINDER, BinderContainer(binder))
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
        private const val EXTRA_BINDER: String = "moe.shizuku.privileged.api.intent.extra.BINDER"

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
                                ACTION_NIGHT_BINDER_RECEIVED -> {
                                    val binder = intent.extras?.getBinder(NightProtocol.EXTRA_BINDER)
                                    if (binder != null && binder.pingBinder()) {
                                        NightClient.onBinderReceived(binder, ctx.packageName)
                                    } else {
                                        fetchBinderFromOwnProvider(appContext)
                                    }
                                }
                                ACTION_BINDER_RECEIVED -> {
                                    @Suppress("DEPRECATION")
                                    val container = intent.getParcelableExtra<BinderContainer>(EXTRA_BINDER)
                                    val binder = container?.binder
                                    if (binder != null && binder.pingBinder()) {
                                        Shizuku.onBinderReceived(binder, ctx.packageName)
                                    } else {
                                        fetchBinderFromOwnProvider(appContext)
                                    }
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
