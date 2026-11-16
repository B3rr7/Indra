# Indra — Android Network Penetration Testing Suite

Indra is an Android network analysis and penetration-testing suite written in Kotlin. It grew out
of the **cSploit** codebase — the bundled C tools are a port of it — but the app, the daemon and the
tooling layer are new: Jetpack Compose UI, a pure-Kotlin client, and a Rust daemon (`indrad`)
behind a Unix domain socket.

> ### ⚠️ Authorized testing only
>
> Indra is published **for educational purposes and for authorized security testing**. Use it only
> on devices and networks **you own**, or where you hold **explicit written permission** from the
> owner. Port scanning, ARP spoofing, traffic capture, credential cracking and every other
> capability here are criminal offences when aimed at systems you do not own — in most
> jurisdictions, not least under the US Computer Fraud and Abuse Act, the UK Computer Misuse Act
> and their equivalents elsewhere.
>
> The authors and contributors accept **no liability** for misuse, for damage caused by the tools,
> or for any legal consequence of running Indra. Most features are disruptive by nature: ARP
> spoofing and deauthentication can drop other people's connectivity, and packet capture can
> expose their traffic. Never run it against a third party, a public hotspot, an employer's network
> or an ISP without written authorization. **You are solely responsible for how you use it.**
>
> Do not use Indra for unauthorized access, interception, or surveillance.

---

## Status

Indra is an active rewrite, not a finished product. The core flows are device-verified on a rooted
moto g60 (Android 16, Magisk 31.1): network discovery, nmap/masscan scanning, OS fingerprinting,
traceroute, packet capture, hydra cracking, tcpdump→aircrack handshake cracking, nuclei scanning,
msfrpcd, the terminal, and the Settings/Core/OTA surfaces. Several features remain **gated on
optional artifacts** (nuclei templates, the Metasploit chroot, Bettercap, ExploitDB), and a few are
honestly marked as partial in [Known limitations](#known-limitations).

## What it is

- **38 plugins**, each gated on the tool it needs, so a card never offers work the device cannot do.
- **A native core** (`indrad` + bundled tools) installed from the app, not shipped as JNI.
- **Rootless mode**: 8 plugins have daemon-free Kotlin implementations and work without root.
- **Everything is a real tool run.** Results come from the tool's own output, parsed and rendered;
  where a feature is not implemented, the screen says so instead of faking a result.

## Features

**Reconnaissance**

| Plugin | What it does |
|---|---|
| Network Radar | Discovers hosts on the LAN (kernel neighbour table + active sweep) |
| Port Scanner | nmap SYN/version scan with live output and parsed ports |
| Masscan | High-speed port scan, with next-hop resolution for Android's links |
| Inspector | OS and service fingerprinting (`nmap -O -sV`) |
| Traceroute | `nmap --traceroute` |
| Link Probe | Classifies link interference: resets, throttling, DNS rewriting |
| WiFi Scanner | Nearby access points from Android's own scan table |

**Wireless**

| Plugin | What it does |
|---|---|
| WiFi Audit | aircrack-ng against a capture + wordlist, live cracking output |
| Handshakes | Browse captured `.cap` files and hand them to the cracker |
| Wifite | Monitor mode → capture → crack orchestration |
| Reaver (WPS) | WPS PIN brute-force |
| WiFi Keygen | Offline key candidates from a BSSID + the WPS PIN checksum |
| GeoMac | Map of captured BSSIDs with KML/CSV export |
| WhisperPair | BLE Fast Pair discovery (CVE-2025-36911) |

**Access & interception**

| Plugin | What it does |
|---|---|
| MITM / One-Tap MITM | ARP spoofing (and DNS redirect for the one-tap variant) |
| Packet Capture | tcpdump with live lines, Stop, and save to `.cap` |
| Session Hijacking | Extracts HTTP cookies from captured traffic |
| DNS Spoofing | Answers spoofed DNS queries with an `iptables` redirect |
| Packet Forger | hping3 — TCP/UDP/ICMP with payloads |
| Login Cracker | hydra against a service, with a bundled wordlist |
| Credential Tester | hydra across several services with one credential set |
| MAC Changer | Sets or randomizes the device MAC |
| IPTables | Reads and edits firewall rules (argv, never a shell) |

**Exploitation & web**

| Plugin | What it does |
|---|---|
| Vulnerability Scanner | nuclei with your templates and your resolver |
| Web Tester | nuclei sqli/xss templates |
| Gobuster | Directory/file brute-force (bundled binary under root, in-app sweep rootless) |
| Exploit Finder | msfrpcd module search |
| Metasploit | `msfconsole` in the bundled chroot |
| SearchSploit | ExploitDB browser |
| RouterPwn | Router default-credential and exposure checks |
| Arsenal | Your own scanner/exploit templates |
| Bettercap | Network attack framework (optional download) |

**Device & utilities**

| Plugin | What it does |
|---|---|
| Terminal | Multi-tab shell with carried cwd/exports, Alpine chroot, floating bubble |
| HID Attacks / DuckyScript | USB HID keyboard injection and DuckyScript execution |
| USB Arsenal | USB gadget profile manager (partial — see limitations) |
| Password Auditor | Password strength/entropy analysis |

## Requirements

- A **rooted** device, Android **8.0 (API 26)** or newer.
- Root manager: **Magisk** (tested through v31.1), KernelSU (incl. Next), or SuperSU.
  Magisk v31 removed the reactive `su` prompt, so grant access in the manager's **Superuser** tab —
  Indra cannot raise a dialog for you.
- The device must also grant **root the capabilities the tools need** (`CAP_NET_RAW`,
  `CAP_NET_ADMIN`). Magisk v30.1+ can restrict them at uid 0; Indra reads `CapEff` and warns.

Rootless mode (Settings) needs none of that, but only the 8 plugins listed
[below](#rootless-mode) are available in it.

## Build

**Toolchain:** JDK **21** (JDK 17 lacks `jlink`; JDK 25 needs a newer Gradle), Gradle 8.14.6,
AGP 8.13.2, Kotlin 2.2.21, `compileSdk 36`, `minSdk 26`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

./gradlew assembleDebug          # debug APK -> indra/build/outputs/apk/debug/indra-debug.apk
./gradlew assembleRelease        # release APK (needs KEYSTORE_FILE, KEYSTORE_PWD,
                                 #   KEYSTORE_ALIAS, KEYSTORE_ALIAS_PWD)
./gradlew test                   # unit tests
./gradlew detekt                 # static analysis (all modules, baselines enforced)
./gradlew installDebug           # install to a connected device
```

### Native core (needed for the bundled tools)

The core is a Rust daemon plus the ported C tools, packaged as
`indra/src/main/assets/core-<abi>.tar.xz`. There is **no JNI and no `jniLibs/`** — the client is
pure Kotlin and talks to `indrad` over `LocalSocket`.

```bash
git submodule update --init --recursive          # indra/jni — an empty submodule after clone

# 1. Rust daemon, both ABIs (the NDK linkers are resolved by name)
export ANDROID_NDK_HOME=/home/rana/Android/Sdk/ndk/25.2.9519653
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
cd rust/indrad
cargo test
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi

# 2. Bundled C tools + packaging (NDK 25.2 is the last one shipping ndk-build)
export PATH=/home/rana/Android/Sdk/ndk/25.2.9519653:$PATH
cd indra/jni && ./build.sh cores

# 3. Refresh the APK's core by hand — the Gradle task will not overwrite an existing asset
cp indra/dist/core-v*+android16.arm64-v8a.tar.xz  indra/src/main/assets/core-arm64-v8a.tar.xz
cp indra/dist/core-v*+android16.armeabi-v7a.tar.xz indra/src/main/assets/core-armeabi-v7a.tar.xz
```

The app itself needs **no NDK**: the NDK only compiles the C tools and links the daemon.

### Install on the device

1. Install the APK, open Indra, and grant root in the manager's Superuser tab.
2. **Settings → Native Core → Install** (unpacks the bundled core to app-private storage), then
   **Start**. The daemon runs as root via `su` and the socket is per-install authenticated.
3. Optional artifacts install from inside their own plugin screens: nuclei (Vulnerability Scanner),
   the Metasploit chroot (Metasploit / Exploit Finder), Bettercap, ExploitDB (SearchSploit), and
   the Alpine rootfs (Settings → Alpine, or the install card a capability gate offers).

> Reinstalling the app wipes its data: install the core again afterwards. `assembleDebug` alone does
> not refresh an existing `assets/core-*.tar.xz`.

## Architecture

Multi-module Gradle project — `:indra :core :data :ui :features` — with dependencies pointing one
way: `features → core, data, ui`, and `indra` (the app shell) on all of them.

| Module | Contents |
|---|---|
| `core/` | `system/` singletons (tool registry + runner, core install, daemon lifecycle, root, settings, logger, plugins, long-run service), `client/` (pure-Kotlin daemon client + wire protocol), `child/` (process registry), `engine/` (Alpine chroot, hashcat, msf launch, OTA trust), `data/` (bundle installer, msgpack), `terminal/` (tabs, transports, script builder) |
| `data/` | `NetworkRepository` and the `data.model` types only — no fake use-case layer |
| `ui/` | Theme + palettes, shared components (console, host suggestions, brand mark) |
| `features/` | All 37 plugin screens and their ViewModels |
| `indra/` | App shell: `MainActivity`, navigation, Home/Tools/Terminal/Settings, resources, assets |

Stack: Jetpack Compose + Material 3, Hilt, Coroutines/Flow, **Navigation 3** (typed `NavKey`
destinations on a single back stack), `minSdk 26` / `targetSdk 36`.

**How a tool runs.** A screen asks the registry for a tool (`requireTool`, suspend, gated on daemon
liveness), builds an argv, and `SmartToolBox` ships it to `indrad`. The daemon `execvp()`s it (no
shell), streams stdout/stderr line by line, and reports the exit status. `shell()` is the only path
that goes through `/system/bin/sh -c`, and every user-typed field interpolated there is quoted for
it — those strings run as root.

**Daemon.** `indrad` is per-connection: a child belongs to the connection that started it and is
killed when that socket closes, so a force-stopped app cannot leave root tools behind. The socket is
`0600` and owned by the app uid, the credential is generated per install and stored only as a
SHA-256 hash, and peers are checked with `SO_PEERCRED`.

## Tool registry

`SmartToolBox.TOOL_SPECS` is one table with **18 entries** — `nmap`, `masscan`, `hydra`,
`aircrack-ng`, `reaver`, `hping3`, `nuclei`, `gobuster`, `bettercap`, `arpspoof`, `tcpdump`,
`ettercap`, `fusemounts`, `network-radar`, `msfrpcd`, `shell`, `raw`, `blind` — each naming the
daemon handler that serves it. Bundled binaries run through `raw`/`direct`; `nmap` is the only
handler that reports structured events (ports, OS matches). `ToolRegistryTest` enforces uniqueness,
handler validity, and that every plugin's `requires` key resolves.

## Rootless mode

Android 10+ denies `execve()` on app-home storage, so a rootless `indrad` cannot work — and neither
can any bundled binary (`proot` included). Rootless is therefore a **subset implemented in Kotlin**,
not a degraded daemon:

`port_scanner`, `wifi_scanner`, `whisperpair`, `password_auditor`, `wifi_keygen`, `gobuster`
(in-app HTTP sweep), `terminal` (`/system/bin/sh` as the app uid), `link_probe`.

Everything else reports `NEEDS_ROOT`, truthfully: its binary can only be spawned by the root daemon.

## Security model

- **Daemon credential** — 32 random bytes per install; only `sha256(secret)` is written to
  `<core>/users` (0600), and the daemon loads it once at startup.
- **Socket** — `chmod 0600`, `chown` to the app uid, `SO_PEERCRED` check on every accept.
- **Command construction** — user fields go through `shellQuote`; free-form flag passthroughs are
  documented as plain concatenation. `command()`/`rawProbe` never use a shell.
- **OTA** — HTTPS with a certificate pin, an RSA-signed envelope, SHA-256 on the payload, atomic
  swap with a `.bak` rollback and a post-install health check.
- **Network** — cleartext is permitted only for `127.0.0.1`/`localhost` (dev OTA testing).
- **App** — `allowBackup=false`, only the launcher activity is exported, the FileProvider is scoped.

## Testing

```bash
./gradlew test                                          # all unit tests
./gradlew test --tests "*Netw*"                          # one class/pattern
./gradlew detekt                                         # static analysis
```

**853** unit tests (1706 executions across debug and release), mostly pure logic in `:core`:
`PortScan`, `NmapOutputParser`, `Nuclei`, `MsfSearch`, `WifiScan`, `Hashcat`, `MonitorMode`,
`NeighborTable`, `HostSuggestionLayout`, `DirBrute`, `GoResolvers`, `CaptureRoute`, `RootAccess`,
`ShellQuote`, `ToolRegistry`, `SignedEnvelope`, `MsgPack`, `SafeTarExtractor`,
`TerminalScript`/`TerminalExit`/`TerminalTabRules`, `WifiKeygen`, … Instrumentation tests live in
`indra/src/androidTest/` and need a device; CI runs the unit suite and detekt.

## Known limitations

- **USB Arsenal is partial.** It binds one HID function (`HidUdc`) and creates gadget function
  directories; composing a full gadget and binding it to a UDC is not implemented. The screen says
  so instead of implying a working composite gadget.
- **WiFi Keygen** produces 9 generic candidates plus the WPS PIN checksum — no vendor algorithms.
- **Monitor mode is driver-dependent** and needs `iw` + `iproute2` in the Alpine chroot. The test
  device's Qualcomm qcacld driver refuses `set type monitor` (`EOPNOTSUPP`) even though `iw list`
  advertises the mode; Wifite and Packet Capture fail with that message rather than hanging.
- **MITM/arpspoof hard-reset the moto g60 test device** (3/3, vendor WiFi driver). The app refuses
  to target its own IP and warns, but treat ARP spoofing as disruptive.
- **Metasploit** is a ~183 MB compressed / ~475 MB unpacked musl chroot, and the exploit database,
  nuclei templates and Bettercap are separate downloads — none ship in the APK.
- **Core download is manual**: the app installs the core bundled in its assets; it does not fetch it.

## Contributing

Commits are expected to keep `./gradlew test detekt assembleDebug` green. Two rules matter more
than style: **a new plugin must not claim a capability it does not have** (a gate is a fix card, not
a red sentence), and **anything a user types that reaches a root shell is quoted** — see
`SmartToolBox.shellQuote` and `ShellQuoteTest`.

## License

GPL v3 or later — see [LICENSE](LICENSE). The bundled tools keep their own upstream licenses.

## Legal notice

Indra is a dual-use security tool provided **as-is, for education and authorized testing**. Running
it against systems you do not own or have written permission to test is illegal in most countries.
The maintainers do not condone, enable or take responsibility for unauthorized use, and are not
liable for any damage, data loss or legal consequence arising from it. Check your local law and get
permission in writing before you point this at anything.
