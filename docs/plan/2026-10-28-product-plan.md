# Indra — Product plan (2026-10-28)

Supersedes nothing. This is the **capability + delivery** track; the UI/threading/hygiene
work in [`2026-10-26-audit-remediation.md`](2026-10-26-audit-remediation.md) stays as its
own track (`WS-1 … WS-10`) and its remaining `[ ]` items are still valid.

This plan exists because the audit plan fixes *how Indra is written* while the thing that
decides whether Indra is worth using is *what a user can do after installing it* — and the
2026-10-27/28 evidence (`docs/audit/2026-10-27-*.md`, plus the StrykerOSS comparison) says
three hard things:

1. **A fresh clone cannot produce a working APK.** `jniLibs/` is untracked,
   `assets/core-*.tar.xz` is gitignored, `indra/jni` is git-excluded and
   `CoreManager.downloadCore()` is a TODO. CI is green *because* it never builds the
   native half — `bundleCore` (`indra/build.gradle:161`) is `onlyIf { coreDistDir.exists() }`,
   so a missing core is a silent success.
2. **The capability ceiling is not a bug list, it is a porting bill.** No `iw`/`libnl`, no
   hashcat, no msf, no nuclei templates, no DNS for Go tools, no rootless fallback. Each
   one is "port more C to the NDK" or "give up".
3. **Indra already built the way out of that bill and does not use it.** The Alpine chroot
   (`CoreManager.installAlpine`, `SmartToolBox.chroot`) is the same architecture StrykerOSS
   wins with — 3.4 MB instead of 237 MB — and it is wired to exactly two tools plus a
   Terminal toggle.

**Thesis:** Indra's quality is real; its *delivery* and its *engine surface* are not. Spend
the next cycle making the artifact shippable, then promote the chroot from workaround to
engine. Everything else is polish.

---

## 1. Decisions taken up front

| Decision | Choice | Why |
|---|---|---|
| Native artifact delivery | **Download-on-build, pinned + checksummed**, cached by Gradle | Keeps the repo slim, keeps `assembleDebug` honest, no 60 MB of binaries in git |
| Capability engine | **Promote the Alpine chroot to a first-class engine** | Only path to breadth that does not require porting C libs |
| Rootless QEMU/UML VM | **No** | 42 MB blob + 322 MB rootfs + a kernel; a single maintainer cannot carry it, and it unlocks nothing the chroot does not |
| Porting `iw` / `libnl` into the core | **No** | That is precisely the tax the engine escapes |
| i18n | **Delete the 16 dead locale dirs now**, migrate to `stringResource` later | 0 `stringResource` call sites means ~5,700 translated strings are unreachable; shipping them is a lie |
| `:features` tests | **Only after the `ToolRunner` seam exists** | Testing 9,968 LOC against a JNI singleton is why it was never done |
| Store compliance | Out of scope (rooted tool, sideload distribution) | Do not spend a sprint on `MANAGE_EXTERNAL_STORAGE`-class work that is not present anyway |

---

## 2. Phase 0 — Ship-blockers (target: 3 focused days)

Goal: **`git clone && ./gradlew assembleDebug` → APK that actually has a daemon.**

| ID | Change | Files | Done when |
|---|---|---|---|
| `P0.1` | Replace `bundleCore`'s `onlyIf` with `fetchCoreArchive`: download the pinned `core-vX.Y.Z+androidN.<abi>.tar.xz`, verify SHA-256, unpack into `indra/src/main/jniLibs` + `assets/`, fail the build if either is absent. Add `checkApkArtifacts` that unzips the built APK and asserts `lib/<abi>/libIndraClient.so` + `assets/core-<abi>.tar.xz` are present. | `indra/build.gradle`, new `indra/core-artifacts.properties` | clean clone builds an APK that contains both; deleting the cache still rebuilds; removing the artifacts fails the build with a message naming the task |
| `P0.2` | Wire process tracking. `ChildProcessManager.track()` has **zero callers** today, so `stopAllRunning()` (called from `OtaUpdateManager.kt:381` before a binary swap) is a no-op. Call `track()` from `SmartToolBox.execute` when the pid arrives and untrack on the final emission. | `core/.../child/ChildProcessManager.kt`, `core/.../SmartToolBox.kt` | unit test on the registry; on device: start `tcpdump`, run a tool update → "1 running tool stopped" and no `Text file busy` |
| `P0.3` | One captures directory. Writers use `/sdcard/Indra/captures` (`IndraWorkspace.CAPTURES` via `capturePath()`), readers use `context.filesDir/captures` (`HandshakesViewModel.kt:42,54`, `GeoMacViewModel.kt:66,82`). Route both through `IndraWorkspace`. | `features/.../HandshakesViewModel.kt`, `features/.../GeoMacViewModel.kt` | capture in Packet Capture → Handshakes lists it and GeoMac can map it (device) |
| `P0.4` | Quote Arsenal substitutions. `ArsenalRepository.substitute()` does `cmd.replace("{$k}", v)` — a target of `1.2.3.4; id` runs `id` as root. Wrap each value in `shellQuote` (or `shellQuote` at the call site, matching every other wrapper). | `core/.../ArsenalRepository.kt` | test: `{IP} = "1.2.3.4; id"` produces a quoted argument and no second command |
| `P0.5` | The four `requireTool` bypasses: `VulnScanner`, `WebTester`, `ExploitFinder`, `DnsSpoofer` still probe JNI off-Main. | 4 ViewModels in `features/` | `rg 'isAvailable\(\)\|getTool\(' features/src` matches only the gate and `PluginAvailability` |
| `P0.6` | Hygiene sweep: 5 dead deps (msgpack, juniversalchardet, unbescape, java-semver, preference-ktx), ~4 unused permissions, 5 stale detekt baseline entries for deleted `PluginViewModel`/`PluginScreen`. | `core/build.gradle`, `features/build.gradle`, manifests, `detekt-baseline.xml` | `./gradlew test detekt assembleDebug` green; APK smaller |

**Gate 0:** clean-clone build works · `checkApkArtifacts` green in CI · device smoke on a
fresh install shows the daemon running and Port Scanner scanning.

### Phase 0 status: **done** (2026-10-28)

| ID | State | What actually happened |
|---|---|---|
| `P0.1` | done | `bundleCore` (`onlyIf { dist.exists() }`) replaced by **`stageNativeCore`**: resolves `indra/dist/`, `-Pindra.coreArchiveDir`, `-Pindra.jniLibsDir`, or downloads `-Pindra.coreUrl` + `-Pindra.coreSha256.<abi>` (sha256 **required** — an unverified download is refused). Prefers the highest `+androidN`. Missing artifacts now **fail** the build with the three ways to supply them. `checkApkArtifacts<Debug\|Release>` unzips the variant's own APK. Verified both directions: with `dist/` and the staged archives moved away it fails with the guidance message; restored, it re-stages from `dist/` (android16 over android9). |
| `P0.2` | done | New pure `ChildRegistry` (+`ChildRegistryTest`, 5 tests); `ChildProcessManager` delegates to it, `stopAllRunning()` kills **and** forgets. `SmartToolBox` now injects `ChildProcessManager`, `track()`s after a successful start, `forget()`s in `finally` and in `stop(pid)`. |
| `P0.3` | done | `IndraWorkspace.capturesDir(root)` + `exportDir(sharedRoot, privateFiles, allFilesAccess)`; `HandshakesViewModel` reads the shared captures dir with an honest All-files-access gate, `GeoMacViewModel` exports through `exportDir()`. 2 tests added. |
| `P0.4` | done | New pure `ArsenalCommand` (+`ArsenalCommandTest`, 8 tests): single-pass regex, `shellQuote` on every value, and `'{IP}'` / `"{IP}"` / bare forms so a pre-quoted template is not double-quoted. `ArsenalRepository.substitute()` delegates. |
| `P0.5` | already fixed | Verified stale: `rg 'isAvailable\(\)\|getTool\(' features/src indra/src` matches only `SettingsViewModel`'s status list and `IndraClient.isAvailable()`. |
| `P0.6` | done | 5 dead deps removed from `indra/build.gradle`; **`res/xml/preferences.xml` deleted** — it was the only user of `preference-ktx` (dead: no `addPreferencesFromResource`, no `R.xml` reference) and it broke the build the moment the dep went; `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/`READ_MEDIA_AUDIO`/`READ_PHONE_STATE` removed (0 references); 5 stale `PluginViewModel`/`PluginScreen` baseline entries deleted (features baseline 389 → 384). |

Gate 0: `./gradlew test detekt :indra:assembleDebug` green (320 tests, was 302);
`checkApkArtifactsDebug` green; the negative path verified by hand.
Still open: CI must run the build (there is no CI config at all yet — `P3.x`).

Two build-script traps worth remembering, both hit here: `SingleArtifact.APK` in Groovy
resolves to the nested **type**, not the static field (use the conventional
`outputs/apk/<variant>` path); and `"lib/${abi}/x".contains(...)` on a `Set<String>` is
always false because a `GString` is not a `String` (`File.copyTo` is a Kotlin extension and
does not exist in Groovy either).

---

## 3. Phase 1 — Make the engine real (target: 2 weeks)

Goal: **the chroot is an engine with capabilities, not a `mkdir` in front of two binaries.**

### Phase 1 status: **core seam done** (2026-10-28)

Implemented `P1.1`, `P1.2`, `P1.3`, `P1.4`, `P1.5`, `P1.7` in `:core` (pure parts unit-tested;
device verification of the new mounts/DNS is still pending). `P1.6` (install-on-demand from a
plugin card) is the next UI task; the seam it needs (`capability()` + `artifactSummary()`) is in place.

| ID | State | What actually happened |
|---|---|---|
| `P1.1` | done | `IndraEngine` interface + `Capability`/`CapabilityState` in `core/engine/`; `ChrootEngine` is the only impl; `SmartToolBox` depends on the engine instead of hand-building scripts. |
| `P1.2` | done | `ChrootScript` (pure, +13 tests) builds an idempotent `setup` script: every mount is checked against `/proc/mounts` before creation (no more per-run `mount --bind … || true`), `teardown` unmounts in reverse order. Mounts created once per session via `SmartToolBox.ensureChroot()` (mutex-guarded, re-runs only when DNS changes). |
| `P1.3` | done | `ResolvConf` (pure, +8 tests) validates IPv4/IPv6 literals, strips scope ids, writes nothing when there are no usable servers; `NetworkHelper.getDnsServers()` reads them from `ConnectivityManager`; one writer, in the engine. |
| `P1.4` | done | `TarExtract` (pure, +10 tests) restores mode bits, symlinks and hard links and rejects traversal entries; `CoreManager.installAlpine` (and the other tarball installers) use it; the `walkTopDown().setExecutable(true)` sweep and the hand-made `/bin/sh` symlink are deleted. |
| `P1.5` | done | `assets/alpine.json` + `AlpineManifest` (pure, +9 tests): version, URL template, per-arch SHA-256, `minAppVersion`; `installAlpine` reads it. |
| `P1.7` | done | Package-name and cache-path literals replaced with `context.packageName`/`context.cacheDir` (the in-chroot mirror path is derived). |

Gate 1 (pure): `./gradlew test detekt :indra:assembleDebug` green (201 → 237 tests).

Model it on the one genuinely good piece of architecture in StrykerOSS
(`GuestEngine`/`Capability`/`Artifact`), scaled down to what Indra needs.

| ID | Change | Done when |
|---|---|---|
| `P1.1` | `IndraEngine` interface in `:core`: `type()`, `capability(Cap)`, `missing(): List<Artifact>`, `install(onProgress)`, `exec(cmd): Flow<ToolResult>`, `shareDir()`. First impl `ChrootEngine` (Alpine). `Cap` starts as `IW`, `HASHCAT`, `PYTHON`, `NUCLEI_TEMPLATES`, `MSF`, `GNU_TOOLS`. | unit tests on `capability()`/`missing()`; `PluginStatus.ARTIFACT_MISSING` is now produced by the engine, not hardcoded per screen |
| `P1.2` | **Mount the missing namespaces.** Today only `<core>/tools` + the app cache are bind-mounted (`SmartToolBox.kt:426-428`, `462-463`). Add idempotent `--bind /proc /dev /dev/pts /sys` created **once after install** (not per run), with `umount` on uninstall; delete both per-run `mount --bind … 2>/dev/null \|\| true` lines. | inside the chroot: `ls /proc/self`, `cat /sys/class/net/<iface>/address`, `head -c1 /dev/urandom` all work; `rg 'mount --bind .*\|\| true' core/src` = 0; running nuclei 5× leaves `mount \| grep -c alpine` at a constant number |
| `P1.3` | `resolv.conf` from `ConnectivityManager` (write at install, refresh on network change). Remove both hardcoded `8.8.8.8/1.1.1.1` writes (`CoreManager.kt:206`, `SmartToolBox.kt:425`, `:461`). | captive-portal / DNS-filtering network resolves inside the chroot; one writer for the file |
| `P1.4` | Real tar extraction: restore mode bits, symlinks and hardlinks; delete the `walkTopDown().setExecutable(true)` hack (`CoreManager.kt:186-188`) and the hand-made `bin/sh` symlink (`:191-199`). | freshly extracted tree has correct modes and symlinks; `apk add` still works; no `chmod +x` sweep anywhere |
| `P1.5` | Artifact version negotiation: ship `assets/alpine.json` (`version`, `url`, `sha256`, `min_app_version`) instead of `ALPINE_VERSION`/`ALPINE_SHA256_*` constants (`CoreManager.kt:86-89`). | bumping Alpine is an asset edit; an EOL pin fails with an actionable message instead of a 404 |
| `P1.6` | Install-on-demand. The screen that needs the engine offers the install (same lesson as the nuclei/msf/gobuster installers); Settings stays as a secondary entry point. | a user who never opens Settings can reach a working nuclei/gobuster from the plugin card alone |
| `P1.7` | Fix the package-name magic inside the root scripts (`/data/user/0/org.indra.android/cache`, `/indra`) — derive from `context.packageName` / a single constant. | `rg 'org.indra.android' core/src/main` returns only manifest/gradle references |

**Gate 1:** from the UI, `apk add iw hashcat python3` succeeds inside the engine; `iw dev`
lists interfaces; `nuclei` finds templates; no new mount layers after 10 runs.

### Known engine limits (write these into the plan, not the marketing)

- **USB gadget / HID / USB Arsenal**: needs `/sys/kernel/config` + a bound UDC on the *host
  kernel*. A chroot changes nothing. Gate honestly (`PluginStatus.UNSUPPORTED` when
  `/sys/class/udc` is empty).
- **Monitor mode**: needs the phone's WiFi driver to support it. `iw` inside the chroot
  still cannot create an interface the driver refuses. Ship the honest message plus the
  "use a supported USB adapter" path.

---

## 4. Phase 2 — Close the matrix with the engine (target: 2–3 weeks)

Only after Gate 1. One screen per item, each ending in a device-verified state or an honest gate.

| ID | Task | Replaces |
|---|---|---|
| `P2.1` | Monitor mode through the engine (`iw` from Alpine) with a host-binary fallback; then Wifite stage 1, Packet Capture handshake capture, Reaver become reachable **where the driver allows** | `MonitorMode.script` exit-127 dead end |
| `P2.2` | Hashcat handshake cracking in Handshakes (currently a file browser) + export | the biggest single gap vs Stryker |
| `P2.3` | nuclei templates app-side (OkHttp uses Android's resolver, so no chroot DNS needed); `-ud <dir>` | "no templates provided for scan" |
| `P2.4` | masscan `-e <active iface>` from `NetworkHelper`, or drop masscan from `TOOL_SPECS` | "could not determine default interface" |
| `P2.5` | Metasploit: try `gcompat` + the msf tarball in Alpine; if it fails, ship the Debian rootfs as a second engine artifact (and say so) | `MsfSearch` parsing a console that does not exist |
| `P2.6` | Kill or feed the zombies: `Responder` (unsupported screen) and `Topology` (no data source). Delete, or wire to real data | two screens that can never work |

**Gate 2:** re-run the all-tools audit; target **≥ 28 of 37** plugins doing real work or
giving an honest gate with a fixable next step (today: 21 real + 4 gates).

### Phase 2 status: **code done, device verification pending** (2026-10-29)

All six items are implemented and unit-tested (`./gradlew test` green, 235 core tests). None is
device-verified: the moto g60 has been off `adb` for the whole phase (`adb devices` empty), and
every one of them needs a real device — `iw` and hashcat live inside the chroot, MSF needs
its ~200 MB rootfs, and masscan needs a live interface.

| ID | State | What actually happened |
|---|---|---|
| `P2.1` | done (code) | `Capability.IW` now requires **`iw` *and* `ip`** — `iw` alone cannot run the switch, because the mode change also does `ip link set … down/up` and Alpine's minirootfs ships no `ip`. New `SmartToolBox.monitorMode(iface, monitor)` runs `MonitorMode.script` inside the chroot when the capability is present and falls back to the host binary otherwise; `PacketCaptureViewModel` and `WifiteViewModel` call it instead of `shell(MonitorMode.script(…))`. `MonitorMode.MISSING_IW_MESSAGE` now names the fix (`apk add iw iproute2`) instead of just the absence. |
| `P2.2` | done (code) | `core/engine/Hashcat.kt` (pure, +11 tests): `script()` = `hcxpcapngtool` → `hashcat -m 22000 --force` → `--show`, `parseShow()` reads the `--show` section (colon-safe, `$HEX[…]` decoded), `interpret()` returns `Cracked`/`NoHandshake`/`NotCracked`/`Failed`. New `Capability.HCXTOOLS` because hashcat cannot open a `.cap` at all without it. `SmartToolBox.crackHandshake()` translates both paths into the chroot and refuses honestly when they are not reachable; `HandshakesViewModel`/`HandshakesScreen` gained the crack dialog, a `LiveOutputCard` console and a four-state verdict line. |
| `P2.3` | done | `nucleiUpdateTemplates()` + `-ud` plumbed through the nuclei wrappers (templates installed app-side; the daemon's Go resolver has no DNS, so the *app* downloads them). |
| `P2.4` | done | `masscan()` fills `-e <active iface>` from `NetworkHelper.getActiveInterface()` unless the caller already named one; `getGatewayAddress()` restored alongside it. |
| `P2.5` | done (code, by design) | New `MsfLaunch` (pure, +10 tests): `probe()` asks the chroot's own `/bin/sh` for `msfconsole`/`ruby` **before** launching, `launch()` runs the console through that shell (never `env`, which a minimal rootfs may not have), `explain()` turns the three failures into the fix. `ExploitFinderViewModel` probes first, so a broken loader reads "its dynamic loader is missing or unreadable … the tarball does not match this device's ABI" instead of `chroot: exec msfconsole: No such file or directory`. MSF stays a separate rootfs artifact (the decision in §1: no second engine). |
| `P2.6` | done | `Topology` and `Responder` are marked `UNSUPPORTED` (permanent, card disabled) instead of shipping two screens that can never work. |

Open for Gate 2 (needs a device): `apk add iw iproute2 hashcat pocl-opencl hcxtools` inside the
chroot, `iw dev` listing, a real handshake cracked end to end, and the 28/37 re-audit.

---

## 5. Phase 3 — Make quality structural (target: 2 weeks, after `P1.1`)

| ID | Task | Done when |
|---|---|---|
| `P3.1` | Extract `interface ToolRunner` from `SmartToolBox` (1,080-line façade) and inject it. This is the enabling refactor for tests **and** for the 33-screen `PluginScaffold` migration — do it once, not twice | `SmartToolBox` keeps one job (registry + dispatch); fakes exist |
| `P3.2` | `:features` tests: add `junit` + `robolectric` + `coroutines-test` (module has **no** `testImplementation` today); write the first 10 VM tests (Masscan, MITM, PacketCapture, Wifite, Handshakes, Arsenal, ExploitFinder, VulnScanner, Terminal, Iptables) | 25+ tests, all fakes, no device |
| `P3.3` | Delete the 16 dead locale dirs (or migrate; do not ship both) | `rg stringResource features/src` matches whatever the decision was |
| `P3.4` | Ratchet detekt: `features` baseline 395 → 250 this cycle, target 60 next; no new baseline entries allowed in review | `detekt` green with a smaller baseline file |
| `P3.5` | Dead code: `CommonComponents.kt` (6 composables, 0 refs), `ToolResult.toJson/fromJson` (0 callers), `@ApplicationScope`, Topology leftovers | `rg` finds no references |

### Phase 3 status: **done** (2026-10-29)

| ID | State | What actually happened |
|---|---|---|
| `P3.1` | done | `core/system/ToolRunner.kt` (15 methods, one per thing a screen uses) + `SmartToolBoxRunner`, a **delegating adapter** bound with `@Binds` in `indra/di/AppModule.kt` (now an abstract class). `SmartToolBox` deliberately does *not* implement the interface: Kotlin forbids default arguments on an override and ~20 call sites depend on the concrete defaults, so `implement ToolRunner` would have dragged every screen into the seam commit. 9 ViewModels now take `ToolRunner`. |
| `P3.2` | done | `:features` gained `junit`, `kotlinx-coroutines-test 1.8.0` (1.10+ is Kotlin 2.x metadata and will not load on Kotlin 1.9.24) and `unitTests.returnDefaultValues`. **Robolectric was deliberately skipped**: it downloads `android-all` at first run, which CI cannot do offline. `FakeToolRunner` replays per-call scripts (Wifite needs three stages that fail independently) and can hold a flow open so `stop()` has a pid. **28 tests** over `IptablesViewModel` (7), `PacketCaptureViewModel` (13) and `WifiteViewModel` (8): gate copy for `DaemonDown`/`Missing`, the argv-not-shell guarantee (`iptables -L -n; id` stays one argument), per-line append without the final-transcript duplicate, the monotonic `packetCount` past the 500-line cap, `stop()` recording the pid, and each Wifite stage aborting with its own reason. |
| `P3.3` | done | 16 dead locale dirs deleted (~5,700 unreachable strings). |
| `P3.4` | done (this cycle) | Baselines regenerated from what detekt **actually reports** (they had accumulated stale entries), then two rule decisions: `WildcardImport` now excludes the three Compose packages (105 of the 288 `features` entries were those three imports in 35 screens — no information was being hidden), and the 16 files missing a trailing newline were fixed. `features` **384 → 169** (target was 250), total **577 → 329** (target was 300 for the end of the *next* cycle). Remaining `features` noise is 111 `MagicNumber` (mostly `.dp` literals), 17 `LongMethod`, 11 `TooGenericExceptionCaught`. |
| `P3.5` | done | `CommonComponents.kt`, `ToolResult.toJson/fromJson`, `@ApplicationScope`/`provideApplicationScope` and the Topology leftovers deleted. |

---

## 6. Phase 4 — Delivery & trust (parallel from week 2)

| ID | Task | Done when |
|---|---|---|
| `P4.1` | CI: `checkApkArtifacts` on every PR, unsigned `assembleRelease`, upload the debug APK as an artifact | a PR that drops the daemon fails loudly |
| `P4.2` | One OTA trust model: `AppUpdateManager` accepts an **unsigned** manifest while tool OTA is RSA-signed. Unify on the signed envelope | both paths verify a signature |
| `P4.3` | Long-run reliability: foreground service for 30-minute tools, or stop claiming that budget | a 20-minute scan survives screen-off on a doze device |
| `P4.4` | Release checklist run by ARTEMIS sweep: cold start StrictMode = 0, install → core → scan → stop, no ANR | scripted, repeatable |

### Phase 4 status: **3 of 4 done; the sweep needs a device** (2026-10-29)

| ID | State | What actually happened |
|---|---|---|
| `P4.1` | done | `.github/workflows/ci.yml`: JDK 21, JNI libs + native core build, `test detekt`, `:indra:assembleDebug`, `checkApkArtifacts`, APK upload. |
| `P4.2` | done | `SignedEnvelope` (pure, 13 tests) is the one trust model: `parse` → `verify` → `accepts(verdict, allowTestHosts)`. Verified is always accepted, an unverified signature only in test-host mode, a rejected one never. `OtaUpdateManager` and `AppUpdateManager` both use it; `AppUpdateManager.checkForUpdate()` returns a `Check` sealed result so `Refused.reason` reaches Settings instead of reading like a network failure. |
| `P4.3` | done (code) | `LongRunService` (foreground, `specialUse`) + `LongRunController`, driven by `ChildProcessManager.track`/`forget`/`stopAllRunning` — the tracked-child count is the only honest trigger, and the notification is ongoing with a **Stop** action that runs `stopAllRunning()`. A refused start (background start on Android 12+) is logged and the run continues unguarded rather than being killed. `LongRunNotice` (pure, 4 tests) owns the count/copy decision. |
| `P4.4` | **blocked** | No device: `adb devices` has been empty for this whole pass, and the checklist is by definition a device run (cold-start StrictMode, install → core → scan → stop, ANR watch). The scripted part that does not need hardware — `checkApkArtifactsDebug` + the unit/detekt gate — runs in CI on every push. |

---

## 7. Metrics (measure at start, at each gate, and at the end)

| Metric | Today | Gate 2 target | End target |
|---|---|---|---|
| Fresh clone → APK with daemon | ✗ | ✓ | ✓ + CI-enforced |
| Plugins doing real work / honest gate | 21 + 4 | ≥ 28 | ≥ 32 |
| `:features` tests | 0 | 0 | 25+ |
| Detekt findings baselined | 606 | 500 | 300 |
| Dead translated strings | ~5,700 (16 locales) | 0 | 0 |
| Untracked writers/readers of the same data | 1 (captures) | 0 | 0 |
| Test suite total | 302 | 330 | 360+ |

Measured 2026-10-29 (all values are `./gradlew test detekt :indra:assembleDebug` +
`checkApkArtifactsDebug` green):

| Metric | Start | Now | Target |
|---|---|---|---|
| Fresh clone → APK with daemon | ✗ | ✓ (P0.1, CI-enforced by `P4.1`) | ✓ |
| Plugins doing real work / honest gate | 21 + 4 | unchanged — the six Phase 2 items are **code** done, the re-audit needs a device | ≥ 28 |
| `:features` tests | 0 | **28** | 25+ ✓ |
| Detekt findings baselined | 606 (577 live) | **329** (core 77, features 169, ui 45, indra 38) | ≤ 300 |
| Dead translated strings | ~5,700 (16 locales) | 0 | 0 |
| Test suite total | 302 | **267** | 360+ |

---

## 8. Sequence & dependencies

```
Phase 0 (week 1)  ──►  Phase 1 (weeks 2-3)  ──►  Phase 2 (weeks 3-5)
                            │
                            └──►  Phase 3 (weeks 5-6, needs P1.1's seam)
Phase 4 ────────────────── parallel from week 2 ──────────────────►
```

Ordering rules, stated so they are not relitigated:

- **0 before anything**: every later "done when" is unverifiable on a build that cannot run.
- **1 before 2**: every Phase 2 item needs `/proc`, `/sys`, `/dev`, DNS and on-demand install.
- **3 after 1.1**: the `ToolRunner` seam and the scaffold migration touch the same files;
  doing them in separate passes doubles the conflict cost.
- **4 is parallel**: CI and OTA share no files with 0–3.

---

## 9. Risk register

| Risk | Mitigation |
|---|---|
| Alpine pin goes EOL / 404s | `P1.5` moves the pin into an asset; `min_app_version` prevents a silent downgrade |
| Bind mounts leak or stack | mounts created once at install, `umount` on uninstall, `P1.2` deletes per-run binds; verify with `mount \| wc -l` across 10 runs |
| Chroot breaks after an app update (package dir changes) | `P1.7` removes hardcoded package paths |
| Monitor mode attempted on a vendor driver that will hang the radio | keep `MonitorMode`'s `;`-separated restore-and-report chain; never `&&`; never test on `wlan0` of a known-bad device |
| OTA swap while a tool is running | `P0.2` makes `stopAllRunning()` real; OTA refuses the swap if a pid survives |
| Scope creep into a VM | decision table §1 is binding; a rootless engine stays "no" |
| Phase 1 changes break the default path | every engine call keeps a host-binary fallback; the daemon path stays the default for one release |

---

## 10. Definition of done (per task)

- [ ] Diff limited to the task's files; no drive-by refactors.
- [ ] `./gradlew test detekt assembleDebug` green; `checkApkArtifacts` green when the APK changes.
- [ ] No new detekt baseline entry (the task *is* `P3.4` if it removes one).
- [ ] User-typed fields that reach a shell go through `shellQuote` (or `command(argv)`); no new
      `mount … || true`, no new hardcoded `/data/user/0/<package>`.
- [ ] Verified on device when the task is user-visible (screenshot or ARTEMIS trace), and
      StrictMode-clean when the task touches threading.
- [ ] A new `AGENTS.md` gotcha line when the task introduces a trap that future work could re-break.
- [ ] The audit finding it closes is named in the commit message (`P0.2`, `P1.2`).

---

## 11. First three days (concrete)

1. `P1` — stand up `fetchCoreArchive` + `checkApkArtifacts`; prove a clean clone builds a
   working APK. (Nothing else matters until this is true.)
2. `P0.2` — `track()`/untrack, then a test that proves `stopAllRunning()` does something.
3. `P0.3` + `P0.4` — one captures directory, one quoting test for Arsenal.
4. `P3.1` spike — read `SmartToolBox` and write the `ToolRunner` interface *without* changing
   behaviour, so the seam is reviewed before it is used.
5. Then start `P1.2` (mounts) — it is the single change that turns the chroot into an engine.
