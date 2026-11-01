package rikka.shizuku.server

import android.app.ActivityThread
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextHidden
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.os.UserHandleHidden
import android.util.Log
import android.util.Pair
import dev.rikka.tools.refine.Refine
import java.lang.reflect.Constructor

object UserService {

    private var tag: String = "UserService"

    @JvmStatic
    fun setTag(tag: String) {
        this.tag = tag
    }

    @JvmStatic
    fun create(args: Array<String>): Pair<IBinder, String>? {
        var name: String? = null
        var token: String? = null
        var pkg: String? = null
        var cls: String? = null
        var uid = -1

        for (arg in args) {
            when {
                arg.startsWith("--debug-name=") -> name = arg.substring(13)
                arg.startsWith("--token=") -> token = arg.substring(8)
                arg.startsWith("--package=") -> pkg = arg.substring(10)
                arg.startsWith("--class=") -> cls = arg.substring(8)
                arg.startsWith("--uid=") -> uid = arg.substring(6).toIntOrNull() ?: -1
            }
        }

        if (pkg == null || cls == null || token == null) {
            return null
        }

        val userId = uid / 100000
        Log.i(tag, String.format("starting service %s/%s...", pkg, cls))

        val service: IBinder = try {
            val activityThread = ActivityThread.systemMain()
            val systemContext = activityThread.systemContext

            val processName = name ?: "$pkg:user_service"
            DdmHandleAppName.setAppName(processName, userId)

            val userHandle: UserHandle = Refine.unsafeCast(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    UserHandleHidden.of(userId)
                } else {
                    UserHandleHidden(userId)
                }
            )
            val context = Refine.unsafeCast<ContextHidden>(systemContext).createPackageContextAsUser(
                pkg,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                userHandle
            )
            val mPackageInfo = context.javaClass.getDeclaredField("mPackageInfo").apply {
                isAccessible = true
            }
            val loadedApk = mPackageInfo.get(context)!!

            try {
                val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val appBindDataCtor = appBindDataClass.getDeclaredConstructor().apply {
                    isAccessible = true
                }
                val appBindData = appBindDataCtor.newInstance()

                setAppBindDataField(appBindDataClass, appBindData, "processName", processName)

                val appInfo = try {
                    loadedApk.javaClass.getMethod("getApplicationInfo").invoke(loadedApk)
                } catch (_: Throwable) {
                    try {
                        loadedApk.javaClass.getDeclaredField("mApplicationInfo").apply {
                            isAccessible = true
                        }.get(loadedApk)
                    } catch (_: Throwable) {
                        null
                    }
                }
                setAppBindDataField(appBindDataClass, appBindData, "appInfo", appInfo)

                try {
                    val compatInfo = Class.forName("android.content.res.CompatibilityInfo")
                        .getField("DEFAULT_COMPATIBILITY_INFO")
                        .get(null)
                    setAppBindDataField(appBindDataClass, appBindData, "compatInfo", compatInfo)
                } catch (_: Throwable) {
                }

                activityThread.javaClass.getDeclaredField("mBoundApplication").apply {
                    isAccessible = true
                    set(activityThread, appBindData)
                }
            } catch (_: Throwable) {
            }

            val makeApplication = loadedApk.javaClass.getDeclaredMethod(
                "makeApplication",
                Boolean::class.javaPrimitiveType,
                Instrumentation::class.java
            )
            val application = makeApplication.invoke(loadedApk, true, null) as Application
            activityThread.javaClass.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
                set(activityThread, application)
            }

            val classLoader = application.classLoader
            val serviceClass = classLoader.loadClass(cls)
            var constructorWithContext: Constructor<*>? = null
            try {
                constructorWithContext = serviceClass.getConstructor(Context::class.java)
            } catch (_: NoSuchMethodException) {
            } catch (_: SecurityException) {
            }

            if (constructorWithContext != null) {
                constructorWithContext.newInstance(application) as IBinder
            } else {
                serviceClass.getDeclaredConstructor().newInstance() as IBinder
            }
        } catch (tr: Throwable) {
            Log.w(tag, String.format("unable to start service %s/%s...", pkg, cls), tr)
            return null
        }

        return Pair(service, token)
    }

    private fun setAppBindDataField(
        appBindDataClass: Class<*>,
        appBindData: Any,
        fieldName: String,
        value: Any?
    ) {
        if (value == null) return
        try {
            appBindDataClass.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(appBindData, value)
            }
        } catch (_: Throwable) {
        }
    }
}
