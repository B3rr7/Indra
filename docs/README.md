# Indra — Documentation

Per-feature notes and gotchas. See `AGENTS.md` for the full project overview and `IMPROVEMENT_PLAN.md` for the roadmap.

Dated execution plans live in `docs/plan/` — newest first:

| Plan | Scope |
|---|---|
| [`plan/2026-10-26-audit-remediation.md`](plan/2026-10-26-audit-remediation.md) | Full-tree audit remediation: threading, honest results, UI consolidation, theming, tests, hygiene |

## Screens
- **Home** — network status hero (tappable → Network Radar), tool grid.
- **Tools** — plugin cards with availability badges + per-card OTA update.
- **Terminal** — run arbitrary shell commands via the `shell` handler.
- **Settings** — Native Core install/start/stop, ports, OTA, bundled-tools status, rootless toggle.

## Native core
- Bundled as `assets/core-<abi>.tar.xz` (arm64-v8a + armeabi-v7a).
- `CoreManager.installCore()` extracts to `filesDir`, sets exec bits, writes VERSION.
- Daemon runs as root via Magisk `su`. There is no app-user fallback: see Rootless mode below.

## Rootless mode
- Toggle in Settings → General. **There is no daemon in rootless mode**: `indrad` lives in
  app storage and Android 10+ denies `untrusted_app` execute on `app_data_file`, so a rootless
  start is a guaranteed exit 126. `CoreManager.startDaemon()` and `ensureDaemon()` therefore
  refuse outright in that mode instead of attempting the exec, and `NativeCoreState.Rootless`
  keeps the Settings card from offering a Start that cannot work.
- What works without root is the platform-API subset listed in `core/system/Rootless.kt`:
  Port Scanner (TCP connect scan via `java.net.Socket`, i.e. nmap's `-sT` equivalent),
  WiFi Scanner (`WifiManager`), WhisperPair (BLE), Password Auditor, and the Terminal.
- The Terminal in rootless mode runs `/system/bin/sh` as the app's own uid (`LocalShell`), so it
  gets toybox and the app's files — no bundled binary, no root, and no Alpine chroot (`chroot(2)`
  and `mount(2)` need `CAP_SYS_ADMIN`; `TerminalTransport` refuses the combination rather than
  silently running the command outside the guest).
- Everything that runs a bundled binary — MITM/ARP spoof, packet capture, monitor mode, the
  Alpine chroot, hydra/nuclei/gobuster, iptables, HID — is gated as `NEEDS_ROOT`.
- `RootlessTest` pins the supported set and checks every id against `PluginManager`'s registry;
  `PortScanTest` pins the range parser and the bounded connect scan.

## OTA
- Manifest fetched over pinned HTTPS, envelope verified (SHA256withRSA).
- Per-binary atomic swap with health check + rollback.