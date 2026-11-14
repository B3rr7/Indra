# VERIFIED COMPARISON — Indra vs Strix vs StrykerOSS vs WIFI-Frankenstein vs zAnti

**Status: authoritative.** Every figure below was measured on disk on 2026-11-14 with `find`/`grep`/`wc`/`unzip`.
The earlier documents (`ULTIMATE_COMPARISON.md`, `COMPARISON_TABLE.md`, `FINAL_COMPARISON.md`,
`DEEP_CODEBASE_ANALYSIS.md`, `PROJECT_DEEP_ANALYSIS.md`, `COMPARISON_ANALYSIS.md`) contain the errors
listed in §2 and are **superseded by this file**.

---

## 1. How these numbers were produced

| Metric | Method |
|---|---|
| Source file counts | `find <module>/src/{main,test} -name '*.kt'` (or `*.java`) |
| Test counts | `grep -rh '@Test' <module>/src/test \| wc -l` — annotation count, not file count |
| Tool registry | literal read of `SmartToolBox.TOOL_SPECS` spec list |
| Plugin count | `grep -oE 'id = "[a-z_0-9]+"' PluginManager.kt \| sort -u \| wc -l` |
| SDK / stack | `build.gradle(.kts)`, `gradle/libs.versions.toml`, `gradle-wrapper.properties` |
| Native components | `find -name '*.so'`, `find -name '*.c'`, `find -name '*.rs'`, APK `zipfile.namelist()` |
| Competitor CI | `sed` of every `.github/workflows/*.yml` `on:` block |

---

## 2. Corrections — claims that were wrong

Severity: **H** = changes a conclusion, **M** = material, **L** = cosmetic.

| # | Claim in old docs | Measured truth | Sev |
|---|---|---|---|
| 1 | Indra ~279 Kotlin files | **231 main** + 81 test = 312 | M |
| 2 | Indra ~1114 unit tests | **663 unique** `@Test` (core 594, features 47, indra 18, ui 4); 1,326 executions across debug+release | H |
| 3 | Indra "17 tool specs" (also written as 18 and 19) | **18 specs** | M |
| 4 | Indra "36 plugins" vs "37 plugins" (flip-flopped) | **37 registered ids**, **36 plugin screens** in `:features`; `terminal` screen lives in `:indra` | M |
| 5 | Indra `gobuster` runs via the `raw` handler | **`gobuster` is a `direct` tool** (`ToolSpec("gobuster","direct",…)`); the bundled binary needs no daemon | H |
| 6 | Strix "30 tests" | **30 test *files*, 117 `@Test`** methods | H |
| 7 | Strix "weekly CI builds" | 6 workflows, but only `update-bundles.yml` is scheduled (`0 6 * * 5`). `build-apk.yml` = tags + `workflow_dispatch`; `ci.yml` = PR. It is a **weekly bundle track**, not weekly APK builds | M |
| 8 | StrykerOSS "targetSdk 28 (legacy)" | **compileSdk 37 / targetSdk 37 / minSdk 24** | H |
| 9 | StrykerOSS "1 module, monolithic" | **6 Gradle modules**: `:app :terminal :chrome-tabs :NeoLang :Xorg :NeoTermBridge` | H |
| 10 | StrykerOSS `Core.java` = 1,336 lines | **1,643 lines** | L |
| 11 | StrykerOSS "331 Java files" | **434** | M |
| 12 | StrykerOSS rootless = QEMU only | **three engines**: `CHROOT`, `ROOTLESS` (QEMU VM), **`UML`** (User-Mode Linux) | H |
| 13 | StrykerOSS native = "minimal (neigh.c, protected.c)" | **8 prebuilt `.so`**: `libqemu`, `libuml`, `libumnet`, `libumusb`, `libslirp`, `libpasst`, `libbash`, `libstub` + JNI `neigh.c`/`protected.c` | H |
| 14 | StrykerOSS HID = "7 keyboard layouts" | **8**: `de en es fr gb it ru us` | L |
| 15 | WIFI-Frankenstein targetSdk 34 | **compileSdk 37 / targetSdk 37 / minSdk 21** | M |
| 16 | WIFI-Frankenstein uses "hashcat" | **No hashcat.** Own native `cracker.c` (1,104 lines) + `HandshakeConverterEngine`; hashcat appears only in 3 incidental references | H |
| 17 | WIFI-Frankenstein root-heavy | Only **4 files** reference `su`; the product is DB/platform-API driven | M |
| 18 | zAnti "540 smali files" | **1,719** under `com/zimperium` (**5,139** total incl. bundled jsch/jcifs/jackson) | H |
| 19 | zAnti `Anti.smali` 59K / `Helpers.smali` 165K lines | **Anti 2,153 / Helpers 6,226 / ShellPool 438** | H |
| 20 | zAnti native: `libzmf.so`, `libzips.so`, `libandroid-http-injector.so` | **only 2**: `libwizardroid.so`, `libzcloud.so` | H |
| 21 | zAnti "targetSdk likely ≤28" | **minSdk 14 / targetSdk 21** (= Android 5.0), versionName 2.4.2 | H |
| 22 | (missing) Indra API level vs peers | Indra **targetSdk 36**; StrykerOSS + WIFI-Frankenstein are **37**. Indra is now *behind* on API level | M |
| 23 | Indra has "12 unique moats" | **8 survive scrutiny** — process-tree kill is duplicated in Strix, rootless parity is stronger in StrykerOSS, modular structure is matched. See §9 | H |

---

## 3. Verified scale

| Metric | Indra | Strix | StrykerOSS | WIFI-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Path | `/home/rana/Indra` | `/home/rana/strix/strix` | `/home/rana/strykerapp` | `/home/rana/WIFI-Frankenstein` | `/home/rana/zAnti` |
| Language | Kotlin 2.2.21 | Kotlin | Java 8 source level | Kotlin | Java (decompiled smali) |
| Main sources | **231 `.kt`** | 236 `.kt` | **434 `.java`** | **387 `.kt`** | 1,719 smali (app) |
| Test sources | 81 `.kt` | 30 `.kt` | 1 `.java` (vendored) | 0 | 0 |
| `@Test` methods | **663** | **117** | **1** | **0** | 0 |
| Gradle modules | **5** | 1 | **6** | 1 | n/a (prebuilt APK) |
| Plugin/feature screens | 37 ids / 36 screens | ~20 | ~35 packages | ~35 packages | ~50 activities |
| Native footprint | Rust daemon (1,177 lines) + 12 C tools | 6 C tools + Ruby/MSF bundle | 8 prebuilt `.so` + 2 JNI `.c` | 1 `.c` (1,104 lines) | 2 `.so` |
| Licence | open | GPLv3 | GPLv3 | GPLv3 | commercial, closed |

---

## 4. Verified stack

| | Indra | Strix | StrykerOSS | WIFI-Frankenstein | zAnti |
|---|---|---|---|---|---|
| compileSdk | 36 | 35 | **37** | **37** | — (targetSdk 21) |
| targetSdk | 36 | 34 | **37** | **37** | **21** |
| minSdk | 26 | 29 | 24 | 21 | **14** |
| UI | Compose, 100% | Compose, 100% | XML + Fragments | Compose + XML | ActionBarSherlock + SlidingMenu |
| Navigation | **Nav3 typed `NavKey`** (41 destinations) | Navigation Compose (string routes) | Drawer + `FragmentTransaction` | mixed | Activities + SlidingMenu |
| DI | **Hilt, all modules** | Hilt | static `Core` singleton | manual/Hilt mix | none |
| Build | AGP 8.13.2 / Gradle 8.14.6 / JDK 21 | Gradle KTS | Gradle 8-ish / NDK 25.1 | Gradle KTS | n/a |
| Static analysis | **detekt + 4 baselines** | unknown | lint | unknown | n/a |
| ABI | arm64-v8a + armeabi-v7a | arm64 | **arm64 only** | arm64-v8a | armeabi-v7a / x86 / mips |

---

## 5. Verified execution model

### Indra — hybrid, three routes (measured from `TOOL_SPECS`)

| Route | Tools |
|---|---|
| **Rust daemon** (`indrad`, Unix socket) | `nmap`, `ettercap`, `fusemounts`, `network-radar`, `msfrpcd`, `shell`→`raw`, `raw`, `blind` |
| **Direct `su -c`** (`ProcessManager`, pseudo-handler `direct`) | `masscan`, `hydra`, `aircrack-ng`, `reaver`, `hping3`, `nuclei`, **`gobuster`**, `bettercap`, `arpspoof`, `tcpdump` |
| **Alpine chroot** | nuclei/gobuster/hashcat/msf support paths via `ChrootEngine` |

Daemon = 9 handlers (`nmap, ettercap, tcpdump, arpspoof, fusemounts, network-radar, msfrpcd, raw, blind`);
direct = 10 of 18 specs; the `direct` pseudo-handler is deliberately not in `DAEMON_HANDLERS` so a
handler probe can never dim a direct tool.

### Everyone else

| | Indra | Strix | StrykerOSS | WIFI-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Daemon | **Rust `indrad`** | — | — | — | — |
| Direct `su` | ✅ | ✅ `ProcessBuilder("su","-c",…)` | only inside chroot | ✅ (4 files) | ✅ `ShellPool` |
| Chroot | **Alpine (musl)** | — (zero `chroot` refs) | **Debian trixie** `/data/local/stryker/release` | proot (Router Scan) | — |
| VM rootless | — | — (zero `rootless` refs) | **QEMU + UML** | — | — |
| App-API rootless | **7 plugins** | — | platform scanners | partial | — |
| Structured events | **`PortFound`/`OsFound`** | text parse | text parse | text parse | text parse |
| Tree kill | `INDRA_PID` + `ps/awk` TERM→KILL | `__STRIX_PID__` + `pgrep -P` TERM→KILL | chroot `pkill` | `destroy()` | pool + `pkill` |

**Correction of emphasis:** Strix's tree kill is the same design as Indra's — it is not a differentiator.

---

## 6. Verified capability matrix

Legend: ✅ native/working · ◐ partial or chroot-gated · ✗ absent.

| Capability | Indra | Strix | StrykerOSS | WIFI-Frankenstein | zAnti |
|---|---|---|---|---|---|
| Host discovery | ✅ radar + neighbour table | ✅ ARP | ✅ nmap | ◐ local scan + 3WiFi DB | ✅ `Connect` |
| Port scan | ✅ nmap + masscan + rootless connect | ✅ nmap | ✅ nmap | ◐ | ✅ |
| OS fingerprint | ✅ structured (`OsFound`) | ◐ text | ◐ | ✗ | ✗ |
| Brute force | ✅ hydra | ✅ hydra | ✅ hydra (chroot) | ◐ WPA only | ✅ CrackerPlugin |
| MITM | ✅ arpspoof/ettercap/DNS spoof | ✅ ettercap/arpspoof | ✗ | ✗ | ✅ **zmitm + ZHttpInjector (SSL strip)** |
| Packet capture | ✅ tcpdump + pcap | ✅ tcpdump | ✅ | ✅ handshake capture | ✗ |
| Packet forge | ✅ hping3 | ✅ | ✗ | ✗ | ✗ |
| Vuln scan | ✅ nuclei | ✗ | ✅ nuclei | ✗ | ✅ Heartbleed / ShellShock / SSLPoodle |
| Web dir brute | ✅ gobuster (direct) + in-app `DirBrute` | ✗ | ✗ | ✗ | ✗ |
| WPS | ✅ reaver | ✗ | ✅ PixieDust (chroot) | ✅ PixieDust + PIN gen | ✗ |
| WiFi crack | ✅ aircrack-ng | ✗ | ✅ hashcat (chroot) | ✅ **own `cracker.c`** + wpa-sec | ✗ |
| Handshake convert | ◐ (hcxtools gate) | ✗ | ◐ | ✅ **HandshakeConverter** | ✗ |
| Metasploit | ◐ chroot + RPC client | ✅ **msfrpcd + sessions** | ✅ native MSF in Debian | ✗ | ✗ |
| ExploitDB | ✅ searchsploit | ✗ | ✅ | ✗ | ✗ |
| Router attack | ✅ RouterPwn | ◐ default creds | ✅ Arsenal | ✅ Router Scan (proot) | ✅ RouterPwnPlugin |
| HID / USB | ◐ DuckyScript + profiles | ✗ | ✅ **8 layouts + USB Arsenal + 8 libs** | ✗ | ✗ |
| BLE | ✅ WhisperPair | ✗ | ✅ WhisperPair + FastPair | ✗ | ✗ |
| geoMAC / wardriving | ◐ KML export | ✗ | ✅ OSM map | ✅ **wardriving + 56-file 3WiFi** | ✗ |
| WiFi DB lookup | ✗ | ✗ | ◐ vendor DB | ✅ **3WiFi / SmartLink (31 files)** | ✗ |
| Cloud | ✗ | ✗ | ✗ | ◐ 3WiFi API | ✅ **zcloud** |
| VNC desktop | ✗ | ✗ | ✅ XFCE in chroot (`:Xorg`) | ✗ | ✗ |
| Terminal | ✅ daemon / local / chroot | ✗ | ✅ (`:terminal` module) | ✗ | ✗ |
| SMB client | ✗ | ✗ | ✗ | ✗ | ✅ jcifs |
| Internet-block detection | ✗ | ✗ | ✗ | ✅ DPI/RKN | ✗ |
| Bettercap | ◐ screen exists | ✗ | ✗ | ✅ 13 files | ✗ |

---

## 7. Verified Metasploit / OTA / root

### Metasploit

| | Indra | Strix | StrykerOSS |
|---|---|---|---|
| Runtime | Alpine chroot + separate `msf/` bundle | dedicated musl **Ruby + MSF bundle** (`ruby-msf/` Docker) | native MSF inside Debian chroot |
| RPC | `MsfRpcClient` + own `MsgPack` codec | `MsfRpcClient`/`MsfMsgPack`/`MsfRpcConsole` (7 files) | console parsing + adapters |
| Lifecycle | `MsfLaunch.probe()` per search | **`MsfDaemon` singleton, generation counter, `beginMaintenance()`** | singleton in `metasploit/` |
| Sessions | via RPC | **`MsfSessionNotifier` polling → notifications** | console |
| Verdict | functional, less automated | **most complete** | most compatible |

### OTA

| | Indra | Strix | StrykerOSS |
|---|---|---|---|
| Channels | `tools` + `msf` (`BundleSpec`) | tools + msf | core + QEMU/UML payloads |
| Trust | ECDSA P-256 envelope + pinned host | signature + SHA-256 | `VerifiedDownloader` + `RemoteManifest` |
| Swap | `BundleSwapper` atomic + rollback, `SafeTarExtractor` | `BundleInstaller` stage/swap | `CoreDownloader` + `UpdateWorker` |
| Automation | daily check, **no CI publishing** | **weekly scheduled bundle track (Fri 06:00)** | `UpdateScheduler` + poller |
| Extract safety | traversal + symlink rejection | `SafeTarExtractor` + `TarGuards` | unknown |

### Root

| | Indra | Strix | StrykerOSS | WIFI-F | zAnti |
|---|---|---|---|---|---|
| Probe | bounded `su -c` script + **`CapEff` capability read** | `su` test | `Core.checkRoot()` | `su` in 4 files | pool spawn |
| Magisk v31 | handled (no reactive prompt, `<queries>` for 5 managers) | unknown | unknown | unknown | n/a |
| No-root parity | 7 app-API plugins | none | **full-parity VM (QEMU/UML)** | partial | none |

---

## 8. Verified quality

| | Indra | Strix | StrykerOSS | WIFI-F | zAnti |
|---|---|---|---|---|---|
| Architecture | 5 modules, clean direction | 1 module, clean | **6 modules**, static `Core` | 1 module, ~35 features | activities |
| God class | none | none | `Core.java` 1,643 | none | `Helpers` 6,226 smali |
| Tests | **663** | 117 | 1 | 0 | 0 |
| Lint/detekt | **detekt, 4 baselines** | unknown | lint | unknown | n/a |
| Nav safety | **compile-time** | runtime strings | runtime | mixed | runtime |
| Registry validation | `ToolRegistryTest` enforces spec↔handler↔plugin | `BundleSpec` | hardcoded switch | n/a | n/a |

---

## 9. Moats that survive verification

| # | Moat | Unique? | Note |
|---|---|---|---|
| 1 | **Hybrid execution** (daemon + direct `su` + chroot) | ✅ | no peer combines all three |
| 2 | Pure-Kotlin daemon client (no JNI) | ✅ | unique |
| 3 | Rust privileged daemon (1,177 lines) | ✅ | unique |
| 4 | Session-scoped ViewModels (Nav3) | ✅ | Strix is entry-scoped → results die on back |
| 5 | Structured `Port`/`Os` events | ✅ | everyone else scrapes text |
| 6 | Honest UI states (zero ≠ error, status enum) | ✅ | unique discipline |
| 7 | Validated single-source tool registry | ◐ | Strix's `BundleSpec` is close |
| 8 | 7-plugin app-API rootless set | ◐ | StrykerOSS's VM parity is *more* capable, Indra's is lighter/faster |

**Dropped from the old "12 moats":** reliable tree kill (Strix has it), modular architecture
(StrykerOSS has 6 modules), modern stack (Indra is now targetSdk 36 vs peers' 37), OTA hot-swap
(Strix and StrykerOSS both ship it).

---

## 10. Revised verdict

**Indra is still the strongest engineering artifact of the five, but by a much smaller margin than the earlier documents claimed, and against a different field.**

- **StrykerOSS is not a legacy monolith.** It is a 6-module, API-37, arm64 platform with three
  execution engines (chroot, QEMU, UML), 8 native components, an 8-layout HID stack, full USB
  Arsenal, VNC desktop, native Metasploit, scheduled updates. Its weakness is code hygiene
  (`Core.java` 1,643 lines, static singleton, 1 test), not capability. **On feature breadth it is
  clearly ahead of Indra.**
- **Strix is Indra's closest peer in design**: Kotlin/Compose/Hilt, single module, direct `su` only.
  It has 117 tests (not 30), a genuinely more complete Metasploit stack, and the only scheduled
  automation in the field. It has no chroot, no daemon, no rootless mode, and loses ViewModel state
  on navigation.
- **WIFI-Frankenstein is a specialist**, not a general suite: essentially zero root dependency,
  zero tests, but owns the WiFi-database/wardriving/3WiFi niche outright plus its own WPA cracker.
- **zAnti is the weakest technically**: targetSdk 21, minSdk 14, 2 native libraries, no tests,
  closed. Its MITM HTTP-injection/SSL-strip engine remains the one thing nobody else has rebuilt.

**Where Indra leads:** execution architecture, process/state handling, test discipline, UI honesty.
**Where Indra trails:** Metasploit automation, CI automation, HID/USB, VM-based rootless parity,
WiFi database features, API level, and nuclei/monitor-mode/handshake completion.

---

## 11. Priority list (unchanged in order, corrected for reality)

| P | Item | Why |
|---|---|---|
| P0 | Weekly CI that actually publishes (APK + `build.sh cores` + signed bundle manifest) | Strix's scheduled track is the only automation in the field |
| P0 | Finish Metasploit (module run + session handling) | Strix and StrykerOSS both exceed Indra here |
| P1 | Nuclei templates on the path the scan reads | a scanner with no templates reports nothing |
| P1 | Monitor mode (`iw` + `iproute2` in Alpine + driver probe) | gates Wifite, Reaver, handshake capture |
| P1 | Handshake → hashcat pipeline (`hcxpcapngtool`) | WIFI-Frankenstein ships a finished converter; Indra's is gated |
| P1 | Move to `compileSdk`/`targetSdk` 37 | two competitors are already there |
| P2 | USB Arsenal UDC binding | StrykerOSS has 8 native components behind it |
| P3 | HTTP injection / SSL strip | zAnti's one unreplaced capability |
| P3 | 3WiFi/wardriving | specialist niche, low strategic value |

---

*Measured 2026-11-14. If a number here disagrees with a source file, the source file wins — re-run
the commands in §1 before trusting any figure.*
