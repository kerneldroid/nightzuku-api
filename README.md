# Nightzuku API

Kotlin-first IPC protocol, client SDK, dual-wire `ContentProvider`, interactive shell (`rish`) bridge, and privileged server foundation for [Nightzuku](https://github.com/kerneldroid/Nightzuku).

## Architecture & Modules

| Module | Namespace | Description |
| :--- | :--- | :--- |
| `:aidl` | `rikka.shizuku.aidl` | Low-level AIDL interfaces (`IShizukuService`, `IShizukuApplication`, `IShizukuServiceConnection`, `IRemoteProcess`). |
| `:shared` | `rikka.shizuku.shared` | Native `NightProtocol` wire constants, capability bitmasks, zero-stub `LegacyParcelCodec`, and `ShizukuApiConstants`. |
| `:api` | `rikka.shizuku.api` | Native `NightClient`, `NightBinderWrapper`, and `NightSystemProperties` (`kerneldroid.nightzuku.api`), plus the Kotlin-rewritten `rikka.shizuku.*` compatibility layer. |
| `:provider` | `rikka.shizuku.provider` | Dual-wire `NightProvider` and `ShizukuProvider` for receiving privileged binders across `NightProtocol` and legacy Shizuku authorities. |
| `:rish` | `rikka.rish` | Interactive PTY shell bridge (`Rish`, `RishHost`, `RishTerminal`, `RishService`, `RishConfig`). |
| `:server-shared` | `rikka.shizuku.server` | Privileged server abstractions (`Service`, `ClientManager`, `ConfigManager`, `UserServiceManager`, `UserService`, `RemoteProcessHolder`, `IContentProviderUtils`). |

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

### 2. Connect & Execute via `NightClient`

```kotlin
import android.content.pm.PackageManager
import kerneldroid.nightzuku.api.NightBinderWrapper
import kerneldroid.nightzuku.api.NightClient
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.SystemServiceHelper

// Listen for binder lifecycle
NightClient.addBinderReceivedListenerSticky {
    if (NightClient.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        // Fast one-shot command execution over flat Parcel IPC
        if (NightClient.hasCapability(NightProtocol.CAP_SHELL_EXEC)) {
            val result = NightClient.execFast(arrayOf("id"))
            println("stdout: ${result.outputText}")
        }

        // System service binder proxy forwarding
        if (NightClient.hasCapability(NightProtocol.CAP_BINDER_PROXY)) {
            val pmBinder = NightBinderWrapper(SystemServiceHelper.getSystemService("package")!!)
        }
    } else {
        NightClient.requestPermission(1001)
    }
}
```

## License

MIT License
