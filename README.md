# Indra — Android Network Penetration Testing Suite

Indra is a modern Android network analysis and penetration testing suite, built on
top of the legacy **cSploit** codebase but rebuilt from the ground up with a modern
stack (Kotlin, Jetpack Compose, Material 3, Hilt). It communicates with a native
`indrad` daemon (a descendant of cSploit's `cSploitd`) over Unix domain sockets
and requires a **rooted** device.

> The goal is not to revive cSploit as-is, but to build a more advanced, easier-to-use
> pentesting tool for Android than cSploit ever was.

---

## Features

**Working today (native core v1.0.1, both arm64-v8a and armeabi-v7a):**

- Network host discovery (Network Radar — auto-discovers hosts on the LAN)
- Port scanning (nmap, with live progress and per-port results; masscan for high-speed scans)
- OS / service fingerprinting (Inspector)
- Traceroute (`nmap --traceroute`)
- Packet capture (tcpdump, with live output + Stop + save to .cap)
- Man-in-the-middle (arpspoof) — ⚠️ see warning below
- Login / credential cracking (hydra with a bundled wordlist)
- Credential Tester (hydra against multiple services)
- Packet Forger (hping3)
- WiFi Audit (aircrack-ng, Wifite orchestration, Reaver WPS)
- Vulnerability Scanner (Nuclei — optional download)
- Web Tester (Nuclei sqli/xss)
- Exploit Finder (Metasploit — optional chroot download)
- RouterPwn
- One-Tap MITM, Session Hijacking (cookie theft), DNS Spoofing
- Password Auditor, WiFi Scanner, MAC Changer (with profiles), IPTables
- Terminal (built-in shell)
- HID Attacks (DuckyScript parser + key injection), USB Arsenal (gadget profiles)
- WhisperPair (BLE Fast Pair discovery)
- GeoMac (OSM map + KML/CSV export)
- Handshakes (capture file browser)
- Arsenal (custom template exploits)
- SearchSploit (ExploitDB browser)
- Network Topology (visual host map)
- Bettercap (optional download)
- Responder (LLMNR)
- Plugin marketplace UI (Tools screen), with availability badges

**Native core ships:** nmap, hydra, ettercap, tcpdump, arpspoof, fusemounts,
network-radar, hping3, aircrack-ng, masscan, reaver, plus the daemon handlers
(nmap, hydra, ettercap, tcpdump, arpspoof, fusemounts, network-radar, msfrpcd,
raw, blind). Optional downloads: Nuclei, Metasploit (chroot), Bettercap,
ExploitDB.

**App-level capabilities:**
- Secure OTA binary updater (HTTPS + TLS pinning, SHA-256 verify, atomic swap, rollback, per-tool updates)
- Native core install / start / stop from Settings
- Magisk / KernelSU / SuperSU root detection
- Crash reporter (local logs, no ACRA)

> ⚠️ **MITM / arpspoof is unsafe on the moto g60 test device**: starting it
> hard-resets the phone (3/3 runs, safe non-self target, no kernel/ramdump/pstore
> record → vendor WiFi driver). The app refuses to target its own IP and shows a
> risk dialog; do not re-run it on that device without being ready for a reboot.

---

## Requirements

- **Rooted** Android 8.0 (API 26) or newer
- Root manager: **Magisk** (recommended), KernelSU, or SuperSU
- Build: **JDK 21**, Android SDK 34, NDK 25.2 (for native builds)

---

## Building

### Android app

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release (needs KEYSTORE_* env vars)
./gradlew test                 # unit tests
./gradlew installDebug         # install to connected device
```

### Native daemon + tools (one-time, before first build)

```bash
git submodule update --init --recursive
cd indra/jni
export PATH=/home/rana/Android/Sdk/ndk/25.2.9519653:$PATH
export ANDROID_NDK_HOME=/home/rana/Android/Sdk/ndk/25.2.9519653
./build.sh jni                 # builds libIndraClient.so / libIndraCommon.so
./build.sh cores               # builds the full core package (indrad + tools)
```

Prebuilt `.so` files must exist under `indra/src/main/jniLibs/<abi>/`.

### Native core on device

The app installs the bundled core (`assets/core.tar.xz`) to `context.filesDir`.
Open **Settings → Native Core → Install**, then **Start**. The daemon runs as root
via Magisk `su`.

---

## Architecture

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (StateFlow), Hilt DI |
| Navigation | Navigation Compose (single-top, save/restore state) |
| Async | Kotlin Coroutines, Flow |
| Native | JNI bridge `IndraClient` ↔ `indrad` daemon over Unix sockets |
| OTA | HTTPS + TLS pinning, SHA-256, atomic swap, rollback |

### Modules

```
:core          # core/system, core/client, core/child, events
:data          # data/model, data/repository, domain/usecase
:ui            # ui/theme, ui/components
:features      # ui/screens/plugins (all plugin screens)
:indra (app)   # ui/screens (Home/Tools/Settings), navigation, AndroidManifest, resources, assets
```

### Key packages (`org/indra/android/`)

```
core/system/     # IndraSystem, SmartToolBox, PluginManager, CoreManager,
                 # RootManager, SettingsRepository, OtaUpdateManager, Logger, ...
core/client/     # IndraClient — JNI bridge to the native daemon
core/child/      # ChildProcessManager (process lifecycle + event pump)
data/model/      # Target, Network, Service, Exploit, Session
data/repository/ # NetworkRepository, PluginRepository
domain/usecase/  # NetworkUseCases, PluginUseCases
events/          # JNI event classes (Port, Hop, Newline, Message, ...)
ui/theme/        # Material 3 theme (dynamic colors)
ui/components/   # Reusable composables
ui/navigation/   # NavHost, BottomNavBar
ui/screens/      # Home, Tools, Settings
```

### Tools

`SmartToolBox` registers 30+ tools (nmap, masscan, hydra, aircrack, reaver,
hping3, nuclei, sqlmap, nikto, wifite, bettercap, responder, arpspoof, ettercap,
iptables, tcpdump, msf, msfrpcd, ruby, shell, raw, ip, logcat, fusemounts,
network-radar, traceroute, blind, …). Only a subset is currently bundled in the
native core; cards for missing tools are dimmed with "· not in native core".

### Plugins

18 plugins (`PluginManager`): Port Scanner, Inspector, Traceroute, MITM,
Network Radar, Packet Capture, Exploit Finder, Login Cracker, Packet Forger,
RouterPwn, WiFi Audit, Vuln Scanner, Web Tester, Credential Tester, and more.
Each declares a `requires` tool; unavailable tools disable the card.

---

## Secure OTA binary updater

`OtaUpdateManager` hot-swaps native binaries without bricking the core:

- Manifest fetched over **pinned HTTPS** (TLS `CertificatePinner`), envelope
  `{payload, signature}` verified with **SHA256withRSA** (pinned public key)
- SHA-256 verified before swap; atomic replace (`ATOMIC_MOVE` + POSIX
  `rwxr-xr-x`); pre-swap `<name>.bak` backup; post-update health check →
  rollback on failure
- `indrad` swap = `stopDaemon()` → swap → `startDaemon()`; tool swap =
  `ChildProcessManager.stopAllRunning()` → swap → daemon re-execs
- Aborts when core/daemon not running; sweeps leftover `.ota-tmp-*` scratch files
- UI: Settings "Binary Updates (OTA)" (Check / Update all) + per-card "Update"
  on the Tools screen (independent per-tool updates)
- `scripts/gen-ota-manifest.py` builds + signs the manifest envelope

Dev/LAN testing: override `SettingsRepository.otaManifestUrl` and enable
`otaAllowTestHosts` (debug builds expose a "OTA Test Host (dev only)" card in
Settings). Cleartext is permitted only for `127.0.0.1`/`localhost`
(`res/xml/network_security_config.xml`).

---

## Testing

```bash
./gradlew test                                          # all unit tests
./gradlew test --tests "org.indra.android.core.system.NetworkHelperTest"
```

Unit tests: `NetworkHelper`, `RootManager`, `ToolResult`, `PluginManager`,
`NetworkRadarParsing`. UI tests live in `indra/src/androidTest/`.

Most functionality requires a rooted device with the native daemon running.

---

## Critical gotchas

- **Root required** — execs `su`; Magisk prompt auto-fires on launch.
- **JDK 21** — JDK 17 lacks `jlink`, JDK 25 breaks Gradle 8.7.
- **JNI submodule** — `indra/jni` must be initialized before building.
- **Daemon dependency** — `libIndraClient.so` / `libIndraCommon.so` must be in
  `jniLibs/`; core must be installed on device (`/data/data/org.indra.android/files/`).
- **No ACRA** — crashes go to `CrashReporter` (local logs).
- **Event argument order** in `events/Event.kt` must match the native code's
  `cSploitClient/event.c` (`Port(protocol, port, service, version)`,
  `Os(accuracy, name, type)`, `Account(address, protocol, username, password)`,
  `Message(severity, message)`, `Attempts(sent, left, elapsed, eta)`,
  `Packet(source, destination, length)`) — JNI only checks the type signature,
  so a reordering compiles and silently swaps fields.
- **Android ICU** rejects some regexes the JVM accepts (`\{(.*)}\s*$` crashes at
  class init); network-radar parsing uses plain string ops with a unit test.
- **MITM/arpspoof** hard-resets the moto g60 test device — see warning above.

---

## License

GPL v3 or later. See [LICENSE](LICENSE).