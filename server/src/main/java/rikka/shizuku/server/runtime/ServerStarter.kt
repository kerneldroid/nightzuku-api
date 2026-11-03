package rikka.shizuku.server.runtime

import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import kerneldroid.nightzuku.api.LegacyParcelCodec
import kerneldroid.nightzuku.api.NightProtocol
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.UserService
import rikka.shizuku.server.util.IContentProviderUtils
import rikka.shizuku.server.util.PackageSignatureVerifier

/**
 * Privileged entrypoint for a UserService process.
 *
 * The server launches this class through `app_process` with the manager APK on the classpath and a
 * one-shot launch token. Before loading any code from the target package, the starter proves two
 * things: the token belongs to a still-pending server record (so a stale or forged command line
 * cannot start arbitrary code), and the target installation still matches the app id and signing
 * certificates the server recorded. Only then does it create the service binder and hand it back.
 */
object ServerStarter {

    const val DEFAULT_STARTER_CLASS: String = ServerConfig.DEFAULT_USER_SERVICE_STARTER_CLASS

    private const val TAG = "NightzukuStarter"
    private const val LEGACY_CLAIM_USER_SERVICE_TRANSACTION = 10004
    private const val LAUNCH_TOKEN_TIMEOUT_MS = 5_000L
    private const val LAUNCH_TOKEN_RETRY_MS = 250L

    private const val APP_PROCESS_64 = "/system/bin/app_process"
    private const val APP_PROCESS_32 = "/system/bin/app_process32"

    /**
     * Strong reference to the server binder whose death ends this process.
     *
     * The death link itself is owned by the Binder proxy; keeping the reference makes the
     * relationship explicit and prevents the proxy from being collected between the reply and the
     * looper starting.
     */
    @Volatile
    private var serverBinder: IBinder? = null

    /** Quotes one command-line argument for `/system/bin/sh`. */
    @JvmStatic
    fun escapeShellArg(arg: String?): String {
        if (arg == null) return "''"
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Debug runtime arguments for the given platform level.
     *
     * Kept as an explicit function (instead of a `Build.VERSION` constant) so tests can assert the
     * argument set for every supported branch on any host.
     */
    @JvmStatic
    fun debugArgs(sdkInt: Int = Build.VERSION.SDK_INT): String = when {
        sdkInt >= 30 ->
            "-Xcompiler-option --debuggable " +
                "-XjdwpProvider:adbconnection " +
                "-XjdwpOptions:suspend=n,server=y"
        sdkInt >= 28 ->
            "-Xcompiler-option --debuggable " +
                "-XjdwpProvider:internal " +
                "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
        else ->
            "-Xcompiler-option --debuggable " +
                "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
    }

    /** Selects the 32-bit runtime only when the caller asked for it and the binary exists. */
    @JvmStatic
    @JvmOverloads
    fun appProcessPath(
        use32Bits: Boolean,
        exists: (String) -> Boolean = { File(it).exists() }
    ): String = if (use32Bits && exists(APP_PROCESS_32)) APP_PROCESS_32 else APP_PROCESS_64

    /**
     * Builds the `sh` command that starts a UserService process.
     *
     * The whole command is wrapped in parentheses and backgrounded, matching the historical
     * Shizuku launcher so the privileged server's `sh` exits as soon as the process is forked.
     * [starterClass] exists for deployments that relocate the starter; the default points at this
     * module.
     */
    @JvmStatic
    @JvmOverloads
    fun commandForUserService(
        appProcess: String,
        managerApkPath: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String,
        callingUid: Int,
        debug: Boolean,
        expectedSigners: Set<String> = emptySet(),
        starterClass: String = DEFAULT_STARTER_CLASS
    ): String {
        val processName = String.format(Locale.ENGLISH, "%s:%s", packageName, processNameSuffix)
        val signersArg = if (expectedSigners.isNotEmpty()) {
            " --signers=" + escapeShellArg(expectedSigners.joinToString(","))
        } else {
            ""
        }
        return buildString {
            append("(CLASSPATH=")
            append(escapeShellArg(managerApkPath))
            append(' ')
            append(appProcess)
            if (debug) {
                append(' ')
                append(debugArgs())
            }
            append(" /system/bin --nice-name=")
            append(escapeShellArg(processName))
            append(' ')
            append(starterClass)
            append(" --token=")
            append(escapeShellArg(token))
            append(" --package=")
            append(escapeShellArg(packageName))
            append(" --class=")
            append(escapeShellArg(classname))
            append(" --uid=")
            append(callingUid)
            append(signersArg)
            if (debug) {
                append(" --debug-name=")
                append(escapeShellArg(processName))
            }
            append(")&")
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val launch = parseArgs(args)
        val config = ServerConfig.load()
        val authority = config.managerPackageName + NightProtocol.PROVIDER_SUFFIX

        if (!isLaunchTokenLive(config, authority, launch.token)) {
            Log.w(TAG, "UserService launch token is not active, aborting")
            System.exit(1)
            return
        }

        if (!verifyTargetPackageIdentity(
                launch.packageName,
                launch.uid,
                launch.expectedSigners
            )
        ) {
            Log.e(TAG, "UserService target package failed identity verification, aborting")
            System.exit(1)
            return
        }

        UserService.setTag(TAG)
        val created = UserService.create(args)
        if (created == null) {
            Log.e(TAG, "UserService.create returned no binder")
            System.exit(1)
            return
        }

        val service = created.first
        val token = created.second
        if (!sendBinder(service, token, authority)) {
            System.exit(1)
            return
        }

        Looper.loop()
        Log.i(TAG, "service exited")
    }

    internal data class LaunchArgs(
        val token: String?,
        val packageName: String?,
        val uid: Int,
        val expectedSigners: Set<String>
    )

    internal fun parseArgs(args: Array<String>): LaunchArgs {
        var token: String? = null
        var packageName: String? = null
        var uid = -1
        var signers: Set<String> = emptySet()
        for (arg in args) {
            when {
                arg.startsWith("--token=") -> token = arg.substring("--token=".length)
                arg.startsWith("--package=") -> packageName = arg.substring("--package=".length)
                arg.startsWith("--uid=") -> uid = arg.substring("--uid=".length).toIntOrNull() ?: -1
                arg.startsWith("--signers=") -> {
                    signers = arg.substring("--signers=".length)
                        .split(',')
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
            }
        }
        return LaunchArgs(token, packageName, uid, signers)
    }

    private fun verifyTargetPackageIdentity(
        packageName: String?,
        callingUid: Int,
        expectedSigners: Set<String>
    ): Boolean {
        if (packageName.isNullOrEmpty() || callingUid <= 0) return false
        val userId = callingUid / 100_000
        val expectedAppId = callingUid % 100_000
        return try {
            val packageInfo = PackageManagerApis.getPackageInfoNoThrow(
                packageName,
                PackageSignatureVerifier.signatureLookupFlags(),
                userId
            ) ?: return false
            val snapshot = PackageSignatureVerifier.extractSnapshot(packageInfo, userId) ?: return false
            if (snapshot.appId != expectedAppId) {
                Log.e(
                    TAG,
                    "Target package $packageName appId=${snapshot.appId} does not match expected $expectedAppId"
                )
                return false
            }
            if (expectedSigners.isNotEmpty()) {
                val baseline = PackageSignatureVerifier.Baseline(
                    packageName,
                    expectedAppId,
                    expectedSigners
                )
                if (!baseline.matches(snapshot)) {
                    Log.e(TAG, "Target package $packageName signing certificate mismatch")
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to verify target package identity for $packageName", t)
            false
        }
    }

    private fun isLaunchTokenLive(
        config: ServerConfig,
        authority: String,
        token: String?
    ): Boolean {
        if (token.isNullOrEmpty()) return false
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < LAUNCH_TOKEN_TIMEOUT_MS) {
            val binder = fetchServerBinder(authority)
            if (binder != null && queryTokenLive(binder, token)) {
                return true
            }
            try {
                Thread.sleep(LAUNCH_TOKEN_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Log.w(TAG, "Timed out waiting for launch token on ${config.managerPackageName}")
        return false
    }

    private fun fetchServerBinder(authority: String): IBinder? {
        var provider: IContentProvider? = null
        return try {
            provider = ActivityManagerApis.getContentProviderExternal(authority, 0, null, authority)
                ?: return null
            if (!provider.asBinder().pingBinder()) {
                return null
            }
            val reply = IContentProviderUtils.callCompat(
                provider,
                null,
                authority,
                NightProtocol.METHOD_GET_BINDER,
                null,
                Bundle()
            )
            LegacyParcelCodec.extractBinderFromBundle(reply)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch the server binder from $authority", t)
            null
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(authority, null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun queryTokenLive(serverBinder: IBinder, token: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val descriptor = serverBinder.interfaceDescriptor ?: NightProtocol.DESCRIPTOR
            data.writeInterfaceToken(descriptor)
            data.writeString(token)
            val transactionCode = if (descriptor == NightProtocol.DESCRIPTOR) {
                NightProtocol.TRANSACTION_CLAIM_USER_SERVICE
            } else {
                LEGACY_CLAIM_USER_SERVICE_TRANSACTION
            }
            if (!serverBinder.transact(transactionCode, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            Log.w(TAG, "queryTokenLive failed", t)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendBinder(binder: IBinder, token: String, authority: String): Boolean {
        val reply = ProviderBinderClient.sendUserService(
            binder = binder,
            token = token,
            authority = authority,
            userId = 0,
            onServerDied = {
                Log.i(TAG, "server binder died, exiting")
                System.exit(0)
            }
        )
        val received = reply.serverBinder
        if (received == null) {
            Log.w(TAG, "server binder not received from $authority")
            return false
        }
        serverBinder = received
        Log.i(TAG, "sent UserService binder to $authority")
        return true
    }
}
