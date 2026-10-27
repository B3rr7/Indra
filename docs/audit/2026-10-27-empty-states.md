# Empty-state audit — all 36 plugin screens (2026-10-27)

Task `2.5` of [`docs/plan/2026-10-26-audit-remediation.md`](../plan/2026-10-26-audit-remediation.md).

The rule under audit is the plan's principle 2: a real **`0`** ("the tool ran and found
nothing") must never look like **`null`** ("the tool could not look"), and neither may look
like "not started yet". Each row records what the screen shows in each of those three cases
and, most importantly, **what the screen knows them from** — the source of truth.

Legend: **OK** = already honest; **fixed** = changed in `2.5`; **console** = a raw tool-output
list (every line *is* the tool's output), migrated in `3.3` onto the shared `LiveOutputCard` with
its state-aware placeholder.

| # | Screen | Before | After | Source of truth |
|---|---|---|---|---|
| 1 | Arsenal | templates list from local repo (never null); run output silent | OK | `repo.getAll()` is synchronous local storage — 0 templates is a real 0 |
| 2 | Bettercap | no output state | console | tool output lines only |
| 3 | Credential Tester | `No result reported by hydra` when the run printed nothing; `""` when it failed | OK | hydra's own output (a literal statement about hydra, not about the host) |
| 4 | DNS Spoofer | log list; nothing before start | console | event log lines |
| 5 | DuckyScript | `parse` failure is an error; no result list | OK | parse result |
| 6 | Exploit Finder | one fake 500-char "result" row | **fixed** (2.1/2.2) | `MsfSearch.parse` → `null` (no table) vs empty list (table, no rows) |
| 7 | Gadget Profiles | `if (profiles.isNotEmpty())`, silent on 0 | OK | configfs listing on the device; capability card reports the blocker |
| 8 | GeoMAC | no result list (input → action) | OK | n/a |
| 9 | Gobuster | `if (output.isNotEmpty())`, silent on 0 | console | tool output lines |
| 10 | Handshakes | `No capture files found. Use Packet Capture with "Save to capture file" …` | OK | real directory listing (0 files is a real 0, and the hint names the fix) |
| 11 | HID | "nothing typable" is an error; no result list | OK | `HidReports` mapping |
| 12 | Inspector | `osInfo = "No open ports found"` string hack; no OS-match state | **fixed** | `finished` + structured `Port`/`Os` events |
| 13 | IPTables | silent console | console | tool output lines |
| 14 | Login Cracker | same note as Credential Tester | OK | hydra output |
| 15 | MAC Changer | output console + saved profiles | console | tool output lines |
| 16 | Masscan | silent console | console | tool output lines |
| 17 | MITM | rolling log | console | tool output lines |
| 18 | Network Radar | `Hosts (0)` + `No hosts discovered yet`; failure surfaces in `note`/`error` | OK | kernel neighbour table (`ip neigh`): a real 0 is a real 0; a failed probe sets `error` |
| 19 | One-Tap MITM | rolling log | console | tool output lines |
| 20 | Packet Capture | `Captured (0 lines)` before Start | **fixed** | `hasRun` |
| 21 | Packet Forger | `result: String`, silent when empty | console | hping3 output |
| 22 | Password Auditor | `result: PasswordAuditResult?` — `null` until the audit runs | OK | the model already separates "not audited" from a score |
| 23 | Port Scanner | zero ports set as a red `error` | **fixed** | `finished` + `Port` events / nmap parse |
| 24 | Reaver | silent console | console | tool output lines |
| 25 | Responder | rolling log | console | tool output lines |
| 26 | RouterPwn | `Found N open services` (red) only when non-empty; `Result: Scan complete: N open ports` states the 0 | OK | nmap `Port` events |
| 27 | SearchSploit | silent console | console | tool output lines |
| 28 | Session Hijack | `Captured sessions: 0` before any run | **fixed** | `hasRun` + Cookie-header parse |
| 29 | Topology | empty canvas, no data source at all | **2.4** | — (screen cannot draw anything; owned by task 2.4) |
| 30 | Traceroute | zero hops set as a red `error` | **fixed** | `finished` + hop-line parse |
| 31 | Vulnerability Scanner | one fake result row; exit 1 read as failure | **fixed** (2.1/2.2) | `Nuclei.interpret` / `Nuclei.parseFindings` |
| 32 | Web Tester | same as 31 | **fixed** (2.1/2.2) | same |
| 33 | Whisper Pair | `status: String` ("Idle" → scan status) + device list | OK | BLE scan callback; a permission denial is an `error` |
| 34 | WiFi Audit | `Cracking (N lines)` only when lines exist; `keyFound` header | OK | aircrack output (`KEY FOUND`) |
| 35 | WiFi Scanner | zero-result scan rendered nothing | **fixed** | `finished` + `BSS` line parse |
| 36 | Wifite | `stage` machine (`idle → monitor → capture → crack → done`) + output | OK | stage transitions; a failed stage sets `error` |

## Notes

- **RouterPwn's wording is the one remaining soft spot**: `Found N open services` is rendered in
  the error colour with the plugin named "RouterPwn", although open ports are not
  vulnerabilities. The zero case is stated honestly in `Result: Scan complete: 0 open ports`, so
  it does not conflate 0 with null — the copy is a naming issue for the UX pass, not a
  falsified result.
- **Topology (task 2.4)** still has no data source: `setGraph`/`addNode`/`addEdge` have no
  caller, so the canvas is always empty while the screen says "Add nodes via the Network Radar /
  Port Scanner results". Nothing adds nodes. Task 2.4 either wires it to
  `NetworkRepository.discoveredHosts` + the last scan's ports or removes the card.
- The **console rows** are closed: every one of those lists now renders through
  `LiveOutputCard` (shared header with `· running`, Copy, auto-follow, capped height), so they look
  and behave the same. The placeholder states what the screen actually knows — "No output yet"
  where there is no run state to report, and the real four-state wording where there is
  (`WiFi Scanner`, `Packet Capture`, `WiFi Audit`, `Wifite`, `Port Scanner`). For the remaining
  console tools an empty transcript is not ambiguous in practice: masscan, gobuster, iptables,
  bettercap, reaver, responder, macchanger and arsenal all print a banner or a summary on every
  run, and a tool that printed nothing and exited non-zero already shows `FormError`. A
  dedicated `finished` flag per console VM was deliberately not added: for those tools the flag
  could only ever read "ran", so it would be state nobody renders.
- Every **fixed** row keeps its tool's error path intact: a failed run still renders
  `FormError`, and only a *completed* run with no results reaches the neutral empty state.
- **Device pass (moto g60)**: Port Scanner (`No open ports in 9000-9010` on 127.0.0.1),
  Traceroute (`No hops reported` on the gateway), Inspector (`No open ports found` on
  10.140.58.1), Session Hijack (`Not capturing yet — press Start.`), Packet Capture
  (`Not capturing yet` + `Press Start to begin capturing`). WiFi Scanner rendered
  `The scan did not complete` + `sh: iw: inaccessible or not found` — see the tool finding
  below; its zero branch (`No networks found`) is unreachable on this device.
- **New finding: WiFi Scanner depends on a binary nobody ships.** It runs
  `iw dev <iface> scan` through `shell`, but `iw` is in neither the native core
  (`aircrack-ng arpspoof ettercap fusemounts gobuster hping3 hydra masscan network-radar nmap
  reaver tcpdump`) nor the ROM, so every scan fails with exit 127 while the card stays openable
  (`requires = "shell"` is satisfied). Recorded in the plan's Sprint 2 findings; it is a
  Phase 2 "ship the binary" item, not an empty-state one.
