# Nightzuku API

Kotlin-first IPC protocol, client SDK, dual-wire `ContentProvider`, Rust-powered interactive PTY shell (`rish`) bridge, and privileged server foundation for [Nightzuku](https://github.com/kerneldroid/Nightzuku).

## Architecture & Modules

| Module | Namespace | Description |
| :--- | :--- | :--- |
| `:aidl` | `rikka.shizuku.aidl` | Low-level AIDL interfaces (`IShizukuService`, `IShizukuApplication`, `IShizukuServiceConnection`, `IRemoteProcess`). |
| `:shared` | `rikka.shizuku.shared` | Native `NightProtocol` wire constants, capability bitmasks, zero-stub `LegacyParcelCodec`, and `ShizukuApiConstants`. |
| `:api` | `rikka.shizuku.api` | Native `NightClient`, `NightBinderWrapper`, and `NightSystemProperties` (`kerneldroid.nightzuku.api`), plus the Kotlin-rewritten `rikka.shizuku.*` compatibility layer. |
| `:provider` | `rikka.shizuku.provider` | Dual-wire `NightProvider` and `ShizukuProvider` for receiving and proactively pulling privileged binders across `NightProtocol` and legacy Shizuku authorities. |
| `:rish` | `rikka.rish` | Interactive PTY shell bridge (`Rish`, `RishHost`, `RishTerminal`, `RishService`, `RishConfig`) backed by `librish.so` (Rust Edition 2024 in `rust/rish`). |
| `:server-shared` | `rikka.shizuku.server` | Privileged server abstractions (`Service`, `ClientManager`, `ConfigManager`, `UserServiceManager`, `UserService`, `RemoteProcessHolder`, `HostProcess`, `IContentProviderUtils`). |

## Native Nightzuku Client Usage

### 1. Declare Permission & Provider in `AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="kerneldroid.nightzuku.permission.API" />

    <application>
        <provider
            android:name="kerneldroid.nightzuku.api.NightProvider"
            android:authorities="${applicationId}.nightzuku;${applicationId}.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>
</manifest>
```

### 2. Connect, Pull Binder Proactively, Execute Commands & Bind `UserService`

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kerneldroid.nightzuku.api.NightBinderWrapper
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper

fun connectAndRun(context: Context) {
    // Optional fast pull if the provider did not yet receive a push from the server
    NightClient.connectFast(context)

    // Listen for binder lifecycle (race-free sticky registration)
    NightClient.addBinderReceivedListenerSticky {
        if (NightClient.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // Fast one-shot command execution over flat Parcel IPC
            if (NightClient.hasCapability(NightProtocol.CAP_SHELL_EXEC)) {
                val result = NightClient.execFast(arrayOf("id"))
                println("stdout: ${result.outputText}")
            }

            // Bind a privileged UserService over NightProtocol
            if (NightClient.hasCapability(NightProtocol.CAP_USER_SERVICE)) {
                val args = Shizuku.UserServiceArgs(ComponentName(context, MyPrivilegedService::class.java))
                    .processNameSuffix("privileged")
                NightClient.bindUserService(args, object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        // Connected to privileged UserService
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                })
            }

            // System service binder proxy forwarding
            if (NightClient.hasCapability(NightProtocol.CAP_BINDER_PROXY)) {
                val pmBinder = NightBinderWrapper(SystemServiceHelper.getSystemService("package")!!)
            }
        } else {
            NightClient.requestPermission(1001)
        }
    }
}
```

## Credits & Acknowledgments

- Original Shizuku API architecture by [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).
- Portions of the PTY relay poll/drain lifecycle (`:rish`), `/proc/<pid>/stat` process identity verification (`HostProcess`), `UserServiceManager` retry/pending-destroy orchestration, and `ShizukuServiceConnection` death-handling refinements were adapted from [d4rken-org/porter-api](https://github.com/d4rken-org/porter-api) (`eu.darken.porter`, Apache-2.0 / MIT).

## License

MIT License
