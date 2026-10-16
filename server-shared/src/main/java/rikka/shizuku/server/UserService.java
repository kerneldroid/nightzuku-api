package rikka.shizuku.server;

import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextHidden;
import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.rikka.tools.refine.Refine;

public class UserService {

    private static String TAG;

    public static void setTag(String tag) {
        UserService.TAG = tag;
    }

    @Nullable
    public static Pair<IBinder, String> create(String[] args) {
        String name = null;
        String token = null;
        String pkg = null;
        String cls = null;
        int uid = -1;

        for (String arg : args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8);
            } else if (arg.startsWith("--package=")) {
                pkg = arg.substring(10);
            } else if (arg.startsWith("--class=")) {
                cls = arg.substring(8);
            } else if (arg.startsWith("--uid=")) {
                uid = Integer.parseInt(arg.substring(6));
            }
        }

        int userId = uid / 100000;

        Log.i(TAG, String.format("starting service %s/%s...", pkg, cls));

        IBinder service;

        try {
            ActivityThread activityThread = ActivityThread.systemMain();
            Context systemContext = activityThread.getSystemContext();

            DdmHandleAppName.setAppName(name != null ? name : pkg + ":user_service", userId);

            //noinspection InstantiationOfUtilityClass
            UserHandle userHandle = Refine.unsafeCast(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? UserHandleHidden.of(userId)
                            : new UserHandleHidden(userId));
            Context context = Refine.<ContextHidden>unsafeCast(systemContext).createPackageContextAsUser(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
            Field mPackageInfo = context.getClass().getDeclaredField("mPackageInfo");
            mPackageInfo.setAccessible(true);
            Object loadedApk = mPackageInfo.get(context);

            // OEM frameworks (Xiaomi/HyperOS, Transsion, ...) add an equality check against
            // Application.getProcessName() inside LoadedApk#makeApplicationInner. ActivityThread
            // #systemMain() leaves mBoundApplication null, so getProcessName() returns null there
            // and the OEM code throws a NullPointerException, killing this process.
            // Install a minimal AppBindData carrying the process name before calling it.
            // Ref: https://github.com/RikkaApps/Shizuku/issues/1198
            String processName = name != null ? name : pkg + ":user_service";
            try {
                Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
                java.lang.reflect.Constructor<?> appBindDataCtor = appBindDataClass.getDeclaredConstructor();
                appBindDataCtor.setAccessible(true);
                Object appBindData = appBindDataCtor.newInstance();

                setAppBindDataField(appBindDataClass, appBindData, "processName", processName);

                // Android 17's ActivityThread.currentPackageName() reads
                // mBoundApplication.appInfo.packageName while LoadedApk builds the class loader,
                // so appInfo must be non-null too, not just processName.
                Object appInfo = null;
                try {
                    appInfo = loadedApk.getClass().getMethod("getApplicationInfo").invoke(loadedApk);
                } catch (Throwable ignored1) {
                    try {
                        Field mApplicationInfo = loadedApk.getClass().getDeclaredField("mApplicationInfo");
                        mApplicationInfo.setAccessible(true);
                        appInfo = mApplicationInfo.get(loadedApk);
                    } catch (Throwable ignored2) {
                        // appInfo stays null; the original path is no worse than before.
                    }
                }
                setAppBindDataField(appBindDataClass, appBindData, "appInfo", appInfo);

                // The class loader path also consults compatInfo on some versions.
                try {
                    Object compatInfo = Class.forName("android.content.res.CompatibilityInfo")
                            .getField("DEFAULT_COMPATIBILITY_INFO").get(null);
                    setAppBindDataField(appBindDataClass, appBindData, "compatInfo", compatInfo);
                } catch (Throwable ignored3) {
                    // Optional; absence is tolerated.
                }

                Field mBoundApplicationField = activityThread.getClass().getDeclaredField("mBoundApplication");
                mBoundApplicationField.setAccessible(true);
                mBoundApplicationField.set(activityThread, appBindData);
            } catch (Throwable ignored) {
                // Stock frameworks do not need this; never make it fatal.
            }

            Method makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
            Application application = (Application) makeApplication.invoke(loadedApk, true, null);
            Field mInitialApplication = activityThread.getClass().getDeclaredField("mInitialApplication");
            mInitialApplication.setAccessible(true);
            mInitialApplication.set(activityThread, application);

            ClassLoader classLoader = application.getClassLoader();
            Class<?> serviceClass = classLoader.loadClass(cls);
            Constructor<?> constructorWithContext = null;
            try {
                constructorWithContext = serviceClass.getConstructor(Context.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
            }
            if (constructorWithContext != null) {
                service = (IBinder) constructorWithContext.newInstance(application);
            } else {
                service = (IBinder) serviceClass.newInstance();
            }
        } catch (Throwable tr) {
            Log.w(TAG, String.format("unable to start service %s/%s...", pkg, cls), tr);
            return null;
        }

        return new Pair<>(service, token);
    }

    private static void setAppBindDataField(Class<?> appBindDataClass, Object appBindData, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        try {
            Field field = appBindDataClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(appBindData, value);
        } catch (Throwable ignored) {
            // Field missing on this platform version; skip it.
        }
    }
}
