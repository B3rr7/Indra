# Deep Comparison: Indra vs StrykerOSS

Date: 2026-10-27 (deep re-audit; supersedes the 2026-10-23 version)

A third reference, **Kali NetHunter**, is the upstream benchmark both projects are measured against. It is not a codebase in this workspace (`kali-nethunter-project/` is a meta-repo — README + version.txt + GitLab issue templates only; the actual app/kernel/rootfs live in separate GitLab repos). Its approach is the "full Kali chroot + custom device kernel" model.

---

## 1. What each project actually is

| | Indra | StrykerOSS |
|---|---|---|
| Tagline | "more advanced, easier-to-use pentest tool than cSploit ever was" | "free and open-source mobile pentest suite for Android" |
| Origin | cSploit descendant (evilsocket, 2012), rebuilt from scratch in Kotlin/Compose | Independent (zalexdev, 2021), v6.5.1 |
| Core idea | Lightweight native daemon (`indrad`, descendant of `cSploitd`) + bundled tools in an app-private core dir | Debian trixie arm64 chroot under `/data/local/stryker/release`, **plus a rootless QEMU/UML VM fallback** |
| Package | `org.indra.android` | `com.zalexdev.stryker` |
| Version | 2.0.0 (versionCode 5) | 6.5.1 (versionCode 651) |
| License | GPLv3 | GPLv3 |

The single most important architectural difference: **Indra hand-ports ~40 C libraries to Android NDK; Stryker installs Debian packages into a chroot.** That one choice explains almost every downstream difference in this document.

---

## 2. Build & toolchain

| | Indra | Stryker |
|---|---|---|
| Language (app) | Kotlin 1.9.24 | Java, source/target level 1.8 |
| UI | Jetpack Compose + Material 3 | XML layouts, Activities/Fragments |
| DI | Hilt 2.51.1 | none |
| Async | Coroutines + Flow | threads + `Runtime.exec` |
| AGP | 8.5.0 | 8.13.2 |
| Gradle | 8.7 | 8.13 |
| NDK | 25.2.9519653 | 25.1.8937393 |
| compileSdk | 35 | 37 |
| minSdk | 26 (Android 8) | 24 (Android 7) |
| targetSdk | 35 | 37 |
| Java | 17 (build + runtime) | 8 (source compat) |
| ABI | armeabi-v7a **+** arm64-v8a | arm64-v8a only |
| Modules | `:core :data :ui :features :indra` (+ `indra/jni` submodule) | `:app :terminal :chrome-tabs :NeoLang :Xorg :NeoTermBridge` |
| APK / payload | 47.2 MB (incl. 11.9 MB core tarballs) | 72 MB app dir; chroot is a **237–297 MB external download**; rootless QEMU payloads ~120 MB |
| CI | `./gradlew test` + `detekt` + unsigned R8 release build, JDK 21 | `./gradlew build`, JDK 17, installs `platforms;android-33` while `compileSdk = 37` |

### Build-hygiene findings (Stryker)
- `settings.gradle` still declares `jcenter()` and `maven { url 'https://jcenter.bintray.com/' }`. JCenter has been read-only/shut down since 2022; with `FAIL_ON_PROJECT_REPOS` this is a real resolution risk, not cosmetic.
- README says **Target SDK 28** and **v6.0**; `build.gradle` says **target 37** and **6.5.1**. The contributing guide tells contributors to "keep `targetSdk = 28`" while the build is 37. Documentation drift is severe.
- `requestLegacyExternalStorage = true`, `MANAGE_EXTERNAL_STORAGE`, `usesCleartextTraffic = true` on a target-37 app. Fine for sideload, a hard blocker for Play Store.
- No `.github/workflows` test step beyond `build`; the single test file is a placeholder in `:Xorg`.

### Build-hygiene findings (Indra)
- Detekt wired into all five modules with per-module baselines; `./gradlew detekt` green.
- StrictMode `penaltyLog` in debug builds; 13 cold-start violations found and fixed (prefs, log-file probe, crash sweep, `System.loadLibrary`, `filesDir`).
- No `jcenter`; `network_security_config.xml` blocks cleartext except loopback.

---

## 3. Code quality & maintainability

| Metric | Indra | Stryker |
|---|---|---|
| App LOC | 21,963 (Kotlin) | 74,480 (Java, app module) |
| Largest file | `SmartToolBox.kt` 1,012 | `WiFIAdapter.java` 1,860; `Core.java` 1,643 |
| God classes | `SmartToolBox` (1,012) — but pure registry + execution | `Core.java` (1,643) + `WiFIAdapter` (1,860) + `Dashboard` (1,707) + `LocalAdapter` (1,051) + `LightfallView` (1,034) |
| Test files | 23 (core + ui) | 1 placeholder |
| `@Test` count | 131 | 0 (real) |
| Detekt suppressions | 14 `@Suppress` | n/a (no detekt) |
| MVVM | ✅ ViewModel + StateFlow per screen | ❌ Activity/Fragment centric, no ViewModels |
| DI | Hilt | none — `Core` is a static god class |
| `requireTool` gates | 28 screens | n/a (no gate abstraction) |
| `shellQuote` call sites | 71 (every root-interpolated field) | n/a (commands built ad hoc) |
| Modules split | `:core :data :ui :features :indra` | `:app` monolith + terminal/chrome-tabs/Xorg |

**Indra's documented self-criticism (from `IMPROVEMENT_PLAN.md`):** no tests in `:features` (9.3k LOC of ViewModels), 389 detekt findings in the audit (mostly addressed), `Topology` screen has no data source (dead), no foreground service for 30-minute tools, daemon auth is a static hash behind a `0666` socket, 173 hardcoded strings.

**Stryker's structural issue:** `Core.java` is the single chokepoint — SharedPreferences, SQLite, asset extraction, root process execution, chroot mount/repair, OTA, logging. Every feature module reaches into it. There is no seam to test against; the one test file confirms it.

---

## 4. Feature matrix (verified against code, not READMEs)

| Capability | Indra | Stryker |
|---|---|---|
| LAN discovery / port scan | ✅ nmap, masscan | ✅ nmap + **non-root scanner** (`localnetwork/nonroot/`, 862-line `NonRootScanner` + 19 resolver classes: mDNS, SSDP, NetBIOS, SNMP, rDNS, Cast, ICMP, TCP-connect) |
| OS / service fingerprint | ✅ Inspector (nmap `-O`) | ✅ |
| Traceroute | ✅ `nmap --traceroute` | via tools |
| Packet capture | ✅ tcpdump (+ save to `.cap`) | ✅ via chroot |
| Packet forge | ✅ hping3 | via tools |
| MITM / ARP spoof | ✅ arpspoof (risk dialog; hard-resets moto g60) | via tools |
| Credential cracking | ✅ hydra | ✅ hydra **+ Hashcat on handshakes** |
| Metasploit | ⚠️ optional chroot, parse-only `MsfSearch` | ✅ full console, sessions, payload gen |
| Nuclei | ✅ optional download; **no templates shipped** (F2) | ✅ severity-grouped findings |
| SearchSploit / Arsenal | ⚠️ screens exist, need artifact install | ✅ full ExploitDB + templated arsenal (`{IP}/{PORT}/{MAC}`) |
| WiFi audit | ✅ aircrack-ng | ✅ |
| Monitor mode | ❌ **blocked**: no `iw`/`airmon-ng`/`airodump-ng` (Wifite stage 1, handshake capture, Reaver unreachable) | ✅ (external monitor-mode USB adapter) |
| Handshake storage/crack | ⚠️ file browser only | ✅ rename, share, OnlineHashCrack, on-device crack |
| GeoMac | ✅ KML/CSV | ✅ OSM map |
| HID / DuckyScript | ⚠️ parser + reports; refuses without bound UDC | ✅ 7 layouts, live log, sample payloads |
| USB Arsenal | ⚠️ reports `UDC: false`, no binding | ✅ configfs profiles, mount `.img/.iso` |
| WhisperPair (BLE Fast Pair) | ⚠️ scan + vuln check; exploit chain **not implemented** | ✅ CVE-2025-36911 full chain + HFP audio capture |
| VNC desktop | ❌ | ✅ in-chroot XFCE |
| Rootless operation | ⚠️ partial (no `su`, `-sT`, gating) | ✅ QEMU/UML VM boots same rootfs |
| Camera/RTSP scan | ❌ | ✅ `cameradar` |
| Built-in terminal | ✅ Compose terminal | ✅ xterm + launcher alias |
| OTA updates | ✅ signed manifest, SHA-256, atomic swap, rollback, per-tool | ✅ manifest + SHA-256 per payload |
| i18n | ✅ many locales | ❓ (strings present) |

**Honesty discipline (Indra only):** `PluginStatus` distinguishes `DAEMON_DOWN` / `TOOL_MISSING` / `ARTIFACT_MISSING` / `NEEDS_ROOT` / `UNSUPPORTED`; zero-result ≠ error (`finished`/`hasRun` flags); one console component (`LiveOutputCard`) and one error component (`FormError`). Stryker has no such contract — screens render whatever the tool prints.

---

## 5. Security posture

| Area | Indra | Stryker |
|---|---|---|
| Network (cleartext) | `network_security_config.xml`: blocked except loopback (OTA test mode) | `usesCleartextTraffic="true"` globally |
| Storage | app-private core dir, traversal-guarded tar extraction, `ATOMIC_MOVE` + mode `0755` | `MANAGE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage`, chroot under `/data/local` |
| Root socket | daemon socket `chmod 0666` ⚠️ (any app can connect; auth is a static hash) | runs as root via `su`; no socket boundary |
| Shell injection | `shellQuote()` (POSIX) on every user field; `ShellQuoteTest` ports the daemon tokenizer | commands built by string concat in `Core.java` |
| OTA trust | HTTPS + TLS pin + RSA signature + SHA-256 + rollback | HTTPS + SHA-256 (no signature verify evident) |
| Crash reporting | local `CrashReporter` (no ACRA, no telemetry) | not inspected |

Indra is materially safer on the network and storage axes; its one real hole is the daemon socket permissions, which is on the improvement plan.

---

## 6. Runtime behaviour (from the 2026-10-27 device audit)

Indra's all-tools audit drove 37 plugins on a moto g60:
- **21 real, device-verified** (Port Scanner, Inspector, Traceroute, Network Radar, Packet Capture, Login Cracker — `Hydra v9.7 … 183 login tries`, Credential Tester, Packet Forger — `2 packets transmitted, 2 received, 0% packet loss`, RouterPwn, WiFi Audit, Gobuster on IP targets, Terminal, MAC Changer, IPTables chain listing, WiFi Scanner, Handshakes, GeoMac, WhisperPair scan, Session Hijacking, DNS Spoofing, Password Auditor — `Entropy: 59.0 bits`, Arsenal running a user template, HID/DuckyScript/USB Arsenal capability probes).
- **4 honest gates** (Exploit Finder no msf chroot, SearchSploit no ExploitDB, Bettercap no artifact, Vuln/Web Tester no templates).
- **Blocked/dead/unsafe**: Wifite + monitor + Reaver (no `iw`), Responder (`UNSUPPORTED`, dead screen), Topology (no data source), MITM + One-Tap MITM (not run — arpspoof hard-resets the phone).

Two bugs surfaced and were fixed during the sweep: nuclei dying on `mkdir /.config: read-only file system` (daemon `HOME=/`), and a latched `startFailed` flag that made every gate report "daemon not running" after one refused start.

Stryker ships the same features as *genuinely working* (monitor mode, handshake cracking, Metasploit console, HID/USB, WhisperPair exploit, VNC) because Debian packages and a chroot remove the porting tax — but only on arm64, only after a 240–300 MB download, and with no test suite to catch regressions.

---

## 7. Where each one wins

**Indra wins on:** engineering quality (tests, detekt, CI, ADRs), modern UX stack, footprint (~12 MB vs ~240 MB), dual ABI, OTA with health-check rollback, security hygiene, honest failure states, velocity (1,478 commits), and the fact that it is the only one of the three that is *maintainable* by a single developer.

**Stryker wins on:** feature *depth and completeness* — everything Indra advertises-but-can't-finish is real there, because the chroot removes the porting tax. Plus rootless QEMU fallback, non-root LAN scanning, cameradar, and a mature chroot installer.

**NetHunter (reference) wins on:** maximum capability — wireless 802.11 injection, MANA evil AP, BadUSB, full Kali toolset, VNC/KeX — at the cost of being device-locked to a custom kernel and a multi-GB payload.

---

## 8. What Indra should do next (ranked by value)

1. **Vendor `libnl` + `iw` + `airmon-ng` + `airodump-ng`.** This single gap blocks Wifite stage 1, handshake capture and Reaver — the entire WiFi half of the matrix. Highest ROI.
2. **Resolve the Go-tool DNS problem app-side** (OkHttp uses Android's resolver; the daemon has no `/etc/resolv.conf`). Until then, nuclei needs an app-side template installer and gobuster needs an "use an IP" hint.
3. **Rootless: finish or drop.** Stryker's QEMU fallback is the one feature Indra structurally cannot match without a VM layer; a half-working toggle is worse than none.
4. **Handshake lifecycle** — crack (hashcat) + export, not just list/rename.
5. **Daemon hardening** — socket permissions + replace the static auth hash; foreground service for 30-min tools.
6. **Kill or feed `Topology`**; add tests to `:features`; adaptive layout (`WindowSizeClass`).

## 9. Verdict

Indra and Stryker are **opposite bets on the same problem**. Stryker chose breadth by importing a whole Linux distro; Indra chose maintainability by owning a small native core. NetHunter is the ceiling neither has reached (device-locked kernel + full Kali). Indra's realistic win condition is not to match NetHunter's feature count — it's to be the only modern, lightweight, dual-ABI, OTA-updatable alternative that doesn't ask the user to download 300 MB, and to be *honest* about what works. The remaining work is mostly the WiFi stack (`iw`/`libnl`) and finishing the half-built screens (WhisperPair exploit chain, handshake cracking, Topology). Everything else is engineering hygiene that Indra is already ahead on.