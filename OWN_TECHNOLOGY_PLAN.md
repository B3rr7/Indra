# OWN TECHNOLOGY PLAN — learn the principle, build our own

Rule: **no code is taken from Strix, StrykerOSS, WIFI-Frankenstein or zAnti.**
For each gap we record what their implementation *taught* us, why their implementation does not fit
Indra, and the design we build instead. Everything below reuses an Indra primitive that already exists.

The measure of success is not "does it resemble theirs" — it is "does it reuse what we already have,
and is it testable in our style".

---

## 1. Handshake → key: our own pcap-native pipeline

### What they taught us
Converting a capture into a crackable hash is a **data-format problem**, not a tooling problem.
It can be solved in-process, with no binaries, in any language with HMAC.

### Why we do not port theirs
WIFI-Frankenstein's entry point is a **22000 text line** produced by an external tool
(`HandshakeParser.parse22000Line`). Indra never has that text — it has its **own** capture:
`tcpdump -w <filesDir>/captures/last.cap` (verified classic pcap, magic `d4c3b2a1`). Feeding their
parser would mean first writing a converter to the format their parser expects — three steps to do
what we can do in one.

Their parser is also 768 lines generalising over four formats Indra does not produce or consume.

### Our design
```
core/system/handshake/
  PcapFrames.kt    class PcapFrames            — walk our own classic-pcap records
  Dot11.kt         object Dot11                — 802.11 data frame → EAPOL / beacon / probe
  EapolHandshake.kt object EapolHandshake     — collect msg 1–4, produce MIC inputs; PMKID from
                                                association/beacon where present
  WpaKey.kt        object WpaKey              — PBKDF2-SHA1(4096) + HMAC-MIC verify
  CrackRun.kt      sealed class CrackRun      — Accepted(key) / Exhausted / NoHandshake / BadCapture
```

Ownership boundaries, in our house style:

| Type | Contract |
|---|---|
| `PcapFrames` | input = `ByteArray`, output = `Sequence<Frame>`; sniffs pcapng magic and returns `BadCapture("pcapng, re-record with -w")` rather than guessing |
| `EapolHandshake` | input = frames, output = `Handshake?` — `null` is a real answer, not an error |
| `WpaKey` | input = `Handshake` + passphrase, output = `Boolean`; **pure, no I/O, no coroutines** |
| `CrackRun` | the same sealed-verdict shape as the existing `Hashcat.Verdict`, so the screen renders **one** verdict whichever path ran |

Concurrency is ours to choose: a bounded `Semaphore` over `Dispatchers.Default`, not their JNI batch
call. No `System.loadLibrary`, no `.so`, no native self-test — that is the decision
`KotlinDaemonClient` already made for the whole app.

### What we gain that they do not have
- **One verdict type for both paths.** aircrack-ng (fast, bulk) and our in-app loop (no engine
  needed, small lists) return the same `CrackRun`, so `HandshakesScreen` has no branch on "which
  engine ran". They have separate convert and crack screens.
- **PMKID as a first-class path.** PMKID needs no client and no deauth; it is the modern attack.
  Their converter supports the format; nothing in their UI leads with it.
- **Their `Capability.HCXTOOLS` problem does not exist for us** — we delete the dependency rather
  than document it as unsatisfiable.

### Tests we will write (our style, pure)
`PcapFramesTest` — a real 6-record header fixture; pcapng rejected with the right reason.
`EapolHandshakeTest` — a hand-crafted 4-way frame set yields one handshake; a 2-frame set yields `null`.
`WpaKeyTest` — known passphrase/SSID pairs from published test vectors; wrong password returns false.
Round-trip: our own `tcpdump` capture of a synthetic EAPOL sequence.

---

## 2. Metasploit: RPC-first, not console-text

### What they taught us
An exploit is **structured data** — a module with typed options, a compatible-payload list and a
target index. The user should fill fields, not type commands. That flow is right.

### Why we do not port theirs
Strix's `buildExploitCommand` **generates msfconsole text** (`use X / set K V / exploit -j`) and then
parses the console for results. That is a workaround for not having a structured API, and it carries
the whole class of bugs that text protocols carry: quoting, option ordering, `set` vs `setg`, parsing
session ids out of ASCII.

**Indra already speaks the structured API.** Measured in `MsfRpcClient`:

| Method | State |
|---|---|
| `moduleInfo(type, name)` | ✅ exists — but returns module **metadata** (name, description, authors, references, rank). **It is not the option schema** |
| `compatiblePayloads(module)` | ✅ exists — `{"payloads" => [names]}` |
| `sessionList()` / `sessionStop(id)` | ✅ exists |
| `consoleCreate/Read/Write` | ✅ exists |
| own `MsgPack` codec, 15 tests + `FakeMsfrpcd` (7 tests) | ✅ exists |

**Correction (found on re-check):** msgrpc splits module introspection across separate methods, and
Indra has only two of them. The option schema is **`module.options`**, targets are
**`module.targets`**, execution is **`module.execute`**, running jobs are **`job.list`** — none of
which exist in our client. So the work is four additions, not two:

```
MsfRpcClient:
  moduleOptions(type, name)   -> Map<optionName, Map<type, required, default, enums, desc>>
  moduleTargets(name)         -> List<target>
  moduleExecute(type, name, opts) -> JobId(uuid)
  jobList()                   -> Map<jobId, metadata>
```

`FakeMsfrpcd` must grow fixtures for all four (it currently covers only `auth.login`, `core.version`,
`module.exploits`/`auxiliary`, `console.read`, `session.stop`) — that is a prerequisite, not a
follow-up: the four new methods would otherwise ship untested.

So we are not catching up to Strix — we have a better substrate than they built on — but the
substrate is one layer thinner than the first version of this plan assumed.

### Our design
```
core/system/msf/
  MsfModule.kt      data class MsfOption(name, type, required, default, choices, desc)
                    data class MsfModule(name, options, payloads, targets)
  MsfCommand.kt     object MsfCommand — module + values → validated Map<String,String>
                    (rejects missing required, bad choice, unknown key)
  MsfJob.kt         sealed class MsfJob — Started(jobId) / Session(sessionId) / Failed(reason)
```
plus four additions to `MsfRpcClient`: `moduleOptions`, `moduleTargets`, `moduleExecute`, `jobList`
(see the correction table above — `moduleInfo` alone does not carry the option schema).

UI: `ExploitFinderScreen` gains a second state (`Searching` → `Configured(module)`), fields are
generated from `MsfModule.options`, payload from `compatiblePayloads`. Sessions render from
`sessionList()` and tap into the existing Shell.

### What we gain that they do not have
- **No console text, no parsing.** Strix's flow is a text pipeline; ours is a DTO pipeline.
- Typed validation before dispatch: a missing required option is a `FormError`, not an msf error
  line scraped out of a console.
- `MsfCommand` is a pure function → unit-testable without a daemon, which is exactly how our
  `Nuclei`/`Hashcat`/`MsfSearch` classes already work.

---

## 3. Build trust: extend `SignedEnvelope`, do not add gpg

### What they taught us
A build should verify the **exact source it downloads**, against a key pinned ahead of time, and fail
closed on any mismatch. Strix does this per upstream project with `versions.env` + `gpgv` + pinned
fingerprints.

### Why we do not port theirs
That is **a second trust mechanism** bolted next to our first. Indra already has one primitive —
`SignedEnvelope` (ECDSA P-256, `parse → verify → accepts`, used by OTA, app updates and both bundle
channels, tested in `OtaModelsTest`). Adding `gpgv` + keyrings + fingerprints gives us two answers to
"is this artifact trustworthy", which is a worse design than one.

### Our design
```
core/system/trust/
  SourcePin.kt     data class SourcePin(name, version, sha256)      — parsed from a checked-in file
  pins/sources.txt one line per upstream: name version sha256
```
- A CI job recomputes `sha256` for every pinned upstream and opens a PR on drift — which is the part
  of Strix's work we actually want.
- Verification reuses `HashingStream` + `BundleInstaller`'s streamed check. No new crypto.
- Signing of **our** releases stays `SignedEnvelope` + the existing `tools`/`msf` `BundleSpec`
  channels. One primitive, two uses.

### What we gain that they do not have
One trust model across source → bundle → APK → OTA. Their source layer (gpg) and release layer
(signature) are different mechanisms that can disagree.

---

## 4. Radio capability: a `Capability`, not a new screen

### What they taught us
A driver's monitor capability is **discoverable from sysfs before you attempt the operation** — bus
kind, driver name, chipset. StrykerOSS does this in ~1,650 lines across 15 files.

### Why we do not port theirs
We do not need a USB explorer. We need one answer to one question: *can this interface go monitor, and
if not, what would?* And Indra already has the machinery to present that answer: the `Capability`
enum and `CapabilityFix` (sentence + one action button, single-flight installer, app-wide state).

### Our design
```
core/system/RadioProfile.kt
  data class Radio(iface, driver, bus, monitorable, reason)
  fun of(uevent: String, driver: String): Radio        — pure
  fun assess(radios: List<Radio>): RadioVerdict        — Ok / Refused(reason, suggestion)
```
- Add `Capability.MONITOR_RADIO` to the existing enum (it already has `IW`).
- Source of truth is `/sys/class/net/*/device/uevent` + the `driver` symlink, read through the
  existing `command()` path — no new binary, no `/sys/bus/usb` walk.
- `CapabilityFix.of(MONITOR_RADIO)` points at the existing `RunApk(iw iproute2)` install when `IW` is
  missing, and at a **named adapter class** when the driver is `qcacld` with no mac80211.
- Bonus, nearly free: when a second wireless interface appears, pre-fill Wifite's interface field.

### What we gain that they do not have
The answer arrives as a **fix card** in the same UI language as every other missing capability,
instead of a separate diagnostic screen. And it is a pure function over text, so it is unit-tested
with three fixtures (qcacld PCIe, ath9k_htc USB, no radio).

---

## 5. HID: layout as data, UDC as a probe

### What they taught us
A keyboard layout is a **table**, and gadget readiness is a **file that either exists or does not**.

### Why we do not port theirs
Their `hid/` package is 38 files of fragments, adapters and dialogs. Our `HidReports` is already a
pure, tested encoder.

### Our design
```
core/system/hid/HidLayout.kt   object HidLayout — Map<Char, Int> per locale, 8 locales as data
core/system/hid/HidUdc.kt      object HidUdc — read /sys/class/udc/*, report bound/unbound + name
```
`HidReports` gains a locale parameter; `usb_arsenal` stops reporting a capability it never binds and
instead renders "UDC present but unbound: <name>" or "no UDC".

---

## 6. DPI / link interference: measure, then classify

### What they taught us
Link-level interference is detectable by probing and classifying the *response pattern*, not by
inspecting the network.

### Our design (P4, after the above)
```
core/system/LinkProbe.kt
  sealed class LinkVerdict — Clean / Throttled / Reset / DnsTampered(detail)
  suspend fun probe(): LinkVerdict      — N small requests, classify by failure shape
```
Runs through the existing `execute()` path so the console shows real lines. No new dependency.

### Explicitly deferred
HTTP injection / SSL stripping (zAnti's one unreplaced capability). It needs a generated CA, a
trust-store install flow, and an in-process proxy — a project, not a task. If we do it, it will be our
own design around `Socket`/`SSLContext`, not a port.

---

## 7. How we prove this is ours

| Dimension | Theirs | Ours |
|---|---|---|
| Handshake entry point | 22000 text line from an external tool | our own `tcpdump` pcap |
| Handshake core | JNI batch + self-test against JVM | pure `WpaKey`, no `loadLibrary` |
| Verdict type | separate convert/crack results | one `CrackRun`, same shape as `Hashcat.Verdict` |
| MSF flow | generated console text + scrape | `moduleInfo`/`execute` DTOs over our `MsgPack` |
| Trust | `versions.env` + gpgv keyrings | `SignedEnvelope` extended + hash pins |
| Radio | 1,650-line USB explorer | one `Capability` + pure `Radio.of()` |
| HID | 38-file fragment package | two data/probe objects |
| Tests | 0 / 1 / 117 / 0 | every unit above is pure and lands with its own test |

---

## 8. Order

| Phase | Build | Reuses |
|---|---|---|
| P0 | `PcapFrames` → `EapolHandshake` → `WpaKey` → `CrackRun`, wired into `crackHandshake` | `Hashcat.Verdict` shape, `ToolResult`, `CapabilityFix` |
| P0 | `MsfModule` + `MsfCommand` + the four `MsfRpcClient` additions + `FakeMsfrpcd` fixtures, then the Configured screen | `MsfRpcClient`, `MsgPack`, `FakeMsfrpcd` |
| P1 | `SourcePin` + hash-pin CI job + `ci.yml`/`build-apk.yml`/`update-bundles.yml` | `HashingStream`, `BundleInstaller`, `SignedEnvelope` |
| P2 | `RadioProfile` + `Capability.MONITOR_RADIO` | `Capability`, `CapabilityFix` |
| P2 | `HidLayout` + `HidUdc` | `HidReports`, `usb_arsenal` |
| P3 | `LinkProbe` | `execute()` |
| P4 | compileSdk/targetSdk 37 | Nav3 1.2 / AGP 9.1 when available |

---

## 9. The distinction, stated once

Their code taught us **where the problems are and which invariants matter**: format conversion
belongs in-process, a module is a schema, a build must verify its source, a driver's capability is
readable before you use it.

Our code answers those invariants **with the primitives Indra already owns** — pure objects with
sealed verdicts, one trust envelope, one capability/fix language, one console.

That is why the result will not look like any of them, and why it will be smaller than all of them.

---

## 10. Re-check pass — what survived, what changed

Every assumption in this plan was re-verified against the tree on 2026-11-14.

### Changed (the plan was wrong)

| Assumption | Reality | Fix applied |
|---|---|---|
| `moduleInfo()` returns the option schema | It returns module **metadata**. Options are `module.options`, targets `module.targets`, execution `module.execute`, jobs `job.list` | P0 grew from 2 client additions to **4**, plus `FakeMsfrpcd` fixtures |
| Strix 2nd, StrykerOSS 3rd is a firm ranking | 0.30 gap flips on any 10-point shift from build/quality/UX to breadth | FINAL_VERDICT now reads **2=** and carries the sensitivity table |

### Confirmed (the plan holds)

| Assumption | Evidence |
|---|---|
| We produce classic pcap, so a pcap-native reader is the right entry point | bundled `tcpdump` is **3.9.8** (`indra/jni/tcpdump/version.c`); it predates pcapng entirely, so `tcpdump -w` cannot emit it. The plan's pcapng sniff stays as belt-and-braces |
| Adding `Capability.MONITOR_RADIO` is safe | `CapabilityFix.of` is an **exhaustive `when` with no `else`** (`engine/CapabilityFix.kt:57-79`), so a new enum member is a **compile error until it is mapped** — the compiler enforces the fix, not us |
| `MONITOR_RADIO` fits the existing state model | `CapabilityState` has exactly `Available` / `Missing(capability, fix)` / `EngineNotInstalled(fix)`; `Missing` is the right shape |
| `CrackRun` can mirror our verdict style | `Hashcat.Verdict` is already `Cracked`/`NoHandshake`/`NotCracked`/`Failed` (`engine/Hashcat.kt:32-46`) — `CrackRun` mirrors it exactly |
| `ToolResult` can carry the result of either path | carries `rawOutput`, `exitValue`, `parsedData: Map<String, Any>?` (`SmartToolBox.kt:1962`) |
| `MsfRpcClient` has the structured substrate Strix lacks | `module.info`, `module.compatible_payloads`, `session.list`, `session.stop`, `console.*`, own `MsgPack` — all present |
| `SignedEnvelope` is the one trust primitive | used by `AppUpdateManager`, `OtaUpdateManager`, both bundle channels |

### Still unverified (deliberately)

- Real `msfrpcd` reply shapes for `module.options` / `module.targets` / `module.execute` / `job.list`.
  The wire facts we do hold came from a live probe (`bin`-encoded keys and values, `binary/message-pack`),
  so these four must be probed the same way **before** writing the DTOs, not assumed from docs.
- PBKDF2 throughput on the target device (the 1–2 k guesses/s figure is an estimate, not a measurement).

---

*Design record, 2026-11-14, re-checked the same day. No source from the compared projects is copied;
file names and type shapes above are ours.*
