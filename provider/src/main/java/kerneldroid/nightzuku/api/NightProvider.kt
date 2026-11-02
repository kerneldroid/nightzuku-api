package kerneldroid.nightzuku.api

import android.content.Context
import rikka.shizuku.ShizukuProvider

/**
 * First-class Nightzuku [android.content.ContentProvider] for receiving the privileged
 * `INightService` binder (and optional legacy fallback binder) in client applications.
 */
open class NightProvider : ShizukuProvider() {

    companion object {
        const val PERMISSION: String = "kerneldroid.nightzuku.permission.API"
        const val MANAGER_APPLICATION_ID: String = "kerneldroid.nightzuku"

        @JvmStatic
        fun connectFast(context: Context): Boolean = NightClient.connectFast(context)
    }
}
