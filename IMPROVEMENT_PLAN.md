# Indra Improvement Plan

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
- [ ] **Add a "Tools status" diagnostic** in Settings showing which bundled binaries exist, their version, and ABI, plus a one-tap "Install/Update core" (reuses `rawProbe` + `CoreManager`).
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

- [ ] **Add `arm64-v8a` to `Application.mk`** (`APP_ABI=armeabi-v7a arm64-v8a`) and rebuild the JNI libs (`libIndraClient.so`, `libIndraCommon.so`).
- [ ] **Rebuild every bundled tool for arm64** (nmap, hydra, tcpdump, arpspoof, ettercap, hping3, aircrack-ng, network-radar, fusemounts). Add per-ABI output dirs under `jniLibs/arm64-v8a/` and `tools/<name>/`.
- [ ] **Multi-ABI core tarballs**: `core-vX.Y.Z+android9.arm64-v8a.tar.xz` (+ armeabi-v7a kept for old devices). Update `OtaManifest`/`OtaAbi` to select by `Build.SUPPORTED_ABIS`.
- [ ] **Update `CoreManager`** to pick the matching tarball from assets/OTA and verify SHA-256 per ABI.
- [ ] **CI**: build both ABIs in `nightly-build.sh`; publish both.
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
- [ ] **Nikto** (`web_tester` alternative): pure Perl, small. Vendor perl + nikto; or replace the Web Tester screen with nuclei-based web scanning (recommended: reuse nuclei with http templates).
- [x] **Masscan**: cross-compiled for arm64+armv7 (patched getifaddrs/pthread_setaffinity_np/getservbyport_r), bundled in core, MasscanScreen + ViewModel.
- [ ] **iptables**: kernel-dependent; ship the userspace binary but gate execution behind a "iptables supported by kernel" check; honest error if the kernel lacks NETFILTER.
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
- [ ] **airodump-class capture**: bundle `airodump-ng` (or use tcpdump in monitor mode) to produce handshakes; save to `captures/`.
- [x] **Handshake storage**: list saved `.cap` files, delete, refresh, rename, share (via FileProvider), export manifest. Done.
- [x] **WiFi scanner**: list nearby APs via `iw dev scan`. One-tap capture TODO.
- Acceptance: end-to-end from UI: scan → capture handshake → crack with bundled wordlist → "Key found" (already works for pcap input).

---

## Phase 5 — Rootless mode (P2) 🔄 IN PROGRESS (core scaffold done)

Indra's tools are already native Android binaries (NDK/bionic), so no chroot/QEMU/rootfs needed (unlike Stryker). Rootless = run daemon as app user + disable root-only features.

- [x] **RootManager extension**: `canRunRootless()` + `hasRawSocketCapability()` added.
- [x] **Settings toggle**: `rootlessMode` in SettingsRepository + SettingsViewModel + SettingsScreen switch.
- [x] **CoreManager.startDaemon()**: runs daemon directly as app user when rootless (no su); handles stale socket removal.
- [x] **Capability gating**: disable raw-socket features (MITM, packet capture, ARP spoof, WiFi audit, monitor mode, packet forger, network radar) when rootless via `requiresRoot` flag on Plugin + availability gating in Home/Tools ViewModels.
- [x] **TCP-connect fallback**: nmap uses `-sT` when rootless mode is on.
- [x] **Graceful EPERM handling**: appends a hint when "Permission denied"/"Operation not permitted"/"EPERM" detected in tool output.
- Acceptance: app functions (nmap TCP scan, hydra, nuclei, masscan connect) on a non-rooted device.

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