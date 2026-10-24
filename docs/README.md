# Indra — Documentation

Per-feature notes and gotchas. See `AGENTS.md` for the full project overview and `IMPROVEMENT_PLAN.md` for the roadmap.

## Screens
- **Home** — network status hero (tappable → Network Radar), tool grid.
- **Tools** — plugin cards with availability badges + per-card OTA update.
- **Terminal** — run arbitrary shell commands via the `shell` handler.
- **Settings** — Native Core install/start/stop, ports, OTA, bundled-tools status, rootless toggle.

## Native core
- Bundled as `assets/core-<abi>.tar.xz` (arm64-v8a + armeabi-v7a).
- `CoreManager.installCore()` extracts to `filesDir`, sets exec bits, writes VERSION.
- Daemon runs as root via Magisk `su` (or as app user in rootless mode).

## Rootless mode
- Toggle in Settings → Native Core. Runs `indrad` as app user (no `su`).
- Disables raw-socket features (MITM, packet capture, ARP spoof, WiFi audit, monitor mode, packet forger, network radar).
- nmap uses `-sT` (TCP connect) instead of SYN scan.

## OTA
- Manifest fetched over pinned HTTPS, envelope verified (SHA256withRSA).
- Per-binary atomic swap with health check + rollback.