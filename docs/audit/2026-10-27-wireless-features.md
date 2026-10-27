# Wireless feature audit — 2026-10-27 (moto g60 / Android 16)

Every plugin in `PluginManager` that touches Wi-Fi, BLE or the device's network
interfaces was opened on the device and exercised as far as it can honestly go.
"Works" below means *observed on this phone*, not "the code looks right".

## Feature table

| Plugin | What it drives | Status | Evidence |
|---|---|---|---|
| WiFi Scanner | `cmd wifi status; start-scan; list-scan-results` | **works** | listed 2 live APs (OPPO `06:6d:a9:76:96:83` ch10 −57 dBm WPA2/WPA3; open `1+` −85 dBm); Wi-Fi-off path shows the fixable error |
| WiFi Audit | `aircrack-ng` on a `.cap` + wordlist | **works** (needs a capture file) | aircrack-ng 1.7 runs through the daemon; earlier end-to-end run cracked `test/wpa.cap` (`KEY FOUND! [ biscotte ]`) |
| Handshakes | file listing of `<filesDir>/captures` | **works** | empty state: "No capture files found. Use Packet Capture with "Save to capture file" to create one." |
| Packet Capture | `tcpdump` | **works** (managed mode) | captured 124 real lines on `rmnet_data2`, header `Packets · running` → `Captured (124 lines)`; monitor toggle is gated, see F1/F3 |
| Network Radar | bundled `network-radar` + kernel neighbour table | **works, honest on failure** | `rmnet_data2`: sweep exits 1 (no L2 neighbours on mobile data) → note "Hosts from the kernel ARP table — active sweep failed: network-radar exited with code 1", `Hosts (0)` |
| Session Hijacking | `tcpdump` + Cookie-header parse | **works** | `Capturing…` → `Listening for Cookie headers on rmnet_data2…`; after Stop: "No sessions captured" + why |
| DNS Spoofing | Kotlin UDP:5353 + `iptables` REDIRECT | **works** (full lifecycle) | `Listening on UDP 5353 for test.local -> 127.0.0.1`; `Redirect UDP/53` installed `-A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5353` (verified with `iptables -t nat -S`); Remove deleted it; `DNS redirect removed` |
| MAC Changer | `ip link set <if> address` via `shell` | **works** | Randomize → valid locally-administered MAC (`f2:53:7c:be:a6:17`); Apply on a bad interface → console + `FormError`: `Cannot find device "nosuchif0"` |
| GeoMac | pure Kotlin KML/CSV export | **works** | added a marker, Export KML wrote a valid `<Placemark>` and opened the share sheet |
| WhisperPair | Android BLE scan (`0xFE2C`) | **works** (discovery only) | BT off → "Bluetooth is disabled"; BT on → scan runs, `Scanning…` (no Fast Pair device was in range) |
| Bettercap | optional 67 MB artifact | **not installed** | screen says so and offers the installer; `ls ./bettercap/bettercap` → `NO-BETTERCAP` in the daemon |
| Wifite | monitor → `tcpdump` → `aircrack-ng` | **blocked** (F1/F3) | stage 1 fails honestly, stage stays `idle` |
| Packet Capture (monitor) | `ip`/`iw` mode switch | **blocked** (F1/F3) | toggle reverts + explains; the link is restored |
| Reaver (WPS) | `reaver` 1.6.6 | **blocked** (F4) | binary runs (`Reaver v1.6.6 …`), but it needs an interface already in monitor mode |
| MITM / One-Tap MITM | `arpspoof` | **not run — unsafe on this device** | 3/3 hard resets on the moto g60 (documented); the app refuses to target its own IP |

## Findings

### F1 — monitor mode left the interface down (fixed)
The chain was

```
ip link set <if> down && iw dev <if> set type monitor && ip link set <if> up
```

`iw` is not shipped (F3), so the chain stopped *after* the link was brought down and the
restore never ran. Verified on the device against `dummy0`: `UP` → `DOWN`, exit 127. On `wlan0`
that takes Wi-Fi off the air until something else sets the link up.

Fixed in `core/system/MonitorMode.kt` (pure, 8 tests in `MonitorModeTest`): the script uses `;`
and always restores the link, keeping `iw`'s exit status so a missing binary is still a reported
failure. Wired into `PacketCaptureViewModel.switchMode` and `WifiteViewModel.run`.

Device-verified: `dummy0` forced `DOWN`, monitor toggle attempted, `dummy0` back to `UP`, and both
screens show "Monitor mode needs `iw`, which the native core does not ship — the interface was
left in its previous mode."

### F2 — WhisperPair kept a stale error (fixed)
`startScan()` set `isScanning = true` but never cleared `error`, so after enabling Bluetooth the
screen read `Scanning…` **and** `Bluetooth is disabled`. Fixed (`error = null`), verified on the
device.

### F3 — `iw` is in neither the core nor the ROM (open)
Core tools: aircrack-ng, arpspoof, ettercap, fusemounts, gobuster, hping3, hydra, masscan,
network-radar, nmap, reaver, tcpdump. `command -v iw` in the daemon → nothing; `airmon-ng` too.
Monitor mode therefore cannot be reached at all, which is what blocks Wifite's stage 1 and Packet
Capture's handshake capture. Two honest options:

1. **Ship `iw`** — vendor `libnl` (absent from `indra/jni`) and build the ~1 MB `iw` binary into
   the core. Feature becomes real, subject to the vendor driver actually allowing the mode change
   (unproven on this device, and the mode change is the documented crash path).
2. **Gate the two screens** — keep the honest message and drop the monitor-mode affordance until
   `iw` ships.

### F4 — Reaver has no monitor-mode precondition (open)
The screen labels the field "Interface (monitor mode)" and starts `reaver` regardless. With F3,
no interface can be in monitor mode, so the run always fails inside reaver. It should either
explain that (same message as F1) or use the shared `MonitorMode` helper first.

### F5 — this phone has no associated Wi-Fi
`wlan0` exists but holds no address; the device is on `rmnet_data2` (`10.161.185.30/30`). Every
Wi-Fi-dependent check above therefore ran on mobile data, `dummy0`, or Android's own scan table.
Joining an AP is what the OPPO hotspot refused (`CTRL-EVENT-ASSOC-REJECT … status_code=1
timeout=auth` — MAC filter / device limit, not a password problem).

### F6 — Bettercap needs the artifact
`bettercap` is a ~67 MB optional download; until it is installed the card opens the installer and
`Run` cannot work. Not exercised.

### F7 — three ViewModels read `filesDir` on Main (open, pre-existing)
Opening Wifite (and, by the same code shape, WiFi Audit and Packet Capture) logs one StrictMode
violation:

```
DiskReadViolation ~duration=69 ms
  at android.app.ContextImpl.getFilesDir
  at org.indra.android.core.system.SmartToolBox.defaultWordlist(SmartToolBox.kt:463)
  at org.indra.android.ui.screens.plugins.WifiteViewModel.<init>(WifiteViewModel.kt:37)
```

`defaultWordlist()`, `defaultDirWordlist()` and `capturePath()` all reach `context.filesDir`, and
the three ViewModels call them from their constructors / `init`, which run on Main. Not caused by
this audit (the lines are unchanged), but it breaks the project's "nothing touches disk on Main"
rule, so it is recorded here rather than left silent. The systemic fix is to resolve the three
paths once in `IndraApplication.startupScope` (already IO) and have `SmartToolBox` serve cached
strings.

## Not exercised, on purpose

- **MITM / One-Tap MITM**: `arpspoof` hard-resets this phone (3/3 runs). The screens were opened
  and read; Start was never pressed.
- **Wi-Fi attacks against networks the operator does not own**: out of scope.
