package rikka.shizuku.server.runtime

import android.content.IContentProvider
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProtocol
import rikka.hidden.compat.ActivityManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.IContentProviderUtils
import rikka.shizuku.server.util.Logger

/**
 * Minimal binder delivery over the manager application's `ContentProvider`.
 *
 * The privileged server cannot call into manager code directly; instead it asks ActivityManager
 * for the manager provider (running in the manager's process) and hands the binder over through
 * [IContentProviderUtils.callCompat]. Two wires are supported: the first-class Nightzuku provider
 * and the legacy Shizuku provider.
 *
 * This class intentionally omits the historical power-save whitelisting, package routing and
 * NightDog watchdog integration. It keeps only the parts needed by the standalone runtime:
 * provider lookup, dead-provider detection, binder extras, and reply/death parsing.
 */
object ProviderBinderClient {

    /** Provider wire selected for a delivery. */
    enum class Wire(val providerSuffix: String, val method: String) {
        NIGHTZUKU(NightProtocol.PROVIDER_SUFFIX, NightProtocol.METHOD_SEND_BINDER),
        SHIZUKU(NightProtocol.LEGACY_PROVIDER_SUFFIX, LEGACY_SEND_BINDER_METHOD)
    }

    private const val LEGACY_SEND_BINDER_METHOD = "sendBinder"

    private val logger = Logger("ProviderBinderClient")

    /** Result of a UserService binder delivery. */
    data class UserServiceReply(
        val serverBinder: IBinder?,
        val linkedToServerDeath: Boolean
    ) {
        val delivered: Boolean
            get() = serverBinder != null
    }

    /** Authority serving [wire] for [packageName]. */
    @JvmStatic
    fun authorityFor(packageName: String, wire: Wire): String =
        packageName + wire.providerSuffix

    /** Builds the extras for a server-binder delivery on [wire]. */
    @JvmStatic
    fun serverBinderExtras(binder: IBinder, wire: Wire): Bundle = when (wire) {
        Wire.NIGHTZUKU -> Bundle().apply {
            putBinder(NightProtocol.EXTRA_BINDER, binder)
        }
        Wire.SHIZUKU -> LegacyParcelCodec.createLegacyBinderBundle(binder)
    }

    /** Builds the extras for a UserService binder delivery. */
    @JvmStatic
    fun userServiceExtras(binder: IBinder, token: String): Bundle = Bundle().apply {
        putBinder(NightProtocol.EXTRA_BINDER, binder)
        putString(NightProtocol.EXTRA_USER_SERVICE_TOKEN, token)
        putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token)
    }

    /**
     * Publishes the server's binders to the manager application in user 0.
     *
     * Returns `true` when at least one wire accepted the binder. [attempts] exists for cold
     * startup, where the manager process may not have started its provider yet; unit tests use
     * the default single attempt.
     */
    @JvmStatic
    @JvmOverloads
    fun publishToManager(
        legacyBinder: IBinder,
        nightBinder: IBinder,
        config: ServerConfig = ServerConfig.load(),
        attempts: Int = 1,
        retryDelayMs: Long = 1_000L
    ): Boolean {
        var published = false
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            val night = sendServerBinder(
                binder = nightBinder,
                wire = Wire.NIGHTZUKU,
                packageName = config.managerPackageName,
                userId = 0
            )
            val legacy = sendServerBinder(
                binder = legacyBinder,
                wire = Wire.SHIZUKU,
                packageName = config.managerPackageName,
                userId = 0
            )
            published = night || legacy
            if (published) {
                return true
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return published
    }

    /**
     * Sends [binder] to the provider of [packageName] in [userId].
     *
     * A dead provider is removed from ActivityManager's cache and reported as a failure; the
     * caller decides whether to retry (the standalone runtime retries at the publish level).
     */
    @JvmStatic
    fun sendServerBinder(
        binder: IBinder,
        wire: Wire,
        packageName: String,
        userId: Int
    ): Boolean {
        val authority = authorityFor(packageName, wire)
        var provider: IContentProvider? = null
        return try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, authority)
            if (provider == null) {
                logger.w("provider is null %s %d", authority, userId)
                return false
            }
            if (!provider.asBinder().pingBinder()) {
                logger.w("provider is dead %s %d", authority, userId)
                removeProvider(authority)
                provider = null
                return false
            }
            val reply = IContentProviderUtils.callCompat(
                provider,
                null,
                authority,
                wire.method,
                null,
                serverBinderExtras(binder, wire)
            )
            if (reply == null) {
                logger.w("failed to send binder (%s) to %s in user %d", wire.name, authority, userId)
                false
            } else {
                logger.i("sent binder (%s) to %s in user %d", wire.name, authority, userId)
                true
            }
        } catch (t: Throwable) {
            logger.w(t, "failed to send binder (%s) to %s in user %d", wire.name, authority, userId)
            false
        } finally {
            if (provider != null) {
                removeProvider(authority)
            }
        }
    }

    /**
     * Sends a UserService binder to [authority] and links to the returned server binder's death.
     *
     * [onServerDied] runs on a Binder thread when the server dies; the starter uses it to exit
     * instead of lingering as an orphaned privileged process.
     */
    @JvmStatic
    @JvmOverloads
    fun sendUserService(
        binder: IBinder,
        token: String,
        authority: String,
        userId: Int,
        onServerDied: (() -> Unit)? = null
    ): UserServiceReply {
        var provider: IContentProvider? = null
        return try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, userId, null, authority)
            if (provider == null) {
                logger.w("provider is null %s %d", authority, userId)
                return UserServiceReply(null, false)
            }
            if (!provider.asBinder().pingBinder()) {
                logger.w("provider is dead %s %d", authority, userId)
                removeProvider(authority)
                provider = null
                return UserServiceReply(null, false)
            }
            val reply = IContentProviderUtils.callCompat(
                provider,
                null,
                authority,
                NightProtocol.METHOD_SEND_USER_SERVICE,
                null,
                userServiceExtras(binder, token)
            )
            val serverBinder = parseServerBinderReply(reply, onServerDied)
            UserServiceReply(serverBinder, serverBinder != null && onServerDied != null)
        } catch (t: Throwable) {
            logger.w(t, "failed to send UserService binder to %s in user %d", authority, userId)
            UserServiceReply(null, false)
        } finally {
            if (provider != null) {
                removeProvider(authority)
            }
        }
    }

    /**
     * Extracts the server binder from a provider reply and, when requested, links its death.
     *
     * Returns `null` for a missing/dead binder or when the death link cannot be established, so a
     * caller can never mistake an unreachable server for a successful handshake.
     */
    @JvmStatic
    @JvmOverloads
    fun parseServerBinderReply(
        reply: Bundle?,
        onServerDied: (() -> Unit)? = null
    ): IBinder? {
        val binder = LegacyParcelCodec.extractBinderFromBundle(reply) ?: return null
        if (!binder.pingBinder()) {
            return null
        }
        if (onServerDied != null) {
            try {
                binder.linkToDeath({ onServerDied() }, 0)
            } catch (_: RemoteException) {
                return null
            }
        }
        return binder
    }

    private fun removeProvider(authority: String) {
        try {
            ActivityManagerApis.removeContentProviderExternal(authority, null)
        } catch (t: Throwable) {
            logger.w(t, "removeContentProviderExternal(%s)", authority)
        }
    }
}
