package rikka.shizuku.server.util

import android.os.Handler
import java.util.Objects

object HandlerUtil {

    @Volatile
    private var mainHandler: Handler? = null

    @JvmStatic
    fun setMainHandler(mainHandler: Handler) {
        HandlerUtil.mainHandler = mainHandler
    }

    @JvmStatic
    fun getMainHandler(): Handler {
        return Objects.requireNonNull(mainHandler, "Please call setMainHandler first")!!
    }
}
