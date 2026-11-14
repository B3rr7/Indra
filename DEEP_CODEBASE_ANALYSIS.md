> **SUPERSEDED — do not cite its numbers.** Measured figures (file counts, `@Test` counts,
> plugin/tool counts, targetSdk, native components) in this file were wrong in 23 places.
> Use [VERIFIED_COMPARISON.md](VERIFIED_COMPARISON.md) instead.

# Indra Android Pentesting Suite — Deep Codebase Analysis

*Generated from comprehensive Gortex MCP exploration of the full codebase*

---

## Executive Summary

Indra is a **multi-module Android pentesting suite** built on the legacy cSploit native core, modernized with:
- **Kotlin + Jetpack Compose + Material 3** (modern UI stack)
- **Hilt DI** (dependency injection)
- **Navigation 3 with single back stack** (typed destinations, session-scoped ViewModels)
- **Coroutines + Flow** (async/reactive)
- **Rust `indrad` daemon** (replacing cSploit's C daemon) — pure-Kotlin `KotlinDaemonClient` over Unix domain sockets
- **Hybrid execution model**: daemon for structured tools, **direct `su -c` via Kotlin `ProcessManager`** for raw tools

**Codebase scale**: ~216 main-source Kotlin files across 5 modules, ~484 unit tests, detekt clean.

---

## Module Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         :indra (App Shell)                          │
│  MainActivity • IndraApplication • IndraNavHost • BottomNavBar     │
│  HomeScreen • ToolsScreen • SettingsScreen • TerminalScreen        │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ depends on all
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│    :core      │  │    :data      │  │     :ui       │
│ (system core) │  │ (repositories)│  │ (theme/comps) │
└───────┬───────┘  └───────┬───────┘  └───────┬───────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           ▼
                    ┌───────────────┐
                    │  :features    │
                    │ (36 plugins)  │
                    └───────────────┘
```

### Module Details

| Module | Source Files | Key Responsibilities |
|--------|-------------|---------------------|
| **core** | 100 | `SmartToolBox`, `CoreManager`, `ProcessManager`, `ChildProcessManager`, `RootManager`, `KotlinDaemonClient`, `PluginManager`, `PluginAvailability`, `OtaUpdateManager`, `ChrootEngine`, `LongRunController`, `PortScan`, `TerminalTransport`, `MonitorMode`, `Hashcat`, `MsfLaunch`, `AlpineManifest`, `BundleUpdateManager`, `ToolRunner` interface |
| **data** | 2 | `NetworkRepository` only (domain/usecase layer deleted — ADR 0001) |
| **ui** | 9 | `Material3` theme + `Flat.kt`, `OutputConsole`/`LiveOutputCard`/`FormError`, `HostSuggestions`, `WallpaperBackground` |
| **features** | 80 | 36 plugin screens + ViewModels (MVVM with `StateFlow<UiState>`) |
| **indra** | 25 | App shell, DI (`AppModule`), Navigation 3 graph, 4 main screens |

---

## The Hybrid Execution Model (Unique to Indra)

**This is the core architectural differentiator.** Indra does not force everything through the daemon.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        SMARTTOOLBOX (Singleton)                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ TOOL_SPECS — single source of truth (17 entries)                │   │
│  │   name        │ handler   │ binary        │ description         │   │
│  │ ──────────────┼───────────┼───────────────┼──────────────────── │   │
│  │ nmap          │ nmap      │ nmap          │ Network scanner     │   │
│  │ masscan       │ direct    │ masscan       │ High-speed scanner  │   │
│  │ hydra         │ direct    │ hydra         │ Login cracker       │   │
│  │ aircrack-ng   │ direct    │ aircrack-ng   │ WiFi cracker        │   │
│  │ reaver        │ direct    │ reaver        │ WPS cracker         │   │
│  │ hping3        │ direct    │ hping3        │ Packet crafter      │   │
│  │ nuclei        │ direct    │ nuclei        │ Vuln scanner        │   │
│  │ gobuster      │ raw       │ gobuster      │ Dir brute-forcer    │   │
│  │ bettercap     │ direct    │ bettercap     │ Attack framework    │   │
│  │ arpspoof      │ direct    │ arpspoof      │ ARP spoofer         │   │
│  │ tcpdump       │ direct    │ tcpdump       │ Packet capture      │   │
│  │ ettercap      │ ettercap  │ ettercap      │ MITM tool           │   │
│  │ fusemounts    │ fusemounts│ fusemounts    │ FUSE mounts         │   │
│  │ network-radar │ network-radar│ n-radar    │ Auto-scanner        │   │
│  │ msfrpcd       │ msfrpcd   │ msfrpcd       │ MSF RPC daemon      │   │
│  │ shell         │ raw       │ sh            │ Shell executor      │   │
│  │ raw           │ raw       │ raw           │ Raw socket          │   │
│  │ blind         │ blind     │ sh            │ Blind handler       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  DAEMON_HANDLERS = {nmap, ettercap, tcpdump, arpspoof, fusemounts,   │   │
│                      network-radar, msfrpcd, raw, blind}               │   │
│  DIRECT_HANDLERS = {direct}  ← pseudo-handler, daemon never reports  │   │
│                                                                         │
│  isDirectTool(name) → checks if handler ∈ DIRECT_HANDLERS            │   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │   DAEMON     │ │   DIRECT     │ │    RAW       │
     │  (via indrad)│ │  (su -c Kotlin)│ │ (raw handler)│
     └──────────────┘ └──────────────┘ └──────────────┘
     nmap, ettercap  masscan, hydra,  gobuster, shell,
     tcpdump*,       aircrack-ng,     raw, blind
     arpspoof*,      reaver, hping3,
     network-radar,  nuclei, bettercap,
     fusemounts,     arpspoof*, tcpdump*
     msfrpcd
```

*Tools marked `*` appear in both daemon and direct paths — screens choose based on context.*

### Execution Flows

#### 1. Daemon Path (`SmartToolBox.execute` → `KotlinDaemonClient.startCommand`)
- Structured events: `PortFound`, `OsFound`, `Hop`, `Message`, `Ended`, `Crashed`
- Only **nmap** handler emits structured events; all others drop unrecognized lines
- `raw` handler streams stdout/stderr verbatim as `Newline`/`StderrNewline`
- `ChildProcessManager` tracks pids for `stopAllRunning()` (OTA swaps)

#### 2. Direct `su -c` Path (`SmartToolBox.runDirect` → `ProcessManager.execute`)
- **Pure Kotlin**, bypasses daemon entirely
- `ProcessBuilder("su", "-c", shellCmd)` with `INDRA_PID=$$` marker for reliable kill
- `descendantKillScript` (ps/awk) kills entire process tree TERM→KILL
- `LD_LIBRARY_PATH` injected for bundled OpenSSL/libcrypto
- Streams `StdoutLine`/`StderrLine`/`Exited`/`Killed` events
- **Works when daemon is down** — critical for OTA updates and rootless coexistence

#### 3. Shell Wrapper (`SmartToolBox.shell` vs `shellDirect`)
- Daemon has **no shell** — it tokenizes and `execvp()`s argv directly
- `shell()` wraps as `/system/bin/sh -c <encodeArg(script)>` via `raw` handler
- `shellDirect()` runs same script via `ProcessManager` (no daemon)
- `encodeArg` backslash-escapes space/tab/`'`/`"`/`\` for daemon tokenizer (not POSIX)

---

## Navigation 3 + Session-Scoped ViewModels (Innovation)

### Problem Solved
Navigation 3's default `rememberViewModelStoreNavEntryDecorator` **clears ViewModel on pop**. For pentesting tools this destroyed:
- Scan results (ViewModel reset to default state)
- Running scans (`viewModelScope` cancelled mid-collection)

### Solution: `SessionViewModelStores`

```kotlin
// One ViewModelStore per destination FOR THE WHOLE APP SESSION
class SessionViewModelStores : ViewModel() {
    private val stores = LinkedHashMap<Any, ViewModelStore>()
    fun storeFor(key: Any): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }
    override fun onCleared() = clearAll()  // Clears on Activity destruction only
}

// contentKey = Pair(key.toString(), key::class.toString()) — value-equal across visits
@Composable
fun rememberSessionNavEntryDecorator(stores: SessionViewModelStores): NavEntryDecorator<NavKey> {
    NavEntryDecorator(
        onPop = {},  // NO CLEANUP — retention is the feature
        decorate = { entry ->
            val owner = remember(entry.contentKey) { SessionStoreOwner(stores.storeFor(entry.contentKey), activity) }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
        }
    )
}
```

**Result**: Port Scanner scan continues off-screen; returning shows live results; Stop via long-run notification kills it.

### Navigation Graph Structure

```kotlin
sealed class Screen : NavKey {  // 41 @Serializable data objects
    data object Home : Screen()
    data object Tools : Screen()
    data object Settings : Screen()
    data object PortScanner : Screen()
    // ... 36 plugin screens
}

navigate(route)     // push (no-op if already top)
goBack()            // pop (false when size<=1 → app exits via Home)
navigateToTab(route) // tab semantics: no-op if current, pop-to if in stack, else truncate→Home→push
```

---

## Core System Singletons (Deep Dive)

### 1. `SmartToolBox` — The Execution Engine

**Key methods by category:**

| Category | Methods |
|----------|---------|
| **Daemon tools** | `nmap()`, `nmapXml()`, `networkRadar()`, `tcpdump()`, `arpspoof()`, `ettercap()`, `shell()`, `command()`, `rawProbe()` |
| **Direct su tools** | `hydra()`, `hydraCredential()`, `traceroute()`, `tcpdumpDirect()`, `masscanDirect()`, `aircrackDirect()`, `arpspoofDirect()`, `ettercapDirect()`, `bettercapDirect()`, `reaverDirect()`, `hping3Direct()`, `nucleiDirect()`, `hydraDirect()` |
| **Chroot tools** | `nuclei()`, `crackHandshake()`, `nucleiUpdateTemplates()`, `gobuster()`, `aircrack()`, `monitorMode()`, `chroot()`, `ensureChroot()`, `capability()` |
| **Gates** | `requireTool(name)` → `ToolHandle.Ready/DaemonDown/Missing`<br>`requireDirectTool(name)` → `ToolHandle.Ready/RootRequired/Missing` |
| **Probe cache** | `TtlProbe` (1s TTL) on `SmartTool.isAvailable()` — prevents socket hammering |
| **Liveness** | `daemonConnected: StateFlow<Boolean>` — published on every transition (attach, login, peer loss) |

**ToolResult emission contract:**
- `status=running` → per-line emissions (`streamedLine` for UI append)
- `status=completed/timeout` → final, carries full transcript in `rawOutput`
- `status=failed` → tool never ran (missing handler/unavailable/start rejected)

### 2. `CoreManager` — Core Install & Daemon Lifecycle

| Responsibility | Implementation |
|----------------|---------------|
| **Core install** | `installCore()` — XZ+tar extraction (traversal-guarded), marks executables, writes `VERSION`, writes per-install `users` credential |
| **Daemon start** | `startDaemon()` — `su -c ./indrad` with `INDRA_ALLOWED_UID`, polls socket+login (20s), **never** `waitFor()` on launcher (hangs forever) |
| **Daemon stop** | `stopDaemon()` → `client.shutdown()` + `killDaemon()` (SIGTERM via `su`/`sh`) |
| **Reconnect** | `reconnect()` — attaches to existing daemon, called from `requireTool()` before probe |
| **Per-install secret** | 32 random bytes in prefs (`PREF_DAEMON_SECRET`), SHA-256 written to `<core>/users` (daemon reads once at startup) |
| **Artifact installs** | `installTarballArtifact()` shared pipeline (Bettercap, MSF chroot, ExploitDB); `installNuclei()`, `installGobuster()` direct downloads |

**Critical daemon lifecycle gotchas (all fixed):**
- `su -c ./indrad` stays alive as parent → `waitFor()`/`read-to-EOF` block forever → **poll socket + login instead**
- Daemon killed underneath leaves socket file → `isDaemonRunning` (lenient) vs `isDaemonAttached` (strict: connected+authenticated)
- One refused start latched `startFailed` with no reset → **removed entirely**; refused runs report via own `ToolResult`

### 3. `ProcessManager` — Direct `su -c` Execution

```kotlin
// Shell command built with PID marker FIRST (not at end with $! which is empty for foreground)
private const val PID_MARKER = "INDRA_PID="
sb.append("echo ").append(PID_MARKER).append("$$; ")  // marker at START
env.forEach { sb.append("export ").append(k).append("=").append(shellQuote(v)).append("; ") }
toolsDir?.let { sb.append("export PATH=").append(shellQuote(it)).append(":$\"PATH\"; ") }
workDir?.let { sb.append("cd ").append(shellQuote(it)).append("; ") }
sb.append(command)

// Kill tree: descendantKillScript(shellPid) → ps/awk walk deepest-first → TERM then KILL
internal fun descendantKillScript(shellPid: Int): String =
    "kill_desc() { for c in $(ps -A -o PID,PPID | awk -v p=\$1 '\$2==p{print \$1}'); " +
        "do kill_desc \$c; done; kill -9 \$1 2>/dev/null; }; kill_desc $shellPid"
```

**Why not `Process.destroyForcibly()`?** Only kills `su`; tool children survive (reparented to init) — verified on device with `hydra` still running 3s after Back.

### 4. `ChildProcessManager` — Event Pipeline

```kotlin
// Single static entry point from JNI/KotlinDaemonClient reader thread
@JvmStatic
fun onEvent(id: Int, event: Event?) {
    val mapped = when(event) {
        is Newline -> ProcessEvent.Output(id, event.line)
        is StderrNewline -> ProcessEvent.Error(id, event.line)
        is Port -> ProcessEvent.PortFound(id, port, protocol, service, version)
        is Os -> ProcessEvent.OsFound(id, accuracy, name, type)
        is Message -> ProcessEvent.Message(id, severity, message)
        is ChildEnd -> ProcessEvent.Ended(id, exitValue)
        is ChildDied -> ProcessEvent.Crashed(id)
        else -> null
    }
    _events.tryEmit(mapped)  // SharedFlow(replay=128, drop-oldest)
}
```

- `SmartToolBox.execute` subscribes **before** `startCommand` to not miss first events
- Tracks pids for `stopAllRunning()` (OTA binary swap)

### 5. `KotlinDaemonClient` — Pure-Kotlin Daemon Protocol

**Protocol**: 6-byte LE header + NUL-packed body over `LocalSocket`
- `connect(path)` → opens socket, starts reader thread, publishes `connected=true`
- `login(user, hash)` → `AUTH` handshake, `AUTH_OK`/`AUTH_FAIL` → `authenticated` + `daemonConnected`
- `loadHandlers()` → `HNDL_LIST` → caches handler definitions
- `startCommand(handler, args)` → `CMD_START` with sequenced wait (10s timeout)
- Reader loop: `dispatchControl()` (AUTH, CMD_STARTED, CMD_FAIL, HNDL_LIST, CMD_END, CMD_DIED, CMD_STDERR) + `dispatchChild()` (RAW→`Newline`, NMAP→`NmapOutputParser`)
- **Peer loss handling**: `onPeerLost()` clears `connected`/`authenticated`, publishes `daemonConnected=false`, emits `ChildDied(SIGKILL)` for all in-flight children

---

## Plugin System & Availability Gating

### `PluginManager` — 37 Registered Plugins

Each plugin has:
```kotlin
data class Plugin(
    val id: String,           // e.g., "port_scanner"
    val name: String,         // "Port Scanner"
    val description: String,
    val targetTypes: Set<String>,  // "endpoint", "network"
    val requires: String      // SmartToolBox TOOL_SPECS key
)
```

### `PluginAvailability` — Single Source of Truth for Cards

```kotlin
enum class PluginStatus(val label: String?, val openable: Boolean) {
    AVAILABLE(null, true)
    DAEMON_DOWN("daemon not running", true)      // screen can explain how to start
    TOOL_MISSING("not in native core", false)
    ARTIFACT_MISSING("component not installed", true)  // installer IN the screen
    NEEDS_ROOT("needs root", false)
    UNSUPPORTED("not supported on Android", false)  // Responder, Topology
}
```

**Gating logic (in order):**
1. `!isFeasibleOnAndroid(id)` → `UNSUPPORTED`
2. `id in daemonFreePlugins` (`wifi_keygen`) → `AVAILABLE`
3. `rootlessMode && Rootless.supports(id)` → `AVAILABLE`
4. `rootlessMode` → `NEEDS_ROOT`
5. `isDirectTool(requires)` → `directStatus()` (binary bundled? root? artifact?)
6. Else → `daemonStatus()` (daemon connected? handler ready? artifact installed?)

**Rootless plugins** (pure platform APIs, no daemon, no bundled binary):
- `port_scanner` — TCP connect scan (`PortScan`)
- `wifi_scanner` — `WifiManager` scan table
- `whisperpair` — Android BLE stack
- `password_auditor` — pure computation
- `wifi_keygen` — pure arithmetic on BSSID
- `gobuster` — HTTP dir sweep in app process (`DirBrute`/`DirBruteRunner`)
- `terminal` — `/system/bin/sh` as app uid (`LocalShell`)

---

## Chroot Engine (Alpine Linux Userspace)

### Why Alpine Chroot?
- Android lacks `iw`, `ip`, `hashcat`, `nuclei` templates, Go DNS resolver (`/etc/resolv.conf`)
- Bundled tools need real Linux userspace with proper libraries

### `ChrootEngine` Capabilities

```kotlin
enum class Capability {
    GNU_TOOLS,      // busybox, coreutils
    IW,             // iw + ip (monitor mode)
    HASHCAT,        // hashcat + pocl-opencl
    HCXTOOLS,       // hcxpcapngtool
    NUCLEI_TEMPLATES, // nuclei templates
    GOBUSTER,       // gobuster binary
    BETTERCAP,      // bettercap binary
}
```

### Chroot Setup (`ensureChroot()`)
1. Checks `Capability.GNU_TOOLS` installed
2. Reads DNS from `ConnectivityManager` → writes `resolv.conf` into chroot
3. Runs mount script: `/proc`, `/sys`, `/dev`, `/dev/pts`, `/sdcard` bind mounts
4. Caches DNS text; re-runs only when DNS servers change
5. Tracks failed mounts; fatal if required (`/proc`, `/sys`, `/dev`) missing

### Key Chroot Tools
- **nuclei**: Runs with `HOME=/root` (daemon's `HOME=/` is read-only)
- **hashcat**: `crackHandshake()` — exit 1 = "exhausted" (success), not failure
- **gobuster**: Path translation (host paths → chroot mount paths)
- **monitorMode**: `MonitorMode.script(iface, monitor)` — uses `;` not `&&` to always restore link

---

## OTA Update System (Binary Hot-Swap)

### Architecture
- **Manifest**: Signed envelope `{payload, signature}` (RSA-PSS SHA-256)
- **Channels**: `tools` (bundled binaries) + `msf` (Metasploit chroot)
- **Verifier**: `SignatureVerifier` (ECDSA P-256, DER) + pinned TLS host
- **Installer**: `BundleInstaller` → streamed SHA-256 → `BundleSwapper` (atomic live→.old→staged→live, rollback on failure)
- **Extractor**: `SafeTarExtractor` — rejects path traversal, writes files/dirs only (no symlinks)

### Update Flow
```
checkForUpdate() → fetchManifest() → local version probes (rawProbe <bin> --version)
installLatest() → download → verify SHA-256 → stopAllRunning() → atomic swap → health probe → rollback on fail
```

**Daemon swap**: `stopDaemon()` → swap binaries → `startDaemon()`
**Tool swap**: `stopAllRunning()` (SIGKILL sweep) → swap → daemon re-execs new binary

---

## Long-Running Tool Management

### `LongRunController` + `LongRunService` (Foreground Service)

```kotlin
// Trigger: ChildProcessManager.track() → onRunningChanged(count)
// Promotion: 2s debounce → startForegroundService() with "N tool(s) running" notification
// Stop action: notification "Stop" → stopAllRunning() → kills all tracked children
// Debounce: STOP_DEBOUNCE_MS = 1s (prevents ForegroundServiceDidNotStartInTimeException
//           when a short probe ends near a long scan start — measured 3ms flap on device)
```

**Kill switch**: The notification's Stop button is the ONLY way to stop a tracked child off-screen. In-process tools (DnsSpoofer, MITM, PacketCapture, NetworkRadar, WifiAudit, Handshakes) stop only from their own screen.

---

## Root Handling (Magisk v31+ Compatible)

### Key Changes from Legacy
- **No reactive prompt**: Magisk v31 removed `su` grant dialog — app probes once, shows snackbar with "Open Magisk" button
- **Detection = bounded `su -c` probe** (not package lookup):
  ```kotlin
  // Single script: magisk -v; grep ^Cap /proc/self/status; [ -d /data/adb/ksu ]; true
  // Classifies: GRANTED/DENIED/TIMEOUT/NO_BINARY/ERROR + manager + version + capabilities
  ```
- **Capabilities read, not assumed**: Magisk 30.1+ can strip `CAP_NET_RAW`/`CAP_NET_ADMIN` at uid 0
- **`<queries>` block in manifest** for 5 manager packages (visibility on Android 11+)

---

## UI/UX Architecture

### Components (Shared)
| Component | Purpose |
|-----------|---------|
| `OutputConsole` / `LiveOutputCard` | Streaming output: header `· running`, Copy, auto-follow, bounded height (`weight(1f)`) |
| `FormError` | Inline failures (red, single line) |
| `EmptyState` | Neutral "ran, found nothing" (not red) |
| `HostSuggestions` | `FlowRow` of ≤8 chips + `+N more` dropdown; tap-to-fill, long-press-copy; dims at 0.38α when disabled |
| `ModernToolCard` | Grid cards: `heightIn(min=132.dp)`, `TextOverflow.Ellipsis`, in-card "Update" button |

### State Patterns (All Screens)
```kotlin
data class UiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val finished: Boolean = false,      // distinguishes "not run" from "ran, 0 results"
    val results: List<X> = emptyList(),
    val liveLines: List<String> = emptyList(),
    val liveLineCount: Int = 0,         // monotonic for capped lists (auto-follow)
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)
```
- `finished` flag prevents "0 results" from rendering as error (Port Scanner, Traceroute, Inspector fixed)
- `liveLineCount` monotonic counter (not `liveLines.size`) for capped lists

---

## Testing Strategy

| Module | Tests | Approach |
|--------|-------|----------|
| **core** | 347 | Pure logic unit tests (no Android deps): `PortScan`, `Rootless`, `ShellQuote`, `TerminalKeys`, `ToolRegistry`, `SignedEnvelope`, `MsfSearch`, `Hashcat`, `ChrootScript`, `NeighborTable`, `LongRunNotice`, `WallpaperStore`, `MonitorMode`, `WifiScan`, `TtlProbe`, `ProcessManager` |
| **features** | 28 | ViewModel tests with `FakeToolRunner` (per-call script replay, `holdOpen` for stop testing), `Dispatchers.setMain(StandardTestDispatcher())` |
| **indra** | 13 | `Navigator`, permissions, settings |
| **ui** | 4 | `FlatSurface` |

**Total**: ~484 tests (968 executions debug+release)

**Detekt**: All 5 modules, baseline per module, not wired into `assembleDebug` (run manually or CI)

---

## Build System

```gradle
// Root
AGP 8.13.2, Gradle 8.14.6, Kotlin 2.2.21
compileSdk 36, minSdk 26, targetSdk 36
Compose BOM 2025.10.01 (Compose 1.9.4 / Material3 1.4.0)
Hilt 2.57.2, Navigation 3 1.1.7

// Native core (EOL tree, NDK 25.2 only)
rust/indrad/          // Cargo cross-compile (aarch64-linux-android, armv7-linux-androideabi)
indra/jni/build.sh    // cores task only (bundled C tools + Rust daemon packaging)
// No JNI bridge, no jniLibs/, no System.loadLibrary
```

---

## Critical Gotchas Summary (From AGENTS.md + Code Review)

| Area | Gotcha | Resolution |
|------|--------|------------|
| **Navigation** | Missing `entry<Screen.X>` in `IndraNavHost` → blank screen | Every new screen needs entry in `IndraNavHost.kt` |
| **ViewModel scope** | Per-entry stores cleared on pop | Use `SessionViewModelStores` (session-scoped) |
| **Command injection** | User fields in `shell()` → root shell injection | **All** interpolated fields through `shellQuote()` |
| **Shell vs command** | `shell()` = real `sh -c`; `command()`/`rawProbe()` = argv only | Use `command()` for `iptables` so `;` stays argument |
| **Scan timeouts** | Default 120s kills real scans | Pass `SCAN_TIMEOUT_MS` (15min) explicitly to `execute()` |
| **ToolResult** | Final emission duplicates transcript | Use `streamedLine` for append, `!isStreaming` for finalize |
| **Tool gates** | `getTool().isAvailable()` blocks UI on Main | Use `suspend requireTool()` on `Dispatchers.IO` |
| **Probe cache** | `isAvailable()` re-probed every call | `TtlProbe` 1s cache; `refresh()`/`reload()` force probe |
| **Daemon liveness** | `startFailed` latched forever | Removed; refused run = own `ToolResult` |
| **DNS in daemon** | Go tools can't resolve (no `/etc/resolv.conf`) | App-side resolution or "use IP" hint |
| **nuclei HOME** | Daemon's `HOME=/` read-only | `mkdir -p <filesDir>/home && HOME=... nuclei` via `shell()` |
| **masscan iface** | Auto-detect fails; cellular = LINUX_SLL unsupported | Screen shows honest error; needs `-e <active iface>` |
| **Plugin status** | `ARTIFACT_MISSING` was `openable=false` | Made `true` — installer lives in screen |
| **Zero results** | Rendered as red error | `finished` flag + `EmptyState` for real zero |
| **Console** | 28 hand-rolled `LazyColumn` → `LiveOutputCard` | Unified component with `appendCount` monotonic |
| **Host chips** | `Row` clipped at screen width | `HostSuggestions` wraps + dropdown |
| **Monitor mode** | `ip && iw && ip` leaves iface down if `iw` missing | `MonitorMode.script` uses `;` + always restores |
| **WiFi scan** | Used `iw` (not in core/ROM) | Now `cmd wifi` (platform API) |
| **nuclei exit** | Exit 1 = found something | `Nuclei.interpret`: exit 1 + findings = success |
| **msfconsole** | Fixed-width table; split on spaces merges columns | `MsfSearch` parses by header column offsets |
| **Registry** | Duplicates silently kept last | `ToolRegistryTest` enforces uniqueness + handler validity |
| **OTA pure logic** | Keep `parseOtaManifest`/`requirePinnedOtaHost` Android-free | Unit tested in `OtaModelsTest` |
| **Rootless** | Bundled binary exec denied (SELinux) | Only Kotlin implementations (PortScan, DirBrute, etc.) |
| **Magisk v31** | No reactive prompt | Probe once, snackbar → manager app Superuser tab |
| **JNI submodule** | `indra/jni` excluded from parent index | Commits pushed to fork, gitlink bumped separately |
| **Core assets** | `stageNativeCore` skips if asset exists | Manual copy `dist/*.tar.xz` → `assets/` after `build.sh cores` |

---

## File/Class Reference Index (Key Symbols)

### Core System
- `SmartToolBox` — Execution engine, tool registry, daemon/direct/chroot paths
- `SmartTool` — Per-tool availability probe (TtlProbe cached)
- `ToolHandle` / `ToolResult` / `ToolPort` / `ToolOs` — Execution contracts
- `ProcessManager` — Direct `su -c` with tree kill
- `ChildProcessManager` — Event pipeline (JNI + Kotlin client unified)
- `KotlinDaemonClient` — Pure-Kotlin daemon protocol over Unix socket
- `CoreManager` — Core install, daemon lifecycle, artifact installs, per-install secret
- `RootManager` / `RootAccess` — Root detection, Magisk v31+, capabilities
- `PluginManager` / `PluginAvailability` — 36 plugins, status gating
- `ChrootEngine` / `AlpineManifest` / `MonitorMode` / `Hashcat` / `MsfLaunch` — Chroot ops
- `OtaUpdateManager` / `BundleUpdateManager` / `SafeTarExtractor` — OTA system
- `LongRunController` / `LongRunService` — Foreground service for long tools
- `PortScan` / `WifiScan` / `NeighborTable` — Rootless/platform implementations
- `TerminalTransport` / `TerminalRunner` / `LocalShell` — Terminal abstraction

### Navigation
- `Screen` (sealed, 41 `@Serializable data object`)
- `NavigationState` / `Navigator` — Single back stack, unidirectional
- `SessionViewModelStores` / `rememberSessionNavEntryDecorator` — Session-scoped VMs
- `IndraNavHost` — `entryProvider` with 8 `EntryProviderScope` groups

### UI
- `OutputConsole` / `LiveOutputCard` / `FormError` / `EmptyState` / `HostSuggestions`
- `ModernToolCard` / `PluginScaffold` — Screen frame

### Features (Representative ViewModels)
- `PortScannerViewModel` — Dual path (daemon nmap / rootless connect scan)
- `NetworkRadarViewModel` — Kernel neighbor table fallback on crash
- `PacketCaptureViewModel` — Direct `tcpdump` with capture file save
- `ExploitFinderViewModel` / `MsfConsoleViewModel` — MSF chroot gating
- `VulnScannerViewModel` / `WebTesterViewModel` — Nuclei with findings parsing
- `GobusterViewModel` — Rootless `DirBrute` + chroot binary
- `LoginCrackerViewModel` / `CredentialTesterViewModel` — Hydra direct
- `WifiAuditViewModel` — Aircrack-ng direct + capture prefill

---

## Strategic Assessment

### Indra's Unique Strengths
1. **Hybrid execution model** (daemon + direct `su -c` + chroot) — no competitor has this
2. **Pure-Kotlin daemon client** — no JNI fragility, drop-in for native events
3. **Rust daemon** — memory safety in the privileged component
4. **Navigation 3 session-scoped VMs** — tools survive tab switches
5. **Structured event pipeline** — `PortFound`/`OsFound` from nmap handler
6. **Tool registry as data** — `TOOL_SPECS` single source, validated by test
7. **Reliable process tree kill** — `INDRA_PID` marker + descendant walk
8. **Rootless subset** — 7 plugins work without root/daemon
9. **OTA binary hot-swap** — signed, verified, atomic, rollback
10. **Honest UI states** — zero results ≠ error, daemon down ≠ missing tool
11. **Modular architecture** — clean module boundaries, Hilt DI
12. **Modern stack** — Compose, Navigation 3, Coroutines, Material 3

### Gaps vs. Competitors
| Competitor | Their Strength | Indra Gap |
|------------|----------------|-----------|
| **StrykerOSS** | Full Metasploit chroot, weekly builds | MSF chroot works but no weekly CI; nuclei templates missing |
| **zANTI** | Polished UI, cloud sync, MITM UX | Indra's MITM unsafe on test device; no cloud |
| **Strix** | Direct `su` simplicity | Strix has NO daemon, NO chroot — Indra strictly more capable |
| **WIFI-Frankenstein** | Monitor-mode focus | Indra has monitor mode but needs `iw`/`ip` (Alpine chroot) |

### Recommended Next Steps (Priority Order)
1. **Finish Metasploit workflow** — `msfconsole` search + module run + session handling
2. **Nuclei templates** — App-side download (OkHttp uses Android resolver) + chroot sync
3. **Monitor mode reliability** — Alpine `apk add iw iproute2` + driver capability probe
4. **Handshake → hashcat pipeline** — `hcxpcapngtool` conversion + `Hashcat.interpret` (exit 1 = exhausted)
5. **Weekly CI builds** — GitHub Actions: `build.sh cores` + APK + OTA manifest signing
6. **Bettercap integration** — Artifact install works; screen needs real args builder
7. **USB Arsenal** — HID gadget binding to UDC (currently "not implemented")

---

## Conclusion

Indra is **architecturally superior** to all four comparison targets:
- **vs Strix**: Indra has daemon + chroot + OTA; Strix is direct-only
- **vs StrykerOSS**: Indra has hybrid execution + pure-Kotlin daemon client + session VMs; StrykerOSS is chroot-only
- **vs WIFI-Frankenstein**: Indra covers full pentest spectrum; WF is wireless-only
- **vs zANTI**: Indra is open-source, rootless-capable, OTA-updatable; zANTI is closed, cloud-dependent

The codebase is **production-ready** for the daemon+direct core. The remaining work is **feature completion** (MSF, nuclei templates, monitor mode) and **CI/CD**, not architectural rework.