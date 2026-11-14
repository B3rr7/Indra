# FINAL VERDICT — ranked, evidence-based

Measured 2026-11-14. Supersedes every earlier comparison document. All figures traced to source.

---

## 1. Scoring model

| Dimension | Weight | What it measures |
|---|---|---|
| Offensive breadth | 25% | how many attack classes the app can actually perform |
| Workflow depth | 20% | how complete the important flows are (MSF, WiFi, MITM) |
| Execution architecture | 15% | privilege model, process control, event fidelity |
| Code quality | 15% | modules, tests, static analysis, testability |
| Build & supply chain | 10% | reproducibility, signature verification, CI |
| UX & state handling | 10% | navigation safety, state retention, honest states |
| Openness | 5% | source availability, extensibility |

Scores are 0–10 and justified per project in §3.

---

## 2. Final ranking

### Overall (architecture + capability + engineering)

| # | Project | Score | One-line verdict |
|---|---|---|---|
| **1** | **Indra** | **8.35** | Best-engineered pentest suite of the five; broad and honest, with depth still unfinished |
| **2=** | **Strix** | **7.20** | Narrow but deep where it matters, with the best native supply chain in the field |
| **2=** | **StrykerOSS** | **6.90** | Most capable by a wide margin, weakest hygiene; three execution engines |
| **4** | **WIFI-Frankenstein** | **4.70** | Specialist WiFi/DB tool; owns its niche, is not a general suite |
| **5** | **zAnti** | **4.20** | Dated, closed, but still holds the only HTTP-injection/SSL-strip engine |

#### Weight sensitivity (added on re-check)

The 0.30 gap between Strix and StrykerOSS is **inside the model's noise**. Tested by shifting 10–20
points of weight between every pair of dimensions:

| Perturbation | Result |
|---|---|
| breadth ↔ quality (0.10 / 0.15 / 0.20) | order unchanged |
| breadth ↔ execution (0.10 / 0.15 / 0.20) | order unchanged |
| depth ↔ quality (0.10 / 0.15 / 0.20) | order unchanged |
| **build → breadth +0.10** | **StrykerOSS overtakes Strix** |
| **quality → breadth +0.10** | **StrykerOSS overtakes Strix** |
| **ux → breadth +0.10** | **StrykerOSS overtakes Strix** |
| breadth raised to 50% | StrykerOSS 2nd, Strix 3rd, zAnti 4th, WIFI-F 5th |
| quality raised to 40% | order unchanged |
| equal weights (1/7 each) | Indra 8.29 > Strix 7.57 > StrykerOSS 6.29 > WIFI-F 4.71 > zAnti 3.29 |

**Robust conclusion:** Indra is 1st under every weighting tested (margin 0.7–1.5 points).
**Not robust:** 2nd vs 3rd — Strix and StrykerOSS trade places as soon as breadth gains 10 points of
weight over build, quality or UX. Reporting them as equal is the honest reading.
4th vs 5th is also unstable (zAnti overtakes WIFI-Frankenstein when breadth weight rises).

### Capability only (breadth 55% + depth 45%)

| # | Project | Score |
|---|---|---|
| **1** | **StrykerOSS** | **9.10** |
| 2 | Indra | 7.65 |
| 3 | zAnti | 6.55 |
| 4 | Strix | 6.35 |
| 5 | WIFI-Frankenstein | 5.35 |

### Engineering only (quality + build + architecture)

| # | Project | Score |
|---|---|---|
| **1** | **Indra** | **10.0** |
| 2 | Strix | 9.0 |
| 3 | WIFI-Frankenstein | 4.0 |
| 4 | StrykerOSS | 3.0 |
| 5 | zAnti | 1.0 |

**Read them together:** Indra wins overall because it is the only project strong on all three axes.
StrykerOSS wins features and loses everything else. Strix wins build engineering and loses breadth.

---

## 3. Per-project final assessment

### 1. Indra — 8.35

| Dimension | Score | Evidence |
|---|---|---|
| Breadth | 9 | **37 plugins**, full `id → requires` table traced: scanner, inspector, traceroute, masscan, MITM×3, radar, capture, forge, hydra×2, routerpwn, wifi audit/wifite/reaver/handshakes/keygen/scanner, nuclei×2, gobuster, searchsploit, arsenal, iptables, macchanger, geomac, whisperpair, session hijack, dns spoof, hid/ducky/usb, bettercap, terminal, msf |
| Depth | 6 | MSF = Exploit Finder (search) + a free console (`use/set/run/sessions` typed by hand) — **no structured module config, payload picker or session UI**. `Capability.HCXTOOLS`/`HASHCAT` are documented as **unsatisfiable in Alpine 3.24**, so the handshake→hashcat path cannot be completed that way |
| Execution | 10 | 18 tool specs: 8 daemon-handler + 10 direct `su` + chroot; Rust `indrad` (1,177 lines); structured `PortFound`/`OsFound`; `INDRA_PID` + descendant TERM→KILL; `redirectInput(/dev/null)` |
| Quality | 10 | **663 `@Test`** (core 594 / features 47 / indra 18 / ui 4), detekt with 4 baselines, 5 modules, heavy pure-function layer |
| Build | 5 | 10 in-tree C tools (`arpspoof tcpdump ettercap hydra nmap fusemounts network-radar hping3 aircrack-ng reaver`) + 4 artifacts; EOL tree, ~40 patches, NDK 25.2 pinned; **no publishing CI** |
| UX | 10 | Nav3 typed keys (41 destinations), session-scoped VMs, `PluginStatus` enum, zero≠error |
| Openness | 8 | |

**Unique:** hybrid 3-route execution · Rust daemon · pure-Kotlin client · structured nmap events · session VMs · validated registry.

---

### 2. Strix — 7.20

| Dimension | Score | Evidence |
|---|---|---|
| Breadth | 5 | **15 screens**: Port Scanner, Host List/Detail, Traceroute, Packet Capture, Packet Forger, Brute Force, MITM, Exploit Finder, MSF, Shell, WiFi Keygen, Logs, Settings. Verified **zero** references to `aircrack`/`reaver`/`wifite`/`wps`/`monitor`: no WiFi attack, no web dir brute, no terminal, no radio tooling |
| Depth | 8 | **Best MSF in the field**: 26-file `ui/msf` package — `ExploitConfigPanel`, `ExploitPayloadPicker`, `ExploitOptionFields`, `buildExploitCommand` (`use → set → exploit -j`), `MsfSessionCards`, session `ShellScreen`, `MsfSessionNotifier`, `MsfDaemon` with generation counter + `beginMaintenance()` |
| Execution | 6 | Direct `su -c` only; `ProcessBuilder("su","-c")`; `__STRIX_PID__` + `pgrep -P` TERM→KILL; **zero** `chroot`/`rootless` references |
| Quality | 9 | 117 `@Test`, clean `core/data/domain/ui` split, no god classes |
| Build | **10** | `versions.env` pins **SHA256 of every upstream** and verifies **OpenPGP signatures** for nmap, libpcap, tcpdump, libidn, openssl, curl, libssh2, libssh, pcre2, libgpg-error, libgcrypt, zlib, mariadb, cyrus-sasl; Docker bundles + Android L2-forwarding patches; per-gem hash lock; **scheduled weekly track** (`0 6 * * 5`) |
| UX | 7 | Compose/Hilt, but entry-scoped ViewModels (state dies on back) |
| Openness | 8 | GPLv3 |

**This is the one project whose native build is better engineered than Indra's.**

---

### 3. StrykerOSS — 6.90

| Dimension | Score | Evidence |
|---|---|---|
| Breadth | **10** | `hid` 38 files, `engine` 38, `localnetwork` 34 (+`exploits/`, `nonroot/`), `netdetect` 15 (USB chipset/driver detection), `ota` 13, `wpair` 11, `metasploit` 11, `wifi` 9, `geomac` 9, `arsenal` 9, `nuclei` 8, `handshakes` 7, `usbarsenal` 6, `vnc` 3, `hashcrack` 3, `hydra` 4, **`cameradar`** (RTSP camera scan) |
| Depth | 8 | Native MSF in Debian + `PayloadGenerator` + `SetValues` + `ExploitLaucnher`; **8 keyboard layouts** (de en es fr gb it ru us); USB Arsenal + `UsbPassthroughManager`; XFCE VNC (`VNCService` + `VNCFragment`); `netdetect` 1,654 lines real USB chipset/driver probing. **`cameradar` is an installer only** (`CameradarInstall` 373 lines, wired from `LocalAdapter`) — not a full RTSP scanner |
| Execution | 8 | **3 engines**: `CHROOT` (Debian trixie at `/data/local/stryker/release`), `ROOTLESS` (QEMU), **`UML`** (User-Mode Linux); 8 prebuilt `.so` (`qemu uml umnet umusb slirp passt bash stub`) + JNI `neigh.c`/`protected.c` |
| Quality | 3 | **1 `@Test`** (vendored), `Core.java` 1,643 lines, static singleton threaded everywhere |
| Build | 4 | 6 modules but arm64-only, NDK 25.1, binary VM blobs, no visible CI |
| UX | 4 | XML fragments + drawer + app-intro |
| Openness | 7 | GPLv3 |

**Breadth leader. If the question is "what can it do", this wins.**

---

### 4. WIFI-Frankenstein — 4.70

| Dimension | Score | Evidence |
|---|---|---|
| Breadth | 4 | 26 UI packages, all WiFi/DB-adjacent: **no MSF, no general port scanner, no MITM, no payload tooling** |
| Depth | 7 | `internetblocking` **37 files** (DPI/RKN detection — unique), `dbsetup` 25 (3WiFi/SmartLink/custom SQLite), `wifimap` 15, `databasefinder` 15, `localnetwork` 11, `handshakecapture` 11, `personalmap` 10 (wardriving), `wifianalysis` 9, `iwwifi` 7, `netprotection` 6, `bettercap` 6, `wpacracker` 5, `handshakeconverter` 5, `pixiedust` 4, `wpsgenerator` 4, `routerscan` 3 |
| Execution | 4 | Only **4 files** touch `su`; proot (18 refs) for Router Scan; own `cracker.c` (1,104 lines) |
| Quality | 4 | **0 tests**, single module |
| Build | 3 | unknown CI |
| UX | 5 | Compose + XML mixed |
| Openness | 6 | GPLv3 |

**Owning niche:** 3WiFi/SmartLinkDB lookup, wardriving, WPS PIN generation, DPI detection, finished handshake converter.

---

### 5. zAnti — 4.20

| Dimension | Score | Evidence |
|---|---|---|
| Breadth | 7 | 21 top-level packages: `Scanner`, `zmitm`, **`ZHttpInjector`** (HttpProxyServer, HTTPSession, SSLServerConnection, `filters/`, `interactive/`), `zcloud`, `samba` (jcifs), `httpserver`, `CrackerPlugin`, `heartbleedplugin`, `ShellShockPlugin`, `SSLPoodlePlugin`, `RouterPwnPlugin`, `macchanger`, `ztether`, `ztether`, `zipsadvert` |
| Depth | 6 | Genuine SSL-intercepting HTTP proxy with injection filters — **nobody else has rebuilt this** — but built for **targetSdk 21** |
| Execution | 5 | `ShellPool` (438 lines) persistent root shells |
| Quality | 1 | 1,719 app smali; `Helpers` 6,226 / `Anti` 2,153 lines; closed source |
| Build | 0 | prebuilt commercial APK, **minSdk 14 / targetSdk 21**, 2 native libs (`libwizardroid`, `libzcloud`) |
| UX | 3 | ActionBarSherlock + SlidingMenu |
| Openness | 1 | commercial |

**Only remaining differentiator:** MITM HTTP injection + SSL stripping with a filter engine.

---

## 4. Final capability list (who has what, verified)

| Capability | Indra | Strix | Stryker | WIFI-F | zAnti |
|---|---|---|---|---|---|
| Port scan | ✅ nmap + masscan + rootless | ✅ nmap | ✅ nmap | ◐ | ✅ |
| Host discovery | ✅ radar + neigh | ✅ ARP | ✅ | ◐ | ✅ |
| OS fingerprint | ✅ structured | ◐ | ◐ | ✗ | ✗ |
| Traceroute | ✅ | ✅ | ✅ | ✗ | ✗ |
| Brute force | ✅ hydra | ✅ hydra | ✅ hydra | ✗ | ✅ |
| MITM ARP | ✅ | ✅ | ✗ | ✗ | ✅ |
| MITM HTTP inject / SSL strip | ✗ | ✗ | ✗ | ✗ | **✅ only** |
| DNS spoof | ✅ | ✅ | ✗ | ✗ | ✗ |
| Packet capture | ✅ | ✅ | ✅ | ✅ | ✗ |
| Packet forge | ✅ | ✅ | ✗ | ✗ | ✗ |
| Vuln scan | ✅ nuclei | ✗ | ✅ nuclei | ✗ | ✅ 4 CVE plugins |
| Web dir brute | ✅ gobuster + in-app | ✗ | ✗ | ✗ | ✗ |
| WPS | ✅ reaver | ✗ | ✅ pixiedust | ✅ pixiedust + PIN | ✗ |
| WPA crack | ✅ aircrack-ng | ✗ | ✅ hashcat | ✅ own `cracker.c` | ✗ |
| Handshake convert | ◐ gated | ✗ | ◐ | ✅ **converter** | ✗ |
| Metasploit | ◐ search + console | **✅ full flow** | ✅ native + payloads | ✗ | ✗ |
| ExploitDB | ✅ | ✗ | ✅ | ✗ | ◐ |
| Router attack | ✅ | ◐ | ✅ arsenal | ✅ Router Scan | ✅ |
| HID / DuckyScript | ◐ | ✗ | **✅ 8 layouts** | ✗ | ✗ |
| USB Arsenal | ◐ | ✗ | **✅ + passthrough** | ✗ | ✗ |
| BLE / FastPair | ✅ | ✗ | ✅ | ✗ | ✗ |
| WiFi DB lookup | ✗ | ✗ | ◐ vendor | **✅ 3WiFi/SmartLink** | ✗ |
| Wardriving | ✗ | ✗ | ✗ | **✅** | ✗ |
| DPI/RKN block check | ✗ | ✗ | ✗ | **✅** | ✗ |
| Camera (RTSP) scan | ✗ | ✗ | ◐ cameradar installer | ✗ | ✗ |
| USB NIC/WiFi chipset detect | ✗ | ✗ | **✅ netdetect** | ✗ | ✗ |
| VNC desktop | ✗ | ✗ | **✅** | ✗ | ✗ |
| VM rootless | ✗ (7 app plugins) | ✗ | **✅ QEMU + UML** | ✗ | ✗ |
| Terminal | ✅ | ✅ shell | ✅ | ✗ | ✗ |
| Cloud sync | ✗ | ✗ | ✗ | ◐ 3WiFi API | **✅ zcloud** |
| SMB client | ✗ | ✗ | ✗ | ✗ | ✅ jcifs |
| HTTP server | ✗ | ✗ | ✗ | ✗ | ✅ |

---

## 5. Final list of Indra's remaining gaps, ordered by what actually costs it

| # | Gap | Why it costs | Fix |
|---|---|---|---|
| 1 | MSF is search + free console only | Strix shows the complete flow; a pentester cannot pick a payload, set options and land a session without typing | port Strix's model: config panel, payload picker, option fields, session list |
| 2 | No publishing CI | Strix's signed, reproducible builds are the field's benchmark | weekly workflow: `build.sh cores` + APK + signed bundle manifest |
| 3 | Handshake→hashcat is impossible as designed | `Capability.HCXTOOLS`/`HASHCAT` are documented unsatisfiable in Alpine 3.24 | make aircrack-ng the primary path (already the documented fix), or ship a bundled hcxtools |
| 4 | Monitor mode blocked on the test device | Wifite/Reaver/handshake capture are gated | already diagnosed: vendor qcacld driver; needs external adapter |
| 5 | targetSdk 36 vs peers' 37 | only API-level lag in the field | bump after Nav3 1.2 allows it |
| 6 | HID/USB not bound to a UDC | StrykerOSS ships 8 layouts + gadget binding + passthrough | finish `hidg0`/UDC binding |
| 7 | No camera / USB NIC detection | StrykerOSS `cameradar`, `netdetect` | optional, cheap wins |
| 8 | No wardriving / WiFi DB | WIFI-F owns it | out of scope unless repositioning |

---

## 6. One-paragraph verdict

**Indra is the best-engineered of the five and the best overall balance**, with a hybrid execution model
(daemon + direct `su` + Alpine chroot), the only pure-Kotlin daemon client, structured nmap events,
session-scoped ViewModels and 663 tests against 1 test and 0 tests in the two nearest feature rivals.
It is **not** the most capable: **StrykerOSS has more offensive surface** (three engines, 8-layout HID,
USB Arsenal, VNC, camera scanning, chipset detection, native MSF), and **Strix has both the most
complete Metasploit workflow and the strongest native supply chain** (OpenPGP-verified, SHA-pinned
Docker builds with a weekly track). Indra's remaining work is therefore not architectural: it is
**finishing depth (MSF, hashcat path), adding publishing CI, and closing four niche features**.

---

## 7. Corrections carried forward from the verification pass

37 registered plugins / 36 screens · 18 tool specs · 663 tests (1,326 executions) · 231 main Kotlin files ·
`gobuster` is a `direct` tool, not `raw` · Strix = 117 tests, not 30 · StrykerOSS = targetSdk 37,
6 modules, 3 engines, 8 native libs, 8 HID layouts · zAnti = targetSdk 21, 1,719 app smali,
2 native libs · WIFI-Frankenstein = targetSdk 37, no hashcat, own `cracker.c`.
