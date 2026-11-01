package rikka.shizuku.server.util

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.RemoteException

/**
 * Unified cross-API [IContentProvider.call] compatibility helper shared by both
 * the privileged Nightzuku server and UserService starter.
 */
object IContentProviderUtils {

    @JvmStatic
    @JvmOverloads
    @Throws(RemoteException::class)
    fun callCompat(
        provider: IContentProvider,
        callingPkg: String?,
        authority: String?,
        method: String?,
        arg: String?,
        extras: Bundle?,
        attributeTag: String? = null
    ): Bundle? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                try {
                    val source = AttributionSource.Builder(OsUtils.getUid())
                        .setAttributionTag(attributeTag)
                        .setPackageName(callingPkg)
                        .build()
                    provider.call(source, authority, method, arg, extras)
                } catch (_: LinkageError) {
                    provider.call(callingPkg, attributeTag, authority, method, arg, extras)
                }
            }
            Build.VERSION.SDK_INT >= 30 -> {
                provider.call(callingPkg, attributeTag, authority, method, arg, extras)
            }
            Build.VERSION.SDK_INT >= 29 -> {
                provider.call(callingPkg, authority, method, arg, extras)
            }
            else -> {
                provider.call(callingPkg, method, arg, extras)
            }
        }
    }
}
