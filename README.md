# Nightzuku API

Kotlin-first IPC protocol, client SDK, dual-wire `ContentProvider`, Rust-powered interactive PTY shell (`rish`) and process-group spawner bridge, and privileged server foundation for [Nightzuku](https://github.com/kerneldroid/Nightzuku).

## Architecture & Modules

| Module | Namespace | Description |
| :--- | :--- | :--- |
| `:aidl` | `rikka.shizuku.aidl` | Low-level AIDL interfaces (`IShizukuService`, `IShizukuApplication`, `IShizukuServiceConnection`, `IRemoteProcess` with `getExitFd()`). |
| `:shared` | `rikka.shizuku.shared` | Native `NightProtocol` wire constants, capability bitmasks, handshake status codes, zero-stub `LegacyParcelCodec`, and `ShizukuApiConstants`. |
| `:api` | `rikka.shizuku.api` | Native `NightClient`, atomic `NightSession`, `NightDiagnostics`, `NightBinderWrapper`, `NightSystemProperties` (`kerneldroid.nightzuku.api`), consumer R8 rules (`consumer-rules.pro`), and the Kotlin-rewritten `rikka.shizuku.*` layer with kernel-EOF `ShizukuRemoteProcess`. |
| `:provider` | `rikka.shizuku.provider` | Dual-wire `NightProvider` and `ShizukuProvider` with kernel `callingUid` validation and binder-free multi-process broadcast signaling. |
| `:rish` | `rikka.rish` | Interactive PTY shell bridge (`Rish`, `RishHost`, `RishTerminal`, `RishService`, `RishConfig`) and `nativeSpawnGroup` (`setsid`) process-group spawner backed by `librish.so` (Rust Edition 2024 in `rust/rish`). |
| `:server-shared` | `rikka.shizuku.server` | Privileged server abstractions (`Service`, `ClientManager`, `ConfigManager`, `UserServiceManager`, `UserService`, `RemoteProcessHolder`, `HostProcess`, `IContentProviderUtils`). |
| `:server` | `rikka.shizuku.server.runtime` | Concrete `app_process` runtime: `NightzukuServerService.main`, `ServerStarter` UserService entrypoint, `ProviderBinderClient` binder publishing, `ServerConfig`, and deployment managers backed by `server-shared`. No Android application/UI. |

## Key Engineering Highlights

1. **Kernel EOF Pipe Waiting (`exitPipe` + `Os.poll`)**: `RemoteProcessHolder` creates a kernel pipe (`ParcelFileDescriptor.createPipe()`) and closes its write end immediately upon process termination; `ShizukuRemoteProcess.waitFor()` / `waitForTimeout()` polls `exitPipe[0]` via `Os.poll` with **0 Binder polling transactions** and microsecond wake-up latency.
2. **Non-Blocking `oneway` Async Execution (`TRANSACTION_EXEC_ASYNC`)**: `NightClient.execAsync` (`suspendCancellableCoroutine`) and `NightClient.execAsyncFuture` (`CompletableFuture<ExecResult>`) dispatch `IBinder.FLAG_ONEWAY` requests and receive results over `INightCallback` (`CALLBACK_ON_EXEC_RESULT`), sending `oneway TRANSACTION_CANCEL_EXEC` on cancellation without tying up client or server Binder threads.
3. **Rust `setsid` Process-Group Spawner (`nativeSpawnGroup`)**: `librish.so` exports `RishHost.nativeSpawnGroup(cmd, env, dir)` which `fork()`s, calls `libc::setsid()` (`pgid == pid`), attaches `O_CLOEXEC` stdio pipes, and enables instant process-tree termination via `Os.kill(-pid, OsConstants.SIGKILL)`.
4. **Kernel `callingUid` Guard & Binder-Free Multi-Process Broadcast**: `ShizukuProvider.call()` enforces `callingUid in {0, 2000, Process.myUid()}` before accepting or serving binders, and `enableMultiProcess` sends an empty broadcast signal so secondary processes always pull the binder from their own verified provider authority.
5. **Lock-Free `NightSession` Snapshot & `NightDiagnostics`**: `NightClient` stores an immutable `NightSession` inside `AtomicReference<NightSession?>` and reports protocol version mismatches explicitly via `NightDiagnostics.Incompatible(clientVersion, serverVersion, serverMinVersion, status)`.
6. **Hardened `UserServiceManager` & R8 Consumer Rules**: `UserServiceManager` closes `sh` stdout/stderr streams, enforces a 15-second launch timeout, checks `isCallerStillAllowed` inside `synchronized(this)` before record creation, and ships `api/consumer-rules.pro` to preserve `IBinder` service constructors under R8.

## Building

Requirements:

- JDK 21
- Android SDK platform 37.0, build-tools 37.0.0, NDK `29.0.14206865`
- Rust 1.85+ with the four Android targets and `cargo-ndk`

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
cargo install cargo-ndk --locked

cp local.properties.example local.properties   # set sdk.dir
./gradlew build

# Rust unit tests
cargo test --manifest-path rust/Cargo.toml --workspace
```

`local.properties` is intentionally untracked. CI runs the same commands on JDK 21 via
`.github/workflows/build.yml`.

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
            // Fast synchronous or non-blocking oneway async command execution
            if (NightClient.hasCapability(NightProtocol.CAP_SHELL_EXEC)) {
                val result = NightClient.execFast(arrayOf("id"))
                println("stdout: ${result.outputText}")

                NightClient.execAsyncFuture(arrayOf("uname", "-a")).thenAccept { asyncRes ->
                    println("async stdout: ${asyncRes.outputText}")
                }
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
