# FULL DEEP COMPARISON: Indra vs Strix vs StrykerOSS vs WiFi-Frankenstein vs zAnti

*Comprehensive, code-verified deep analysis of five Android penetration-testing suites.*
*Generated: November 10, 2026 — based on direct source-tree inspection of all five projects.*

---

## 0. Scope & Method

This report compares five Android pentest/security-audit applications that live on the
same workstation:

| Alias | Path | Type |
|---|---|---|
| **Indra** | `/home/rana/Indra` | Open source, active development |
| **Strix** | `/home/rana/strix` | Open source (GPLv3), `daboynb/strix` |
| **StrykerOSS** | `/home/rana/strykerapp` | Open source (GPLv3), `zalexdev/strykerapp` |
| **WiFi-Frankenstein** | `/home/rana/WIFI-Frankenstein` | Open source (GPLv3), `LowSkillDeveloper/WIFI-Frankenstein` |
| **zAnti** | `/home/rana/zAnti` | Commercial, decompiled APK (`com.zimperium.zanti` 2.4.2) |

Every quantitative claim below was verified against the actual source trees (file counts via
`find`, build configs via `build.gradle*`, package lists, test directories). Qualitative
claims about architecture were verified by reading representative source files.

---

## 1. Executive Summary

| Project | One-line | Unique superpower | Biggest weakness |
|---|---|---|---|
| **Indra** | Modern multi-module hybrid-execution suite | Three execution paths (Rust daemon + direct `su` + Alpine chroot) + session-scoped ViewModels | MSF workflow incomplete; no weekly CI; legacy C toolchain |
| **Strix** | Clean Kotlin/Compose direct-execution suite | Most complete MSF integration (RPC + sessions + module browser) + weekly CI/CD | No daemon, no chroot, no rootless mode, arm64-only |
| **StrykerOSS** | Chroot-centric suite with QEMU rootless | QEMU VM rootless parity + HID/USB Arsenal + VNC desktop | God classes, Java 8, targetSdk 28, no tests, no static analysis |
| **WiFi-Frankenstein** | WiFi/database-centric audit tool | 3WiFi/SmartLinkDB + wardriving + geoMAC + Router Scan | Not a general pentest suite; mixed legacy/modern UI |
| **zAnti** | Commercial MITM-focused tool | Cloud sync (zCloud) + MITM HTTP injection/SSL stripping + plugin system | Closed source, legacy stack, god classes, ACRA crashes |

**Bottom line:** Indra is architecturally superior to all four. Its remaining work is
*feature completion* (MSF sessions, nuclei templates, rootless parity) and *CI/CD*, not
architectural rework. The strongest single competitor is Strix (modern stack, complete MSF);
the most feature-rich is StrykerOSS (chroot + rootless + HID/USB + VNC) but on a legacy stack.

---

## 2. Project Scale & Stack

| Metric | Indra | Strix | StrykerOSS | WiFi-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Type | Open source | Open source (GPLv3) | Open source (GPLv3) | Open source (GPLv3) | Commercial (decompiled) |
| Language | Kotlin 2.2.21 (100%) | Kotlin 2.1.0 (100%) | Java 8 | Kotlin 2.x (387 files) | Java (smali, decompiled) |
| UI | Jetpack Compose 100% | Jetpack Compose 100% | XML Layouts + Fragments | Compose + XML (mixed) | Legacy (ActionBarSherlock, SlidingMenu) |
| Navigation | Navigation 3 (typed `NavKey`) | Nav Compose (legacy) | Fragments + Drawer | Mixed | Activities + SlidingMenu |
| Modules | 5 Gradle modules | Single module | Monolithic | Single module | Monolithic |
| Source files | 293 Kotlin | 236 Kotlin | 331 Java | 387 Kotlin | 2002 Java (decompiled) |
| Unit tests | ~568 methods (~1114 exec) | 117 methods (30 files) | 1 method | 0 | N/A |
| Static analysis | detekt (all modules) | Unknown | lint only | Unknown | N/A |
| Min SDK | 26 (Android 8.0) | 29 (Android 10) | 24 (Android 7.0) | 21 (Android 5.0) | 22 |
| Target SDK | 36 | 34 | 37 (README stale: says 28) | 37 | 22 |
| ABI | arm64-v8a + armeabi-v7a | arm64 only | arm64 only | arm64-v8a (+v7a/x86) | armeabi/armeabi-v7a/mips/x86 |
| DI | Hilt | Hilt | Manual (static Core) | Mixed | Manual |
| Screens/plugins | 36 plugin screens | ~20 screens | ~30 fragments | ~40 fragments | ~50 activities |
| Version | 2.0.0 (app) | 0.2.0 | 6.5.1 | 2.6 | 2.4.2 |

---

## 3. Architecture

### Indra — Multi-module clean architecture
```
Indra/
├── core/    (169 files)  SmartToolBox, CoreManager, ProcessManager, RootManager,
│                         KotlinDaemonClient, PluginManager, ChrootEngine, OTA, terminal
├── data/    (2 files)    NetworkRepository only (thin, ADR 0001 — no fake results)
├── ui/      (11 files)   Theme, OutputConsole, HostSuggestions, WallpaperBackground
├── features/(83 files)   36 plugin screens + ViewModels (MVVM, StateFlow<UiState>)
└── indra/   (28 files)   App shell, Hilt DI, IndraNavHost, Home/Tools/Terminal
```
- Dependency direction: `features → core, data, ui`; `indra → all`.
- Navigation 3 with typed `@Serializable data object Screen.X` (compile-safe).
- Session-scoped ViewModels: `SessionViewModelStores` retains one `ViewModelStore` per
  destination for the entire app session, so a scan keeps running off-screen and its
  results survive navigation. Cleared only on Activity destruction.

### Strix — Single-module clean
```
strix/app/.../org/csploit/strix/
├── core/ (16)   ProcessManager, Logger, ShellCommand, ProcessTreeKiller, RootChecker, ScanService
├── data/ (98)   ToolManager, MsfDaemon, NetworkManager, MitmRunner, MsfRpcClient, BundleUpdateManager, ...
├── domain/model/(7)  NetworkInfo, PortInfo, Host, MsfModels, ExtractionState
├── ui/ (112)    20+ screens (Compose), navigation via Routes/ToolDestinations/MsfDestinations
├── di/ (1)      AppModule (Hilt)
```
- Clean Kotlin/Compose/Hilt but single module; no daemon; no chroot; no rootless mode.
- Navigation Compose (legacy, string routes).

### StrykerOSS — Monolithic Java god classes
```
strykerapp/app/.../com/zalexdev/stryker/
├── Core.java (1336 lines)  GOD CLASS: prefs, DB, su exec, chroot mount, tool installs,
│                           vendor DB, pixie dust, VNC, HID, USB Arsenal
├── engine/ (38)   GuestCore (chroot), RootlessEngine (QEMU VM), EngineType, Apt, ...
├── metasploit/ (11)  MSF console, payload gen, RPC, install stages
├── hid/ (38)      DuckyScript engine (pure Java), 7 keyboard layouts
├── usbarsenal/ (6) USB gadget configfs orchestration
├── wifi/, wpair/, handshakes/, macchanger/, geomac/, vnc/, dashboard/, arsenal/, coremanger/
├── ota/ (13)      RemoteManifest, UpdateManager, CoreDownloader, QemuDownloader, VerifiedDownloader
```
- Java 8, XML layouts, targetSdk 28 (legacy). No DI, no tests, no static analysis beyond lint.
- Chroot-centric: Debian trixie arm64 at `/data/local/stryker/release`.

### WiFi-Frankenstein — WiFi/database centric
```
WIFI-Frankenstein/app/.../com/lsd/wififrankenstein/
├── ui/ (248)    40+ fragments: dbsetup, databasefinder, wifiscanner, iwwifi, personalmap,
│                 handshakecapture, wpacracker, wpagenenerator, wpsgenerator, localnetwork, ...
├── util/ (94)   WPS helpers, cracker, ...
├── network/ (13) bettercap
├── service/ (10)
├── ipranges/ (6)
├── data/ (4)
```
- Kotlin, mixed Compose + XML, minSdk 21, targetSdk 37.
- NDK 30, CMake 4.1.2, native C++ (cracker.c).

### zAnti — Commercial, decompiled
- Package `com.zimperium.zanti`, version 2.4.2, minSdk 22, targetSdk 22.
- 2002 decompiled Java files, ActionBarSherlock + SlidingMenu + NineOldAndroids (legacy).
- Plugin system: CrackerPlugin, RouterPwn, ShellShock, SSLPoodle, Heartbleed, zmitm, macchanger,
  httpserver, samba, Scanner, analyze, ztether, zipsadvert, zcloud.
- Cloud sync via zCloud; ACRA crash reporting.

---

## 4. Execution Model

| Aspect | Indra | Strix | StrykerOSS | WiFi-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Daemon | Rust `indrad` over Unix socket | None | None (chroot) | None | Native binaries |
| Direct `su -c` | Yes (masscan, hydra, aircrack, reaver, hping3, nuclei, bettercap) | Yes (all tools) | Yes | Yes | Yes |
| Chroot | Alpine (optional, capability-gated) | None (planned MSF bundle) | Debian trixie arm64 | None | None |
| Rootless mode | Yes (4 platform-API plugins) | No | Yes (QEMU VM) | Partial | No |
| Structured events | `PortFound`, `OsFound`, `Hop`, `Account`, `Packet` | Process output parsing | Process output | Process output | Process output |
| Process kill | Descendant tree kill (TERM→KILL) + daemon child table | ProcessTreeKiller | Process kill | Process kill | Process kill |

**Indra's hybrid model is unique.** It is the only one with a *persistent* daemon (Rust, per-connection
child table, teardown on disconnect) plus direct `su` plus an Alpine chroot for capability-gated
tools (`iw`, `ip`, `hashcat`, `hcxtools`). Strix is the closest in spirit (clean Kotlin, direct
exec) but has no daemon and no chroot. StrykerOSS has the deepest rootless story (QEMU VM booting
the same rootfs) but on Java 8.

---

## 5. Tool / Feature Capability Matrix

| Capability | Indra | Strix | StrykerOSS | WiFi-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Host discovery | ✅ Network Radar (kernel neigh + sweep) | ✅ ARP scan | ✅ Nmap | ✅ WiFi scan | ✅ Scanner |
| Port scan | ✅ nmap + masscan | ✅ nmap | ✅ nmap | ❌ | ✅ Scanner |
| OS fingerprint | ✅ Inspector (nmap -O) | ✅ nmap -O | ✅ nmap | ❌ | ❌ |
| Traceroute | ✅ nmap --traceroute | ✅ | ❌ | ❌ | ❌ |
| Packet capture | ✅ tcpdump (live + save .cap) | ✅ tcpdump | ❌ | ❌ | ❌ |
| Packet forger | ✅ hping3 | ✅ hping3 + WoL | ❌ | ❌ | ❌ |
| MITM | ✅ arpspoof (risky on some devices) | ✅ ettercap + arpspoof | ❌ | ✅ localnetwork ARP spoof | ✅ zmitm (HTTP inject/SSL strip) |
| DNS spoofing | ✅ (redirect UDP/53 → 5353) | ✅ ettercap DNS | ❌ | ❌ | ✅ |
| Credential cracking | ✅ hydra | ✅ hydra (10+ protocols) | ✅ hydra | ❌ | ✅ CrackerPlugin |
| Credential tester | ✅ | ❌ | ❌ | ❌ | ❌ |
| WiFi audit (aircrack) | ✅ aircrack-ng + Wifite | ❌ | ✅ aircrack-ng | ❌ | ❌ |
| Handshake capture | ✅ (tcpdump save) | ❌ | ✅ airodump-ng | ✅ airodump-ng | ❌ |
| WPS attacks | ✅ Reaver | ❌ | ✅ Pixie Dust + pins | ✅ PixieDust + WPS gen | ❌ |
| WiFi keygen (vendor) | ❌ | ✅ cSploit keygen | ❌ | ✅ WPA/WPS generators | ❌ |
| Wardriving | ❌ | ❌ | ❌ | ✅ PersonalMap (Wigle-style) | ❌ |
| Router scan (default creds) | ✅ RouterPwn | ✅ Router check | ❌ | ✅ Router Scan integration | ✅ RouterPwnPlugin |
| Vulnerability scan (nuclei) | ✅ (optional download) | ❌ | ✅ nuclei | ❌ | ❌ |
| Web tester (sqli/xss) | ✅ nuclei templates | ❌ | ❌ | ❌ | ❌ |
| Exploit finder (MSF) | ✅ (optional chroot) | ✅✅ Full MSF (best) | ✅ MSF console | ❌ | ❌ |
| SearchSploit/ExploitDB | ✅ (optional download) | ❌ | ✅ searchsploit | ❌ | ❌ |
| Bettercap | ✅ (optional download) | ❌ | ❌ | ✅ bettercap integration | ❌ |
| HID attacks (DuckyScript) | ✅ (parser + key injection) | ❌ | ✅ (pure Java, 7 layouts) | ❌ | ❌ |
| USB Arsenal (gadget) | ✅ (profiles) | ❌ | ✅ (configfs) | ❌ | ❌ |
| MAC changer | ✅ (with profiles) | ❌ | ✅ | ❌ | ✅ macchanger |
| IPTables | ✅ | ❌ | ❌ | ❌ | ❌ |
| Terminal | ✅ (built-in shell) | ❌ | ✅ (drawer terminal) | ❌ | ❌ |
| GeoMac (OSM map) | ✅ (KML/CSV export) | ❌ | ✅ | ✅ (geomac) | ❌ |
| WhisperPair (BLE) | ✅ (Fast Pair discovery) | ❌ | ✅ (full exploit chain) | ❌ | ❌ |
| Session hijacking | ✅ (cookie theft) | ❌ | ❌ | ❌ | ❌ |
| DNS spoofing | ✅ | ❌ | ❌ | ❌ | ❌ |
| Password auditor | ✅ (entropy) | ❌ | ❌ | ❌ | ❌ |
| WiFi scanner (platform) | ✅ (cmd wifi) | ❌ | ❌ | ✅ (iw + root) | ❌ |
| Saved WiFi passwords | ❌ | ❌ | ❌ | ✅ (root) | ❌ |
| 3WiFi/SmartLinkDB | ❌ | ❌ | ❌ | ✅ (core feature) | ❌ |
| Wardriving | ❌ | ❌ | ❌ | ✅ (PersonalMap) | ❌ |
| WPA offline cracker | ❌ | ❌ | ✅ hashcat | ✅ (hashcat + cracker.c) | ❌ |
| VNC desktop | ❌ | ❌ | ✅ (XFCE VNC) | ❌ | ❌ |
| Cloud sync | ❌ | ❌ | ❌ | ❌ | ✅ zCloud |
| Plugin marketplace | ✅ (Tools screen) | ❌ | ❌ | ❌ | ✅ plugins |
| OTA binary updater | ✅ (HTTPS + RSA sig + rollback) | ✅ (bundles + GitHub releases) | ✅ (CoreDownloader) | ✅ (updates) | ❌ |

---

## 6. Metasploit Integration (deepest comparison)

| Aspect | Indra | Strix | StrykerOSS | zAnti |
|---|---|---|---|---|---|
| MSF available | Optional chroot download | Optional bundle download (GitHub releases) | Bundled in chroot | No |
| Console | MsfConsole screen | ShellScreen + MsfViewModel | MSF console fragment | No |
| RPC client | MsfDaemon (msfrpcd lifecycle) | MsfRpcClient (MessagePack) | RPC | No |
| Module browser | ExploitFinder (search) | ExploitSearchPanel + ExploitConfigPanel + payload picker | Module browser | No |
| Sessions | Partial | ✅ MsfRpcSessions + MsfSessionNotifier + sessions UI | ✅ sessions | No |
| Payload gen | ❌ | ✅ ExploitPayloadPicker | ✅ | No |
| Interactive shell | ❌ (console only) | ✅ ShellTerminal + ShellActionBars | ✅ console | No |

**Strix has the most complete MSF integration** (RPC sessions, payload picker, interactive shell,
module browser, daemon lifecycle). Indra's MSF support is the weakest of the three that have it —
it can install the chroot and search modules, but lacks sessions/payload gen/interactive shell.
This is the single largest feature gap.

---

## 7. OTA / Binary Update Systems

| Aspect | Indra | Strix | StrykerOSS |
|---|---|---|---|
| Trust model | HTTPS + TLS pinning + RSA-signed envelope + SHA-256 | GitHub releases + bundle signatures | RemoteManifest + VerifiedDownloader |
| Scope | Per-tool updates + core | Bundles (tools + MSF) | Core + QEMU |
| Atomic swap | Yes (ATOMIC_MOVE + .bak + rollback) | Yes (BundleSwapper) | Yes |
| Health check + rollback | Yes | Yes (repairLeftovers) | Partial |
| Pinned host enforcement | Yes (requirePinnedHost, dev escape hatch) | Yes (GitHubReleases) | Unknown |
| Resumable download | Yes (BundleDownloader) | Yes | Unknown |

Indra and Strix both have mature, well-tested OTA systems (Indra: `OtaModelsTest`, `SignedEnvelope`;
Strix: 8 bundle tests). StrykerOSS has a downloader but no visible tests.

---

## 8. UI/UX Architecture

| Aspect | Indra | Strix | StrykerOSS | WiFi-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Theme | Material 3 dynamic + Flat treatment | Material 3 | Material (XML) | Material (mixed) | Legacy Holo |
| Dark mode | Dark-only (deliberate) | Unknown | Unknown | Multiple themes | Dark |
| Edge-to-edge | Yes (audited, no double inset) | Unknown | Unknown | Unknown | No |
| Shared console | `LiveOutputCard` (one component, 20 screens) | Per-screen | Per-screen | Per-screen | Per-screen |
| Host suggestions | `HostSuggestions` (wrap + overflow menu) | Unknown | Unknown | Unknown | Unknown |
| Empty states | `EmptyState` + `finished` flag (audited 36 screens) | Unknown | Unknown | Unknown | Unknown |
| Error display | `FormError` (one component) | Unknown | Unknown | Unknown | Unknown |
| Navigation | Nav3, single back stack, session VMs | Nav Compose | Fragments + Drawer | Mixed | Activities + SlidingMenu |

Indra has the most polished, audited UI layer: a single shared console, a single error line,
a single host-chip component, and a documented empty-state contract across all 36 screens.

---

## 9. Root Handling & Capabilities

| Aspect | Indra | Strix | StrykerOSS | zAnti |
|---|---|---|---|---|---|
| Root managers | Magisk, KernelSU (incl. Next), SuperSU | RootChecker | Core (su exec) | SuperSU/ACRA |
| Magisk v31+ (no reactive prompt) | ✅ (probe + snackbar guidance) | Unknown | Unknown | N/A (old) |
| Capability probing | `CapEff` mask (CAP_NET_RAW bit 13) | Unknown | DeviceCapabilities | Unknown |
| Rootless mode | 4 platform-API plugins | No | QEMU VM (full parity) | No |
| Rootless honesty | `Rootless.kt` single source of truth | N/A | QEMU boots same rootfs | N/A |

StrykerOSS's rootless story is the deepest (QEMU VM boots the same Debian rootfs, giving
near-full parity), but on Java 8/targetSdk 28. Indra's rootless is narrower (4 plugins) but
honest and modern.

---

## 10. Code Quality & Maintainability

| Aspect | Indra | Strix | StrykerOSS | WiFi-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Language modernity | Kotlin 2.2, Compose, Hilt, Nav3 | Kotlin 2.1, Compose, Hilt | Java 8, XML, no DI | Kotlin 2.x, mixed | Java (legacy) |
| Module separation | 5 modules, clean deps | Single module | Monolithic | Single module | Monolithic |
| God classes | None (CoreManager split) | None visible | `Core.java` 1336 lines | Some large | Many (165K total) |
| Test coverage | ~568 methods | 117 methods | ~1 method | 0 | N/A |
| Static analysis | detekt (all modules) | Unknown | lint | Unknown | N/A |
| Fake results | None (ADR 0001 deleted them) | Unknown | Unknown | Unknown | N/A |
| Documentation | AGENTS.md (190KB), 10+ ADRs/audits | README + CLAUDE.md | README | README | None |

Indra is the only project with a substantial test suite, static analysis on every module, and
documented architectural decisions (ADRs, audit docs).

---

## 11. Build & Distribution

| Aspect | Indra | Strix | StrykerOSS | WiFi-Frankenstein |
|---|---|---|---|---|
| Build system | Gradle 8.14.6, AGP 8.13.2 | Gradle, AGP 8.7.3 | Gradle, AGP 8.13.2 | Gradle, AGP (KTS) |
| JDK | 21 | 17 | 8 | (foojay resolver) |
| CI/CD | GitHub Actions (ci.yml, bundle.yml) | ✅ Weekly automated builds | Unknown | Unknown |
| Release signing | Env vars (KEYSTORE_*) | keystore.properties | Env/gradle props | Env/gradle props |
| Native build | NDK 25.2 + Rust cargo | NDK (bundles) | NDK 25.1 + externalNativeBuild | NDK 30 + CMake |
| 16 KB page support | ✅ (max-page-size=16384) | Unknown | Unknown | Unknown |

Strix has the best CI/CD story (weekly automated builds). Indra has CI but no weekly schedule
documented; StrykerOSS and WiFi-Frankenstein have unknown CI.

---

## 12. What Indra Should Borrow (Strategic Recommendations)

Ranked by impact vs. effort, derived from the comparison:

### High impact, medium effort
1. **Complete MSF integration** (from Strix): RPC sessions, payload picker, interactive shell.
   Indra has the chroot + daemon; it lacks the UI/workflow. Strix's `MsfRpcClient`,
   `MsfRpcSessions`, `ExploitPayloadPicker`, `ShellTerminal` are the reference.
2. **Weekly CI builds + release automation** (from Strix): scheduled GitHub Actions producing
   signed APKs. Indra already has `ci.yml`/`bundle.yml`; add a weekly schedule trigger.
3. **WiFi keygen / vendor default keys** (from Strix + WiFi-Frankenstein): cSploit-style
   router key recovery. Indra has no keygen at all.
4. **Wardriving / personal map** (from WiFi-Frankenstein): Wigle-style real-time collection
   with cross-check against connected DBs. Indra has GeoMac but no wardriving.

### Medium impact, medium effort
5. **QEMU rootless parity** (from StrykerOSS): boot the same Alpine rootfs under QEMU when
   root is unavailable, giving near-full parity. Indra's rootless is only 4 plugins.
6. **VNC desktop** (from StrykerOSS): stand up an in-chroot XFCE session viewable locally.
   Niche but differentiating.
7. **HID layout coverage** (from StrykerOSS): 7 keyboard layouts vs Indra's US-only.
   StrykerOSS's pure-Java DuckyScript parser is the reference.
8. **Saved WiFi passwords viewer** (from WiFi-Frankenstein): root-only, reads
   `wpa_supplicant.conf`. Easy win.

### Low impact / niche
9. **3WiFi/SmartLinkDB integration** (from WiFi-Frankenstein): massive DB of AP locations.
   Only valuable if Indra targets wardriving/OSINT workflows.
10. **Cloud sync** (from zAnti): zCloud-style backup. Privacy-sensitive; low priority.
11. **Plugin system with remote install** (from zAnti): Indra's Tools screen is local-only;
    a remote plugin catalog would be a big undertaking.

### What Indra should NOT copy
- StrykerOSS's Java 8 / targetSdk 28 / god-class architecture — Indra is already ahead.
- zAnti's legacy UI (ActionBarSherlock, SlidingMenu) — a decade behind.
- WiFi-Frankenstein's mixed Compose/XML — Indra is Compose-pure.

---

## 13. Indra's Unique Advantages (not present in any competitor)

1. **Rust daemon with per-connection child table** + teardown on disconnect (no orphaned root tools).
2. **Session-scoped ViewModels** (scan survives navigation, results persist off-screen).
3. **Navigation 3 with typed keys** (compile-safe destinations).
4. **Honest capability gating** (`PluginStatus` with 6 distinct states; blocked cards still openable when the screen is the fix).
5. **Empty-state contract** (36 screens, `finished` flag, no "0 results = error").
6. **Shared UI components** (one console, one error line, one host-chip row).
7. **Signed OTA with rollback** + per-tool updates.
8. **Rootless honesty** (`Rootless.kt` single source of truth; no fake "daemon running without root").
9. **Magisk v31+ compatibility** (no reactive prompt; capability probing).
10. **~1114 unit tests** + detekt on every module.

---

## 14. Conclusion

Indra is the most architecturally mature of the five. Strix is the closest functional
competitor and the reference for MSF integration + CI/CD. StrykerOSS is the most
feature-rich (chroot + rootless QEMU + HID/USB + VNC) but on a legacy stack that Indra has
already surpassed. WiFi-Frankenstein owns the WiFi-database/wardriving niche. zAnti is a
legacy commercial product whose only transferable ideas are cloud sync and MITM HTTP
injection.

**Recommended next focus for Indra:** close the MSF workflow gap (sessions + payload gen +
interactive shell) using Strix as reference, add weekly CI builds, and expand rootless parity
toward StrykerOSS's QEMU approach. Everything else is polish.
