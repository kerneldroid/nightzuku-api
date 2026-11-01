package rikka.shizuku

import android.annotation.SuppressLint
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("PrivateApi")
object SystemServiceHelper {

    private const val TAG = "SystemServiceHelper"
    private val SYSTEM_SERVICE_CACHE = ConcurrentHashMap<String, IBinder>()
    private val TRANSACT_CODE_CACHE = ConcurrentHashMap<String, Int>()

    private var getServiceMethod: Method? = null

    init {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            getServiceMethod = sm.getMethod("getService", String::class.java)
        } catch (e: Throwable) {
            Log.w(TAG, Log.getStackTraceString(e))
        }
    }

    @JvmStatic
    fun getSystemService(name: String): IBinder? {
        var binder = SYSTEM_SERVICE_CACHE[name]
        if (binder == null || !binder.pingBinder()) {
            try {
                binder = getServiceMethod?.invoke(null, name) as? IBinder
            } catch (e: IllegalAccessException) {
                Log.w(TAG, Log.getStackTraceString(e))
            } catch (e: InvocationTargetException) {
                Log.w(TAG, Log.getStackTraceString(e))
            }
            if (binder != null) {
                SYSTEM_SERVICE_CACHE[name] = binder
            } else {
                SYSTEM_SERVICE_CACHE.remove(name)
            }
        }
        return binder
    }

    @JvmStatic
    fun clearCache() {
        SYSTEM_SERVICE_CACHE.clear()
    }

    @JvmStatic
    fun getTransactionCode(className: String, methodName: String): Int? {
        val fieldName = "TRANSACTION_$methodName"
        val key = "$className.$fieldName"

        TRANSACT_CODE_CACHE[key]?.let { return it }

        try {
            val cls = Class.forName(className)
            var declaredField: Field? = null
            try {
                declaredField = cls.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                for (f in cls.declaredFields) {
                    if (f.type != Int::class.javaPrimitiveType) continue
                    val name = f.name
                    if (name.startsWith("${fieldName}_") &&
                        TextUtils.isDigitsOnly(name.substring(fieldName.length + 1))
                    ) {
                        declaredField = f
                        break
                    }
                }
            }
            if (declaredField == null) return null

            declaredField.isAccessible = true
            val value = declaredField.getInt(cls)
            TRANSACT_CODE_CACHE[key] = value
            return value
        } catch (e: Throwable) {
            Log.w(TAG, Log.getStackTraceString(e))
        }
        return null
    }
}
