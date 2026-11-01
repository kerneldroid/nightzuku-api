package rikka.shizuku.server

import android.os.Bundle
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.server.util.Logger

open class ClientRecord(
    @JvmField val uid: Int,
    @JvmField val pid: Int,
    @JvmField val client: IShizukuApplication?,
    @JvmField val packageName: String,
    @JvmField val apiVersion: Int
) {

    @JvmField
    var allowed: Boolean = false

    open fun dispatchRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        val reply = Bundle().apply {
            putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        }
        try {
            client?.dispatchRequestPermissionResult(requestCode, reply)
        } catch (e: Throwable) {
            LOGGER.w(
                e,
                "dispatchRequestPermissionResult failed for client (uid=%d, pid=%d, package=%s)",
                uid,
                pid,
                packageName
            )
        }
    }

    companion object {
        @JvmField
        protected val LOGGER: Logger = Logger("ClientRecord")
    }
}
