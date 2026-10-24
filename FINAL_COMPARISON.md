# Final Comparison: Indra vs. StrykerOSS (after improvement work)

Date: 2026-10-23

## 1. Origins
- **Indra**: cSploit descendant (evilsocket, 2012). Native `indrad` daemon + bundled tools. Android app rebuilt in Kotlin/Compose.
- **StrykerOSS**: independent (zalexdev, 2021). Debian chroot + rootless QEMU VM. v6.5.1.

## 2. Stack
| | Indra | Stryker |
|---|---|---|
| Language | Kotlin | Java |
| UI | Compose + Material 3 | XML views |
| DI | Hilt | none |
| Async | Coroutines/Flow | threads |
| Min/Target SDK | 26 / 34 | 24 / 37 |
| ABI | armeabi-v7a + arm64-v8a | arm64-v8a only |
| Native | NDK 25.2 (indrad + tools) | NDK 25.1 (thin protected.c) |

## 3. Native runtime
- **Indra**: lightweight core (~5.4MB v7 / ~5.6MB arm64), 14+ bundled tools, OTA binary hot-swap with rollback, app-private storage.
- **Stryker**: Debian chroot (~240MB), rootless QEMU fallback, VNC desktop, USB gadget (HID).

## 4. Feature parity (after this work)
| Feature | Indra | Stryker |
|---|---|---|
| Port scan (nmap) | ✅ | ✅ |
| Masscan | ✅ (new) | ❓ |
| Host discovery / OS fingerprint | ✅ | ✅ |
| Traceroute | ✅ | via tools |
| Network Radar | ✅ | ✅ |
| Packet capture (tcpdump) | ✅ (+ save to .cap) | ❓ |
| Packet forge (hping3) | ✅ | ❓ |
| MITM/ARP spoof | ✅ (risk dialog) | ❓ |
| Credential brute-force (hydra) | ✅ | ✅ |
| Nuclei web scan | ✅ (optional download) | ✅ |
| Web Tester (nuclei sqli/xss) | ✅ | ❓ |
| Metasploit | ✅ (optional chroot) | ✅ full console |
| SearchSploit | ❌ | ✅ |
| Custom Arsenal | ❌ | ✅ |
| RouterPwn | plugin (nmap) | ❓ |
| WiFi audit (aircrack-ng) | ✅ | ✅ |
| Monitor mode toggle | ✅ (risk dialog) | ✅ |
| WiFi scanner | ✅ (new) | ✅ |
| MAC changer | ✅ (new) | ✅ |
| Handshake storage | ❌ | ✅ |
| GeoMac | ❌ | ✅ |
| HID Attacks (DuckyScript) | ❌ | ✅ |
| USB Arsenal (gadget) | ❌ | ✅ |
| VNC desktop | ❌ | ✅ |
| WhisperPair (BLE) | ❌ | ✅ |
| Built-in terminal | ✅ (new) | ✅ |
| IPTables | ✅ (new) | via tools |
| Rootless operation | ❌ | ✅ |
| OTA updates | ✅ (rollback) | ✅ (chroot) |
| Crash reporting | local CrashReporter | ❓ |
| i18n | ✅ (many locales) | ❓ |

## 5. Code quality
- **Indra**: MVVM + Hilt + Compose + Flow; unit tests (5+); zero compiler warnings; clean event pipeline with pid correlation; traversal-guarded extraction.
- **Stryker**: Activity-centric Java; Core.java god-class (1643 lines); no DI/MVVM; 1 test file; monolithic.

## 6. What Indra now has that Stryker doesn't
- Modern Compose UI with dynamic color and adaptive layout.
- OTA binary hot-swap with health-check rollback.
- Lightweight core (~5MB vs ~240MB).
- Clean plugin availability model (cards dim when binary missing).
- Built-in terminal + MAC changer + IPTables + Masscan + WiFi scanner + monitor-mode toggle.
- Honest "coming soon" UX (no fake results).

## 7. What Stryker still has that Indra doesn't
- Rootless QEMU fallback.
- HID Attacks, USB Arsenal, VNC desktop, WhisperPair.
- Handshake storage/share/export, GeoMac.
- SearchSploit, custom Arsenal, Metasploit full console (Indra's is chroot-based optional).
- arm64-only (Indra now supports both).

## 8. Verdict
Indra has closed the gap significantly: it now supports both ABIs, ships a real toolset (nmap, hydra, tcpdump, arpspoof, hping3, aircrack-ng, nuclei, masscan, terminal, MAC changer, IPTables, WiFi scanner), has OTA rollback, and a far more maintainable codebase. Stryker remains broader in scope (rootless, HID, USB, VNC, WhisperPair, handshake management) but is heavier and harder to extend.

Indra's best remaining wins to surpass Stryker: rootless mode, handshake storage, and a built-in terminal (already done). The biggest architectural risk is the 32-bit legacy; now mitigated by arm64 support.