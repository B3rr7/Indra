# Indra Improvement Plan

## Phase 2.5 — Code-health sprint (2026-10-24)

Follow-up to the full-codebase analysis (`develop`, graph gen `2026-10-24`). Ordered by value-per-effort; executed in one sprint.

- [x] **T1 — De-shadow `SmartToolBox` tool registry.** All duplicate/alias `registerTool(...)` entries collapsed into a single `TOOL_SPECS` table (later-registered duplicates silently overwrote earlier ones: masscan/reaver/bettercap/arpspoof/tcpdump). Entries for binaries never shipped (`sqlmap`, `nikto`) and unused aliases (`aircrack`, `networkradar`, dead handler rows) removed. Registry is now pure data: unit-tested for uniqueness and against the real daemon handler set (`ToolRegistryTest`).
- [x] **T2 — `shellQuote()` at the `SmartToolBox` boundary.** User-typed fields (target, user, wordlist, port, iface, bssid, filter, output file) are quoted before they are interpolated into raw-handler command strings (they ran as root). Free-form `args` passthrough wrappers keep documented plain concatenation.
- [x] **T3 — Remove orphaned `assets/indra.p12`** (4 KB PKCS#12 keystore referenced by no code/script — dev signing artifact shipped in the APK).
- [x] **T4 — Retire the fake `data` use-case stubs.** `NetworkUseCases`/`PluginUseCases` classes returned hardcoded fake results (`192.168.1.1`, ports 22/80/443) against the project's no-simulation principle, were referenced by nothing, and were deleted. Repositories stay (NetworkRepository is injected by HomeViewModel; PluginRepository removed with it — both wrappers around singletons). Decision recorded in `docs/adr/0001-thin-data-module.md`.
- [x] **T5 — `CoreManager` install-routine dedup.** `installBettercap`/`installMsf`/`installExploitdb` were the same download → extract → verify → cleanup skeleton ×3 (graph flagged them `SIMILAR_TO`); now share one `installTarballArtifact()` helper. Behaviors preserved (bettercap marks its binary executable; the others do not; per-title error strings preserved).
- [x] **T6 — Exploit Finder gating bug.** `exploit_finder` required the dead `msf` registry key (handler `msf` is not in the daemon's handler set), so the card stayed dim forever. Requires `shell` (what the screen actually executes `msfconsole` through); the existing `isMsfInstalled()` gate now controls it.
- [x] **T7 — `SettingsScreen` split, OTA OTA/terminal cards extracted** (`CoreCard.kt`, `UpdateCard.kt` in `indra/ui/screens/settings/`) extracting the 901-line god file one step.
- [x] **T8 — Unit tests for OTA-critical pure logic** (`OtaUpdateManager` envelope/version logic where pure) + registry/quoting tests; `data/` repositories untouched (no fake results left in the tree).
- [x] **T9 — `AGENTS.md` refresh** (5-module layout, SDK 35, real tool list, detekt, new gotchas).
- [x] **T10 — Extend detekt to all five modules.** Shared root `detekt.yml` (moved from `indra/`); `io.gitlab.arturbosch.detekt` applied in `core`, `data`, `ui`, `features`, `indra`; per-module `detekt-baseline.xml` generated (`./gradlew detektBaseline`); `./gradlew detekt` is green across the repo. Note: pre-existing state had `:indra:detekt` failing with 24 unsuppressed issues (its baseline file was never wired in) — that is fixed. detekt is intentionally not attached to `assembleDebug`; run it (or `check`) in CI.

## Phase 2.6 — Deep audit remediation (2026-10-26) 🔄 SPRINT 1 DONE + DEVICE-VERIFIED

Full-tree audit (Kotlin 18.8k LOC + 8.4k LOC native C, graph gen `2026-10-26`). The
execution plan — workstreams, acceptance criteria, verification steps, sprint order and
the ordered TODO — lives in **[`docs/plan/2026-10-26-audit-remediation.md`](docs/plan/2026-10-26-audit-remediation.md)**.

Headline items (do not duplicate detail here):

- [x] **WS-1 (P0)** Done and **device-verified (2026-10-27, moto g60)**: `SmartToolBox.requireTool()` + sealed `ToolHandle` replaced all 27 Main-thread `isAvailable()` gates (26 plugin VMs + Terminal), `TtlProbe` rate-limits the JNI probe to 1 s, `startDaemon()`/`stopDaemon()` are bounded at 20 s with `destroyForcibly()`, `Logger` writes through a drop-oldest channel off the JNI thread, and debug builds install StrictMode. The sweep found 13 cold-start violations the code review missed (prefs, log-file probe, crash-log sweep, `System.loadLibrary`, `filesDir`) — all moved to an IO `startupScope`, re-measured at **0** across cold start, a tab sweep, the gate path, daemon start/stop and a 10 000-line Terminal run.
- [x] **Honest card/gate states** (sweep finding): `PluginAvailability.PluginStatus` names the real blocker instead of one "not in native core" label; a down daemon keeps its card openable so the screen's gate can explain the fix; `SmartToolBox.isDaemonConnected()` + `IndraClient.startFailed` catch a daemon that died under a running app (the native `connected` flag never clears).
- [ ] **WS-2 (P1)** `VulnScanner` / `WebTester` / `ExploitFinder` collapse a whole transcript into one 500-char "finding"; `Topology` has no data source at all.
- [ ] **WS-3 (P1)** 36 hand-rolled `Scaffold`+`TopAppBar`s and 14 copy-pasted `refreshSuggestions()`; the 6 shared `ui` components have **0** call sites.
- [x] **WS-4 (P0)** Done (`4.1`): the app is deliberately dark-only, and the window theme now agrees — dark `android:Theme.Material.NoActionBar` with `windowLightStatusBar=false`, `values-night` theme/splash deleted, `SystemBarStyle.dark(TRANSPARENT)` on both bars. Verified with the system in light mode.
- [ ] **WS-5 (P1)** Suggestion chips in a non-scrollable `Row` ×14 over a never-shrinking singleton host list; zero `rememberSaveable`/`SavedStateHandle`.
- [ ] **WS-6/9 (P2)** Dead API/DI scope/models, 5 unused Gradle deps, 5 unused permissions, 2 parallel plugin registries, 173 hardcoded strings (dead `strings.xml`).
- [ ] **WS-7 (P1)** No foreground service for 30-minute tools (still open). Daemon auth is no longer a static `DEADBEEF`: a per-install random secret lives in app-private prefs, `<core>/users` holds only its SHA-256 (compared in constant time), and the socket is `chown`ed to the app uid, `chmod 0600` and additionally gated on `SO_PEERCRED` (2026-10-28).
- [ ] **WS-8 (P1)** Zero tests in `features` (4.5k LOC of ViewModels) and 389 detekt suppressions; no `testTag`s.
- [ ] **WS-10 (P2)** No adaptive layout (no `WindowSizeClass`) despite `resizeableActivity`, targetSdk 35.

Derived from the deep comparison with StrykerOSS (`/home/rana/strykerapp`).
Goal: make Indra a **better, easier-to-use pentest tool than cSploit ever was**, and competitive with StrykerOSS, while keeping its modern architecture and lightweight core.

Priorities: **P0** (blocks usability on modern devices / broken promises), **P1** (high-value features), **P2** (nice-to-have / parity), **P3** (polish).

---

## Phase 0 — Stabilize & Honest UI (P0) ✅ DONE (2026-10-23)

Completed:
- Fixed `Plugin.requires` mapping (mitm→arpspoof, network_radar→network-radar, packet_capture→tcpdump, packet_forger→hping3, wifi_audit→aircrack-ng).
- Registered SmartTools under real binary names (arpspoof, tcpdump, hping3, aircrack-ng, network-radar) using the raw/network-radar handlers.
- Added "Bundled Tools" status diagnostic card in Settings.

- [ ] **Principle: build every advertised tool for real.** Do not delete plugin cards to hide gaps — ship the binaries. Removal is only the last resort for tools genuinely infeasible on Android (see Phase 2 "infeasible" list). While a binary is still being built, the card shows "coming soon — not yet bundled" with a disabled action; never a fake result.
- [ ] **Fix the `Plugin.requires` → handler mapping.** Several plugins map to `raw` (mitm, network_radar, packet_capture, packet_forger, wifi_audit), which is always available, so they never dim even when their real binary is missing. Make `requires` reference the *actual* binary (`arpspoof`, `tcpdump`, `hping3`, `aircrack-ng`, …) and add those to `SmartToolBox` as named tools, or keep `raw` but gate on a binary-existence check via `CoreManager`.
  - Files: `core/system/PluginManager.kt`, `core/system/SmartToolBox.kt`, `core/system/CoreManager.kt`.
- [x] **Add a "Tools status" diagnostic** in Settings showing which bundled binaries exist, their version, and ABI, plus a one-tap "Install/Update core" (reuses `rawProbe` + `CoreManager`). Done — `ToolsStatusCard` in SettingsScreen.
- [x] **Bump target SDK** from 34 → 35 (compileSdk + targetSdk in all modules).

---

## Phase 1 — ABI: move to arm64-v8a (P0, biggest blocker) ✅ DONE (2026-10-23)

Attempted 2026-10-23: Application.mk + build.sh abis updated to include arm64-v8a; JNI libs (libIndraClient.so, libIndraCommon.so) built successfully for arm64. But `build.sh cores` fails on libpcap (`pcap-linux.o`): bionic FORTIFY_SOURCE rejects `strlcpy` (same class of issue as nmap, which already uses `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0`). Needs the same FORTIFY-disable applied to the libpcap module (and likely others), plus possibly api-level handling (arm64 needs android-21+). Reverted build.sh/Application.mk to armeabi-v7a-only to keep the working build intact. JNI libs reverted to v7-only.

Completed (both ABIs build now):
- libpcap: FORTIFY disable + HAVE_STRLCPY fix.
- libcurl: CURL_SIZEOF_LONG made conditional on __LP64__.
- libcares: CARES_SIZEOF_LONG made conditional on __LP64__.
- libpcre: disabled JIT (SUPPORT_JIT undef) and dropped pcre_jit_compile.c from build.
- known-issues/issue1.c: added <string.h> for strerror.
- pthread: removed -lpthread from icu4c/sqlite/bdb Android.mk (bionic provides it).
- thomson: excluded from root Android.mk (sha1 ARM assembly; not packaged).
- Application.mk + build.sh: both ABIs (armeabi-v7a + arm64-v8a).
- bundleCore (build.gradle): bundles core-arm64-v8a.tar.xz and core-armeabi-v7a.tar.xz.
- CoreManager: selects asset by Build.SUPPORTED_ABIS.

Both core tarballs produced (~5.4 MB v7, ~5.6 MB arm64). JNI libs built for both ABIs. App compiles and unit tests pass.

Current core is `armeabi-v7a` only. Most modern devices are 64-bit-only and won't install/run it.

- [x] **Add `arm64-v8a` to `Application.mk`** (`APP_ABI=armeabi-v7a arm64-v8a`) and rebuild the JNI libs (`libIndraClient.so`, `libIndraCommon.so`).
- [x] **Rebuild every bundled tool for arm64** (nmap, hydra, tcpdump, arpspoof, ettercap, hping3, aircrack-ng, network-radar, fusemounts). Add per-ABI output dirs under `jniLibs/arm64-v8a/` and `tools/<name>/`.
- [x] **Multi-ABI core tarballs**: `core-vX.Y.Z+android9.arm64-v8a.tar.xz` (+ armeabi-v7a kept for old devices). Update `OtaManifest`/`OtaAbi` to select by `Build.SUPPORTED_ABIS`.
- [x] **Update `CoreManager`** to pick the matching tarball from assets/OTA and verify SHA-256 per ABI.
- [x] **CI**: build both ABIs in `nightly-build.sh`; publish both.
- Acceptance: APK installs and daemon starts on a 64-bit-only device (e.g. Pixel); `isAvailable()` returns true for all handlers.

---

## Phase 2 — Build every advertised tool for real (P1) 🔄 IN PROGRESS

Completed so far:
- Nuclei: optional download flow (CoreManager.installNuclei with SHA-256 verify + zip extract), VulnScanner screen shows install button + progress, card availability gated on isNucleiInstalled(). SmartToolBox.nuclei() runs via raw handler at tools/nuclei/nuclei.
- Web Tester: rewritten to use Nuclei (sqli/xss templates) instead of sqlmap/nikto; card availability gated on nuclei installed.

Goal: ship a real binary for each plugin card. Removal is only the last resort, and only for tools that are genuinely infeasible on Android (see "Infeasible — drop only these").

### Already shipped (keep & maintain)
- [x] nmap, hydra, tcpdump, arpspoof, ettercap, hping3, aircrack-ng, network-radar, fusemounts, msfrpcd (raw).

### Build real (high priority)
- [ ] **Nuclei** (`vuln_scanner`): vendor a static arm64 build; wire `SmartToolBox.nuclei()` (already exists) to a real screen; bundle a minimal template set as an asset; add an OTA "update templates" action.
- [x] **Metasploit** (`exploit_finder`): optional chroot download flow (URL configurable in Settings, SHA-free tar.xz extraction into core/msf), console runs `chroot <msfdir> msfconsole` via shell handler. Card gated on isMsfInstalled().
- [x] **Nikto** (`web_tester`): replaced by nuclei-based web scanning (sqli/xss templates). Web Tester gated on `isNucleiInstalled()`. Done.
- [x] **Masscan**: cross-compiled for arm64+armv7 (patched getifaddrs/pthread_setaffinity_np/getservbyport_r), bundled in core, MasscanScreen + ViewModel.
- [x] **iptables**: kernel-dependent; shipped userspace binary gated behind `CoreManager.isIptablesSupported()` (kernel netfilter probe); honest error if the kernel lacks NETFILTER. Done.
- [x] **Bettercap**: cross-compiled for arm64+armv7 (CGO, libpcap 1.10.4, hid module disabled), bundled as optional download, BettercapScreen + ViewModel with install flow. Done.

### Build real (medium priority)
- [x] **Reaver** (`wifi_audit` WPS): cross-compiled for both ABIs (patched init.c to use pcap_open_live), bundled in core, ReaverScreen + ViewModel. Done.
- [x] **Wifite**: Kotlin orchestration (monitor mode → tcpdump capture → aircrack-ng crack) with risk warning. Done.
- [x] **Responder**: Kotlin LLMNR responder (UDP 5355 listener + spoofed A replies). Done.
- [ ] **Traceroute**: keep using `nmap --traceroute` (already done); no separate binary needed.
- [ ] **sqlmap**: requires Python 3 + large dep tree. Recommend: port the "test a single URL for SQLi" flow to a Kotlin HTTP fuzzer using nuclei's sqli templates, instead of shipping Python. Mark the card "SQLi scan (nuclei-based)".

### Infeasible — drop only these (genuinely can't run on Android)
- [ ] **msf** is heavy but feasible as optional download (not "infeasible" — keep as optional component).
- [ ] **sqlmap** Python runtime: infeasible in base core; replace with nuclei-based SQLi scanning instead of dropping the card.

### Registry & availability
- [ ] **Update `SmartToolBox` tool registry** so every shipped tool has a named entry (`nuclei`, `msf`, `nikto`, `masscan`, `iptables`, `reaver`, `wifite`) with `isAvailable()` reflecting the actual bundled binary via `CoreManager`.
- [ ] **Wire screens** to the real tools: Web Tester → nuclei http templates; Vuln Scanner → nuclei; Exploit Finder → msf console (optional component install prompt).
- Acceptance: every visible plugin card's `requires` binary ships (base or optional) and `isAvailable()` is true after install; no "not in native core" suffix in the default build.

---

## Phase 3 — Built-in terminal (P1) ✅ DONE (2026-10-23)

Implemented:
- `TerminalViewModel` + `TerminalScreen` (Compose): runs commands via `SmartToolBox.shell`, streams output into a scrollback LazyColumn, Stop/Clear, auto-scroll to latest line.
- Registered a "terminal" plugin (requires "shell") and switched the "shell" SmartTool to the `raw` handler so it reports available.
- Wired `Screen.Terminal` route + navigation mapping. Terminal is reachable from the Tools list.

Stryker's killer UX feature. Indra has a `shell` handler but no terminal screen.

- [x] **Terminal screen** (`TerminalScreen`/`TerminalViewModel`) rendering a scrollback `LazyColumn` of `ProcessEvent.Output/Error` lines, with an input field sending lines to a long-lived `shell` child.
- [ ] **Session management**: keep the shell pid alive across recomposition; kill on screen dispose (or keep running in background with a notification).
- [x] **Shortcuts**: quick-insert common commands (ip addr, iw, ps, ls) via AssistChip.
- [x] **Daemon cwd = core dir** so `./tools/...` paths work, matching `raw` handler behavior.
- Acceptance: user can run arbitrary shell commands and see live output; Ctrl+C (kill) works.

---

## Phase 4 — WiFi capture & monitor mode (P1/P2) 🔄 IN PROGRESS

Currently WiFi Audit needs an external pcap; capture needs monitor-mode dump.

- [ ] **Monitor-mode toggle** in Packet Capture / WiFi Audit: `iw dev <iface> set type monitor` via `raw`, with a warning dialog (moto g60 class risk — reuse the MITM risk dialog pattern).
- [x] **Monitor-mode probe**: `CoreManager.isMonitorModeSupported()` (read-only `iw list`) reports whether the chipset/kernel exposes monitor mode; surfaced so WiFi Audit can honestly say 'not supported on this device' (moto g60 does support it).\n- [ ] **airodump-ng**: not bundled in the base core — the osdep radio layer is a large native port and monitor mode needs a patched driver on most phones. Advanced users can cross-compile airodump-ng and drop it into `tools/airodump-ng/`; the `raw` handler runs any binary placed there. Terminal is provided for loading USB WiFi dongle drivers.
- [x] **Handshake storage**: list saved `.cap` files, delete, refresh, rename, share (via FileProvider), export manifest. Done.
- [x] **WiFi scanner**: list nearby APs via `iw dev scan`. One-tap capture TODO.
- Acceptance: end-to-end from UI: scan → capture handshake → crack with bundled wordlist → "Key found" (already works for pcap input).

---

## Phase 5 — Rootless mode (P2) ✅ DONE as a daemon-free subset (2026-10-31)

**The daemon cannot run without root.** `indrad` and the bundled tools live in app-private
storage, and since Android 10 SELinux denies `untrusted_app` the execute permission on
`app_data_file`, so a rootless start dies with exit 126 (`DaemonStartFailure` explains it).
Rootless is therefore *not* "run the daemon as the app user": it is the set of plugins that
have an implementation on platform APIs (`core/system/Rootless.kt`), and everything that needs
a bundled binary is gated as `NEEDS_ROOT`.

- [x] **Rootless subset**: Port Scanner (TCP connect scan, `PortScan` + `java.net.Socket`),
  WiFi Scanner (`WifiManager`), WhisperPair (BLE) and Password Auditor. `RootlessTest` pins the set.
- [x] **Settings toggle**: `rootlessMode` in SettingsRepository + SettingsViewModel + SettingsScreen switch.
- [x] **Card gating**: `PluginAvailability.pluginStatus` answers rootless before any daemon question —
  supported plugins are `AVAILABLE` with no daemon, every other plugin is `NEEDS_ROOT` (card disabled).
- [x] **Port Scanner rootless path**: range parsing (`1-1000`, `22,80,443`), bounded 128-way connect
  scan, live per-port output, zero-open-ports is an empty state not an error (`PortScanTest`).
- [x] **WiFi Scanner rootless path**: `WifiManager.startScan()` + scan table, location-permission and
  Wi-Fi-off errors name the fix; `WifiScan.describeAccessPoint` formats the same two-line rows.
- [ ] **Removed/stale claims**: `hasRawSocketCapability()` and `canRunRootless()` still have no
  callers; `Plugin.requiresRoot` is no longer what gates rootless (the daemon's absence is).
  The `[hint] This feature requires root…` line in `SmartToolBox` still fires in root mode.
- Acceptance: with no root and no daemon, a port scan finds a listening port and the Wi-Fi screen
  reports its real state. Device-verified 2026-10-31 (moto g60, `adb reverse tcp:9999` →
  `Discovered open port 9999/tcp`; Wi-Fi off → "Wi-Fi is turned off — enable Wi-Fi to scan for
  networks."). The Wi-Fi *listing* path is not device-verified: this phone is a hotspot, so
  enabling client Wi-Fi takes the hotspot down.
- **Not achievable without root** (and deliberately not attempted): SYN scans, packet capture,
  ARP spoof/MITM, monitor mode, hping3, iptables/DNS spoofing, HID, `cmd wifi`, the Alpine chroot.

---

## Phase 6 — HID / USB Arsenal (P3, niche) ✅ DONE (2026-10-23)

Only if gadget-capable kernel detected.

- [x] **Kernel capability probe** (`/sys/class/udc/`, configfs presence).
- [x] **DuckyScript parser + executor** (pure Kotlin) with full key injection (HID report descriptors).
- [x] **USB gadget profile manager** (configfs) for HID keyboard/mouse, mass storage, RNDIS/ECM (best-effort).
- Acceptance: gated behind capability check; clear "not supported on this device" message otherwise.

---

## Phase 7 — Architecture & quality (P2/P3) 🔄 IN PROGRESS

- [x] **Module split**: extracted `:core`, `:data`, `:ui`, `:features` (all plugin screens). App depends on all four.
- [ ] **Increase test coverage**: event pipeline pid-correlation, OTA rollback path, tar extraction traversal guard, NetworkRadar parsing (already tested).
- [x] **Instrumented test scaffold** added (androidTest source set, SmokeTest + NmapSmokeInstrumentedTest). End-to-end nmap run TODO (requires rooted device).
- [x] **Lint baseline** + `detekt` (1.23.8) wired in build.gradle with baseline file (532 issues baselined). ktlint deferred (too many style violations; config TBD).
- [x] **Documentation**: `docs/README.md` created with feature notes and gotchas. Per-feature READMEs TODO.

---

## Phase 8 — Parity features (P3)

- [x] MAC changer (randomize + restore via shell/ip link) with persistent profiles. Done.
- [x] GeoMac: OSM map screen (osmdroid) + KML/CSV export + share. Done.
- [x] Handshake manager: list/delete/refresh/rename/share (export TODO).
- [x] Session hijacking (cookie theft): parse captured HTTP traffic for cookies, display extracted sessions.
- [x] Password complexity auditor: local password strength analysis (entropy, patterns, dictionary).
- [x] One-tap MITM automation: single toggle auto ARP spoof.
- [x] DNS spoofing / redirect: UDP 53 listener + spoofed A replies.
- [x] Visual network topology map: Compose Canvas graph of hosts and connections. Done.
- [x] VNC desktop — WON'T DO (heavy Xorg/VNC stack conflicts with lightweight core; terminal covers CLI needs).
- [x] WhisperPair BLE — Fast Pair device discovery (BLE scan for 0xFE2C) + vulnerability check (GATT service probe). Exploit chain (RAW/RETROACTIVE/EXTENDED_RESPONSE), account-key write, HFP audio capture TODO (research).
- [ ] Metasploit console screen (if shipping msf).

---

## Cross-cutting constraints

- Keep the core **small** (< ~10 MB base). Heavy components (msf, python, nuclei templates) are **optional downloads**, never in the base tarball.
- Every new feature must degrade gracefully when its binary is absent (dim card + honest empty state) — no fake/simulated results (already a project rule).
- No arpspoof-class risks without an explicit warning + confirmation (moto g60 lesson).
- Reinstall core from app after `installDebug` (SELinux category randomization).

---

## Suggested execution order

1. Phase 0 (honesty + target SDK) — 1–2 days
2. Phase 1 (arm64) — 1–2 weeks (blocks everything else on modern devices)
3. Phase 2 (ship/drop tools) — 3–5 days
4. Phase 3 (terminal) — 3–5 days
5. Phase 4 (WiFi capture) — 1 week
6. Phase 5 (rootless) — 2–4 weeks
7. Phase 6–8 — opportunistic

---

## Success metrics

- APK installs and daemon starts on a 64-bit-only device.
- Zero "not in native core" cards in the default build.
- Every visible tool runs end-to-end on device with real output (no simulation).
- Test count ≥ 20 unit + 5 instrumented.
- Core tarball ≤ 10 MB per ABI for the base set.