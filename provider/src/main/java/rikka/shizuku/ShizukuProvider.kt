package rikka.shizuku

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

        @Volatile
        private var enableMultiProcess: Boolean = false

        @Volatile
        private var isProviderProcess: Boolean = false

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

        @JvmStatic
        fun requestBinderForNonProviderProcess(context: Context) {
            if (isProviderProcess) return

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_NIGHT_BINDER_RECEIVED -> {
                            val binder = intent.extras?.getBinder(NightProtocol.EXTRA_BINDER)
                            if (binder != null) {
                                NightClient.onBinderReceived(binder, ctx.packageName)
                            }
                        }
                        ACTION_BINDER_RECEIVED -> {
                            @Suppress("DEPRECATION")
                            val container = intent.getParcelableExtra<BinderContainer>(EXTRA_BINDER)
                            val binder = container?.binder
                            if (binder != null) {
                                Shizuku.onBinderReceived(binder, ctx.packageName)
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
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val nightReply = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://${context.packageName}${NightProtocol.PROVIDER_SUFFIX}"),
                    NightProtocol.METHOD_GET_BINDER,
                    null,
                    Bundle()
                )
            }.getOrNull()
            val nightBinder = nightReply?.getBinder(NightProtocol.EXTRA_BINDER)
            if (nightBinder != null) {
                NightClient.onBinderReceived(nightBinder, context.packageName)
            }

            val legacyReply = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://${context.packageName}${NightProtocol.LEGACY_PROVIDER_SUFFIX}"),
                    METHOD_GET_BINDER,
                    null,
                    Bundle()
                )
            }.getOrNull()
            if (legacyReply != null) {
                val binder = LegacyParcelCodec.extractBinderFromBundle(legacyReply)
                if (binder != null) {
                    Shizuku.onBinderReceived(binder, context.packageName)
                }
            }
        }
    }
}
