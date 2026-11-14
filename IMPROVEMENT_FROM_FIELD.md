> **SUPERSEDED — this file framed the work as *porting* competitor code.**
> The rule is: learn the principle, build our own. Use [OWN_TECHNOLOGY_PLAN.md](OWN_TECHNOLOGY_PLAN.md).

# IMPROVEMENT PLAN — what to take from the field, and how

Derived from the verified comparison. Every claim below is traced to a file in one of the five repos.
Ordered by value per unit of work, not by competitor.

---

## 0. The one finding that changes the roadmap

**Indra's handshake→crack path is blocked by a dependency it does not need.**

Current state:

| Fact | Source |
|---|---|
| `SmartToolBox.crackHandshake()` is 100% chroot + hashcat | `core/.../SmartToolBox.kt:657-682` |
| `Hashcat.MODE_WPA = 22000` — it consumes the converted format | `core/.../engine/Hashcat.kt:18` |
| `Capability.HCXTOOLS` is documented **permanent "not available"** | `core/.../engine/IndraEngine.kt` (KDoc on `HCXTOOLS`) |
| Alpine packages neither hcxtools nor pocl for **any** arch | same KDoc |

So Indra can capture a handshake and cannot crack it without a binary that does not exist for its rootfs.

**WIFI-Frankenstein does the whole thing in Kotlin.** No hcxtools, no hashcat for the core path:

| File | Lines | What it is |
|---|---|---|
| `util/WpaCrypto.kt` | **190** | `sha1`, `hmacSha1/MD5/SHA256`, `pbkdf2Sha1`, `sha1Prf`, `sha256Prf`, `aes128Cmac`, hex utils — pure JVM, **zero deps** |
| `util/HandshakeParser.kt` | **768** | `parse22000Line`, `parseHccapx`, `parseHccap`, `parsePmkidText`, `parseAny`, `verifyPassword`, `dedupKey`; serialisers `to22000Line` / `to16800Line` / `toPmkidLine` / `toHccapxBytes` / `toHccapBytes` |
| `util/NativeCracker.kt` | 122 | JNI batch PBKDF2 **with a self-test against the JVM implementation and automatic fallback** |
| `app/src/main/cpp/cracker.c` | 1,104 | the native accelerator |

That is a complete, working, open-source reference implementation of exactly the gap.

### What to port (and what to leave)

| Port | Leave |
|---|---|
| `WpaCrypto.kt` → `core/.../system/wifi/WpaCrypto.kt` | `NativeCracker.kt` + `cracker.c` — Indra deliberately has **no JNI** (`KotlinDaemonClient` replaced it); adding a `.so` back reverses a whole architectural decision |
| `HandshakeParser.kt` (parser + serialisers + `verifyPassword`) → `core/.../system/wifi/HandshakeParser.kt` | `NativePskBruteForceRunner` — it brute-forces by *connecting* to the AP, which drops the link on every attempt; wrong shape for a pentest tool |
| the PBKDF2/MIC verify loop, run on `Dispatchers.Default` with a bounded worker pool | |

**Result:** `crackHandshake` gains an in-app path that needs no engine at all:
`capture → parse handshake → for each wordlist entry: PMK = PBKDF2-SHA1(pass, ssid, 4096, 32) → MIC check → key`.
aircrack-ng stays the fast path for large lists.

**Honest throughput:** PBKDF2-HMAC-SHA1 at 4096 iterations on a phone JVM is roughly
1–2 k guesses/s per core. A 10 k wordlist is ~5–10 s; a 100 M list is not this path's job.
That is the correct division of labour: Kotlin for small/medium lists and for **wiring the
pipeline that currently dead-ends**, aircrack-ng for bulk.

**Tests this buys:** the pure parser is trivially testable — `parse22000Line` on a known line,
`verifyPassword("biscotte")` on the bundled `wpa.cap`, round-trip `to22000Line ∘ parse22000Line`,
rejection of zero-PMKIDs. This is the highest test-per-line-value code available in the field.

---

## 1. From Strix — the Metasploit workflow

Indra's MSF is `MsfConsoleViewModel` with `run()` and a free-text command; `ExploitFinderViewModel`
has `search()` and nothing else. Strix has 26 files doing the full flow.

| Strix file | Lines-worth of idea | Port to Indra as |
|---|---|---|
| `ui/msf/ExploitCommand.kt` | `buildExploitCommand(module, state)` → `use X / set KEY VAL / set TARGET n / exploit -j` | `core/.../msf/MsfCommand.kt` (pure, testable) |
| `ui/msf/ExploitPayloadPicker.kt` | `pickBestPayload(list, useReverse)` — real ranking, not first-match | same file |
| `ui/msf/ExploitOptionFields.kt` | renders the module's option schema as typed fields | Compose in `features/.../plugins/` |
| `ui/msf/ExploitConfigPanel.kt` | selected-module panel | `ExploitFinderScreen` second state |
| `ui/msf/MsfSessionCards.kt` | live session list, tap → shell | new `SessionsSection` |
| `data/MsfSessionNotifier.kt` | one `session.list` poll → notification per new id | `core/.../msf/MsfSessionWatcher.kt` |
| `data/MsfDaemon.kt` | **generation counter** so stale poll/teardown can't clobber a restart; `beginMaintenance()` for hot-swap | fix `MsfLaunch`'s per-search `probe()` |

The generation-counter idea is the structural lesson: Indra's `MsfLaunch.probe()` runs on every
search and has no way to know a daemon it is talking to has been replaced. That is the bug class
Strix already solved.

**Indra already has what Strix had to build:** `MsfRpcClient`, its own `MsgPack` codec (15 tests),
`MsfLaunch`, `CapabilityFix`. The missing piece is the **UI + command-construction layer**, which is
also the easiest part to port because it is pure Kotlin.

---

## 2. From Strix — the supply chain

Indra's native build is an EOL C tree with ~40 in-place patches and no verification of anything it
downloads. Strix's is the field benchmark.

| Strix asset | What it does | Indra equivalent today |
|---|---|---|
| `versions.env` | one file: every upstream version **+ SHA256** | versions scattered in `build.sh` |
| `scripts/verify-sig.sh` | `gpgv` against `keys/*.asc` with **pinned fingerprints**; fails on BADSIG, unknown signer, expired key made after expiry | none |
| `scripts/fetch.sh` | download + hash-check + signature-check in one gate | `curl`-and-hope |
| `scripts/publish-bundle-release.sh`, `sign-bundle.sh` | produce and sign the release | `BundleInstaller` verifies ECDSA, but **nothing publishes** |
| `scripts/tar_safe_extract.py` | path-traversal-safe extraction | `SafeTarExtractor` — already equivalent ✔ |
| `tools/patches/*.patch` | patches as reviewable artifacts | inline `sed` in `build.sh` |
| `.github/workflows/update-bundles.yml` | `cron '0 6 * * 5'` — weekly upstream check | none |
| `.github/workflows/build-apk.yml` | tag-triggered signed APK | none |
| `.github/workflows/ci.yml` | PR gate | none |

**Take:** `versions.env` + `verify-sig.sh` + three workflows (`ci.yml`, `build-apk.yml`,
`update-bundles.yml`). **Skip** the Docker buildx pipeline — it assumes a clean upstream tree, and
Indra's advantage is that the EOL tree already *works*; what it lacks is a reproducible gate around
it.

Trust model note: Indra's ECDSA envelope is fine and already tested (`SignedEnvelope`, `OtaModelsTest`).
The gap is not verification of *Indra's own* artifacts — it is verification of *upstream source*,
which Indra does not do at all.

---

## 3. From StrykerOSS — USB/chipset detection (turns monitor mode from dead end into diagnosis)

Indra's monitor mode is blocked by the moto g60's qcacld driver, and the honest answer today is
"this phone cannot". StrykerOSS's `netdetect` (1,654 lines across 15 files) classifies exactly that:

| StrykerOSS file | Idea |
|---|---|
| `netdetect/UsbProbe.java` | enumerate `/sys/bus/usb/devices`, read vid/pid |
| `netdetect/NetClassifier.java` | decide `EXTERNAL_USB` / `INTERNAL_PCIE` / `INTERNAL_SDIO` from subsystem + driver symlink |
| `netdetect/ChipsetDb.java` | vid/pid → chipset name → known driver capability |
| `netdetect/DriverState.java` | is the driver bound, is it `qcacld`/`ath9k_htc`/`mt7601u`/`rtl8xxxu` |
| `netdetect/UsbFlow.java` (321 lines) | the probe → report state machine |

**Port a subset, not the file set.** ~80–120 lines of Kotlin is enough for Indra's actual need:
walk `/sys/class/net/*/device/uevent` + the `driver` symlink, classify the radio, and answer:

> "This device's Wi-Fi is internal PCIe on `qcacld-3.0` — monitor mode is refused by firmware.
> A USB adapter using `ath9k_htc`, `mt7601u` or `rtl8xxxu` would work; none is attached."

Second payoff, nearly free: detect a plugged-in `wlan1` and **pre-fill Wifite's interface field**,
which is free-text today. That is exactly the "monitor mode now works" path the app cannot currently
even suggest.

This belongs in `core/system/MonitorMode.kt`'s neighbourhood, reusing `Capability.IW` and the existing
`explainFailure()` copy.

---

## 4. From StrykerOSS — HID / USB gadget binding

Indra has `HidReports` (pure, tested, correct boot-keyboard reports) and 8-layout *parsing* is absent;
it also cannot bind the gadget. StrykerOSS ships:

| StrykerOSS | Indra today |
|---|---|
| `hid/` **38 files**, 8 layouts (`de en es fr gb it ru us`) | `HidReports` only, no layout table |
| `usbarsenal/` 6 files + `UsbPassthroughManager` | `usb_arsenal` probe reports `UDC: false` |
| `usb/` configfs function binding | not implemented |

**Take:** the layout table (a `Map<Locale, HID keymap>`, ~200 lines of pure data — trivially testable)
and the UDC lookup (`/sys/class/udc/*`) so `usb_arsenal` stops reporting a capability it never binds.

**Skip:** passthrough. It needs kernel cooperation Indra cannot assume.

---

## 5. From WIFI-Frankenstein — the WiFi-data features

| Feature | Files | Value to Indra |
|---|---|---|
| 3WiFi / SmartLink DB lookup | `dbsetup` 25, `api3wifi` 6, `databasefinder` 15 | **Strategic only.** Turns a handshake into an offline lookup. Big surface, niche audience. |
| Wardriving (`personalmap` 10, `wifimap` 15) | 25 | Low. Needs GPS + map stack for a feature Indra does not claim. |
| DPI/RKN detection (`internetblocking` 37) | 37 | **Interesting, not urgent.** Detects censorship/DPI on the link — genuinely unlike anything else here. |
| `HandshakeCaptureRunner` | 1 | How to drive capture→store→parse without the screen owning the process. |
| `AirodumpParser` | 1 | Parses airodump CSV — useful if Indra ever gets `airodump-ng`. |

**Take now:** nothing beyond the crypto (§0).
**Consider later:** the DPI detector, because it is the only capability that answers a question
none of the other four tools ask.

---

## 6. From zAnti — HTTP injection / SSL strip

The only unreplaced capability in the field: `ZHttpInjector` — `HttpProxyServer`, `HTTPSession`,
`SSLServerConnection`, `filters/`, `interactive/`, driven by `ZHttpInjectorService` with its own
ARPSpoof thread. It intercepts TLS with a generated CA and injects into responses.

**Verdict:** strategically the most distinctive thing anyone could add, and the worst
effort-to-certainty ratio in this list — it is decompiled targetSdk-21 code, it requires a CA-install
flow, and it has a real "will this brick a device's trust store" design question.

**Do not start it before §0–§3 are done.** But note it: it is the one feature that would make Indra
something no peer is.

---

## 7. What NOT to take, and why

| Tempting | Why not |
|---|---|
| StrykerOSS `Core.java` static singleton, 1,643 lines, 1 test | This is the anti-pattern Indra already avoided; nothing to learn |
| StrykerOSS QEMU/UML engines | Every capability Indra gains this way costs two binary blobs and arm64-only. Indra's app-API rootless set is lighter and runs everywhere |
| StrykerOSS XML/Fragments + drawer | Compose + Nav3 typed keys is strictly better |
| WIFI-F `cracker.c` / JNI | Reverses Indra's no-JNI decision; the JVM path is fast enough for the lists this app handles |
| WIFI-F 0-test culture, proot router scan | proot cannot `execve` from `filesDir` on API 29+ — Indra's `Rootless` doc already documents why |
| zAnti ActionBarSherlock, SlidingMenu, targetSdk 21 | obsolete |
| Strix single-module, entry-scoped ViewModels | Indra's 5 modules and session-scoped stores are already ahead |
| Strix Docker buildx pipeline | assumes a clean tree; Indra's EOL tree works and needs a *gate*, not a rebuild |

---

## 8. Build order

| Phase | Work | Borrowed from | Why first |
|---|---|---|---|
| **P0** | `WpaCrypto` + `HandshakeParser` port → in-app crack path behind `crackHandshake` | WIFI-F | Removes a documented permanent blocker; ~1,000 lines of pure, tested Kotlin; no new binary |
| **P0** | `MsfCommand` + payload picker + option fields + session list | Strix | The largest capability gap against the top competitor |
| **P1** | `versions.env` + `verify-sig.sh` + `ci.yml` + `build-apk.yml` + `update-bundles.yml` | Strix | Makes the project reproducible and shippable |
| **P1** | `MsfDaemon` generation counter / `beginMaintenance()` onto `MsfLaunch` | Strix | Fixes a real stale-daemon bug class |
| **P2** | Radio/USB classification (~100 lines) + Wifite interface pre-fill | StrykerOSS | Converts monitor mode from "impossible" to "here is what to attach" |
| **P2** | HID layout table + UDC lookup | StrykerOSS | Finishes a feature that currently reports a capability it does not bind |
| **P3** | `compileSdk`/`targetSdk` 37 | both | only API-level lag in the field; blocked on Nav3 1.2 / AGP 9.1 |
| **P4** | DPI detector | WIFI-F | distinctive, cheap, low risk |
| **P5** | HTTP inject / SSL strip | zAnti | distinctive, expensive, needs a CA-install design first |

---

## 9. The shape of the answer

Indra's architecture is already the best in the field — that is settled and no port changes it.
What the field has that Indra does not is **finish**, and three of the four gaps are closeable with
code that already exists and is already written in the same language:

- the handshake pipeline is **Kotlin-ready** in WIFI-Frankenstein,
- the Metasploit workflow is **Kotlin-ready** in Strix,
- the CI trust chain is **shell-ready** in Strix,
- the radio diagnosis is **100 lines of sysfs** away.

Nothing on this list requires Indra to give up the daemon, the pure-Kotlin client, the module
boundaries, or the test discipline. That is the point: the remaining work is additive.

---

*Compiled 2026-11-14 from measured source. Citations are file + line in the source repos.*
