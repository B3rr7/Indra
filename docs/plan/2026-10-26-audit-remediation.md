# Indra — Audit remediation plan (2026-10-26)

Follow-up to the full codebase audit (`develop`, graph generation `2026-10-26T15:52Z`,
2,299 nodes / 8,139 edges, plus `:core:test` = green, 72 tests).

This document is the **execution plan**: workstreams, tasks, acceptance criteria,
verification steps and sequence. The audit findings themselves are summarised in
[Findings](#appendix-a--findings-index) so each task points at evidence.

---

## 1. Scope

**In scope**

- UI-thread correctness, daemon lifecycle robustness, honest result models.
- UI consistency: one scaffold/section pattern, one host-suggestion pattern, one
  output-console pattern across the 36 plugin screens.
- Theming/system integration (dark-mode contract, status-bar contrast).
- Dead code, dead permissions, dead Gradle dependencies.
- Test + tooling gaps that let the above survive (features layer has 0 tests).
- Daemon credential/socket hygiene (app-side first; native only in WS-7).

**Out of scope (deliberately)**

- New pentest capabilities (nmap alternatives, new binaries).
- Native core porting work, tool rebuilds, OTA protocol changes.
- Navigation 3 / Compose 1.7 migration, AGP 9 / Kotlin 2.x upgrades.
- Anything that requires re-running `indra/jni/build.sh` except WS-7.3 (one-line
  native change, optional).

**Non-negotiable principles** (from `AGENTS.md`, restated because most tasks touch UI):

1. **No fake results.** A missing tool shows why; it never renders plausible output.
2. **`null` ≠ `0`.** "Could not look" and "looked, found nothing" must not render the same.
3. **The `ToolResult` emission contract is law.** `streamedLine` for live lines,
   `!isStreaming` to finalise. Never `status == "running"` string literals.
4. **Nothing blocking on Main.** JNI, `su`, file walks, process wait → IO dispatcher.
5. **Every user-typed field that reaches `shell()` goes through `shellQuote`.**
   Do not regress this while refactoring command construction.

---

## 2. Baseline metrics (re-measure at the end)

| Metric | Now | Target |
|---|---:|---:|
| Kotlin LOC (`*/src/main`) | 18,775 | ≤ 18,000 (refactor removes duplication) |
| `Scaffold` + `TopAppBar` declarations in `features` | 36 | 1 (in `ui`) |
| Copy-pasted `refreshSuggestions()` | 14 | 0 |
| Copy-pasted `isAvailable()` gates | 26 | 0 |
| Call sites of the 6 shared `ui` components | 0 | ≥ 100 |
| Hardcoded Compose strings (`Text("…")`) | 173 | ≤ 20 (rest in `strings.xml`) |
| `rememberSaveable` / `SavedStateHandle` uses | 0 | forms + scroll state |
| Features-layer tests | 0 | ≥ 25 |
| detekt baseline entries (`features`) | 389 | ≤ 60 |
| Dead Gradle deps | 5 | 0 |
| Unused `uses-permission` entries | 5 | 0 |
| Blocking calls on Main (`isAvailable`) | 26 | 0 |

---

## 3. Workstreams

Effort scale: **S** ≤ 2 h, **M** ≤ half a day, **L** ≈ 1 day, **XL** > 1 day.

### WS-1 — Threading & lifecycle correctness (P0)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 1.1 | Add `SmartToolBox.requireTool(name): ToolHandle` as `suspend` (`withContext(IO)`), returning `ToolHandle.Ready(tool)` / `DaemonDown` / `Missing(name)` — the failure states carry the user-facing message, so "daemon down" and "handler missing" stop being six different sentences. | `core/.../SmartToolBox.kt` | No screen calls `isAvailable()`/`getTool()` directly | `rg "isAvailable\(\)\|getTool\(" features/src indra/src` → 0 (gates only) | S |
| 1.2 | Replace the 27 `runner == null \|\| !runner.isAvailable()` gates (26 plugin VMs + `TerminalViewModel`) with `requireTool(…)` + `if (gate !is ToolHandle.Ready)`. Gates that sat in a non-suspend `start()` moved **inside** `viewModelScope.launch`, and the "running"/"loading" state is set only after the gate, so a dead daemon cannot flash a running UI. | 26 VMs in `features/.../plugins/` + `indra/.../TerminalViewModel.kt` | Same copy for the same condition; no behaviour change otherwise | `:features:compileDebugKotlin`; device: kill daemon → Run shows the `ToolHandle` message, UI stays responsive | M |
| 1.3 | Rate-limit the daemon probe: `TtlProbe` caches the handler-check answer for 1 s (`PROBE_TTL_NANOS`) instead of re-probing on **every** call while `available == false`; `SmartTool.refresh()` / `SmartToolBox.reload()` still force a probe. | `core/.../TtlProbe.kt`, `SmartToolBox.kt` | Probe count ≤ 1 per tool per second with the daemon down (Home/Tools probe ~15 tools/refresh instead of 36) | `TtlProbeTest` (6 tests, counted lambda) | S |
| 1.4 | Bound `CoreManager.startDaemon()`: read the fork handshake and `waitFor` under `withTimeoutOrNull(20 s)` using `runInterruptible`, `destroyForcibly()` on expiry, and report the partial output. Same bound on the `su` kill in `stopDaemon()` (the socket probe stays the authority there). | `core/.../CoreManager.kt` | A `su` that never answers fails with a message instead of holding the daemon mutex forever | compile + detekt; device: revoke root → Start fails within 20 s | M |
| 1.5 | Make `Logger` file writing non-blocking: `Logger.d/i/w` enqueue a `(level, message, timestamp)` entry into a bounded (512, drop-oldest) channel drained in batches by one IO coroutine; timestamp formatting moved off the caller. `Logger.e` still writes synchronously (the lines that matter before a crash). | `core/.../Logger.kt` | A 10k-line capture does not do per-line disk I/O on the JNI callback thread | compile + detekt; manual `seq 1 10000` through the terminal with file logging on | M |
| 1.6 | Audit remaining Main-thread probes: constructor-time work is now only `File.absolutePath`/`exists`/`canExecute`; the six artifact gates (`isNucleiInstalled` etc.) already run inside coroutines; `TerminalViewModel`'s gate was still the old blocking one (fixed in 1.2). | `indra/.../TerminalViewModel.kt`, plugin VMs | No JNI/`su`/directory-walk call reachable synchronously from a screen action | source audit + 8.4 StrictMode | S |

### WS-2 — Honest results (P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 2.1 | Give nuclei tools a real console: `VulnScanner`/`WebTester`/`ExploitFinder` store streaming lines (`streamedLine`, bounded 500 lines) + a findings counter parsed from nuclei's `[critical]`/`[high]` output, and keep the final transcript for "copy/share". | `VulnScannerViewModel.kt:84-96`, `WebTesterViewModel.kt:90`, `ExploitFinderViewModel.kt:93-101` | Zero findings renders "No findings" + the real output, never one fake "Scan Result" row; output visible live | Device: scan `127.0.0.1` with nuclei templates → live lines + honest empty state | L |
| 2.2 | Normalise exit-status reporting: a tool that exited non-zero but produced findings (nuclei exits 1 on findings) must not read as failure. Add explicit per-tool success policy in the VM, not a global hack. | same 3 VMs | Documented policy per tool in KDoc; unit tests for the mapping | Unit tests | M |
| 2.3 | Replace the two `status == "running"` literals with `isStreaming`. | `PortScannerViewModel.kt:86`, `RouterPwnViewModel.kt:62` | `rg 'status == "running"'` → 0 | grep + unit test | S |
| 2.4 | Wire `TopologyViewModel` to real data **or** remove the plugin. Preferred: subscribe to `NetworkRepository.discoveredHosts` + last scan's `ToolResult.ports`, lay nodes out radially, label with MAC from `NeighborTable`. If deferred, remove the card + route so it stops promising a map. | `TopologyViewModel.kt`, `TopologyScreen.kt`, `PluginManager.kt`, `IndraNavHost.kt` | Either a populated graph after a Radar sweep, or no Topology card at all | Device: Radar sweep → open Topology → nodes drawn | L |
| 2.5 | Documents the "", "0 ports", "no hosts" triad: audit every screen's empty state against principle 2 (a real `0` vs `null`). | all plugin VMs | Table in the PR description: screen → empty-state wording → source of truth | Manual pass over 36 screens (Sprint 2 device sweep) | M |

### WS-3 — UI consistency: one scaffold, one console, one suggestion row (P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 3.1 | Add `PluginScaffold(title, onBack, actions, content)` to `ui/` instead of hand-rolled `Scaffold`+`TopAppBar` | `ui/.../components/PluginScaffold.kt` | New component + 3 pilot screens migrated (`PortScanner`, `Inspector`, `Traceroute`) | Screenshots before/after identical (device) | M |
| 3.2 | Migrate the remaining 33 screens to `PluginScaffold` (mechanical; one commit per 6–8 screens). | `features/.../plugins/*Screen.kt` | 36 → 1 scaffold declaration | `rg "Scaffold\(" features` → 1 | L |
| 3.3 | Add `LiveOutputCard(lines, autoFollow, onClear)` + `EmptyState(text, hint)` + `FormError(text)` to `ui/`; migrate the hand-rolled live-output/error blocks. | `ui/.../components/`, plugin screens | Errors and consoles look and behave the same everywhere | Device sweep; `rg "heightIn\(max = 160.dp\)"` → 1 | L |
| 3.4 | Delete the 6 unused shared components **or** use them (`ConfirmDialog` for destructive actions: MAC change, iptables flush, DNS redirect). Decide per component; no zombie APIs. | `ui/.../CommonComponents.kt` | Every remaining public component has ≥ 1 call site | `rg "<Name>\("` per component | M |

### WS-4 — Theming & system integration (P0/P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 4.1 | Fix the dark-mode contract. Pick one: (a) follow system, (b) always dark + correct bar contrast. Recommended: **always dark UI, correct bars** (matches the product identity), i.e. `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(TRANSPARENT), …)` and drop `windowLightStatusBar=true` / switch the window theme off `Theme.Material.Light`. | `MainActivity.kt:69`, `res/values/themes.xml`, `ui/.../Theme.kt:72` | On a light-mode device, status/nav icons are visible over the app surface | Screenshot, light + dark system theme | S |
| 4.2 | Optional but cheap: `SettingsRepository.appearance` (`system`/`dark`/`light`) → `IndraTheme(darkTheme = …)`; three-state control in Settings. | `SettingsRepository.kt`, `SettingsCards.kt`, `Theme.kt` | Preference survives restart; system default respected | Device | M |
| 4.3 | Enable `dynamicColor` behind the same appearance section (code path already exists, currently unreachable). | `Theme.kt:74-84` | Toggle visibly retints and reverts | Device (API 31+) | S |

### WS-5 — Form state & host suggestions (P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 5.1 | Replace the 14 non-scrollable `Row`-of-`AssistChip` blocks with one `HostSuggestions` component: `FlowRow` (wraps), cap 8 visible + overflow menu, tap-to-fill, long-press to copy. | `ui/.../components/HostSuggestions.kt` + 14 screens | No clipped chips with 20 hosts | Device: Radar sweep to 20 hosts → open Port Scanner | M |
| 5.2 | Cap and expire `NetworkRepository.discoveredHosts` (keep last 64, clear on network change / explicit action; `clearDiscoveredHosts()` currently has 0 callers). | `data/.../NetworkRepository.kt:24-38`, `NetworkRadarViewModel.kt` | List bounded; "Clear hosts" action in Radar | Unit test (cap + dedupe) | S |
| 5.3 | Persist form state: `SavedStateHandle` in each VM for target/iface/port/wordlist/outFile (or `rememberSaveable` for pure-UI fields). | 36 VMs (batch by group) | `adb shell am kill org.indra.android` mid-form → values restored | Device: type → kill → reopen | L |
| 5.4 | Keyboard affordances: numeric/uri/port keyboard types where relevant; `imeAction = Go` triggering the primary action; focus-advance order. | scan/crack screens | "Go" starts the scan; port field opens a numeric pad | Device | M |

### WS-6 — Dead code, dead permissions, dead deps (P2)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 6.1 | Remove unused backend API: `IndraSystem.updateNetworkStatus/updateTargets/targets/_networkStatus`, `CoreManager.initializeCore/shutdownCore/isMonitorModeSupported`, `PluginManager.getPluginsForTarget`, `AppModule.@ApplicationScope` (or wire the scope where a process-lifetime coroutine is genuinely needed — currently none). | listed files | `rg` shows 0 refs; build green | `./gradlew :core:test` | S |
| 6.2 | Remove unused `data` models (`Service`, `Exploit`, `Session`, `Target` where unreferenced) — keep the repository + the models it actually returns. | `data/.../Models.kt` | Each remaining model has a consumer | `rg "data.model"` | S |
| 6.3 | Drop unused permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE`, `READ_MEDIA_{IMAGES,VIDEO,AUDIO}` — **unless** 7.x adds the foreground service. Update `PermissionManager.getRequiredPermissions()` in the same commit (declared-but-not-requested and requested-but-undeclared both broke before). | `AndroidManifest.xml`, `PermissionManager.kt` | No permission requested that no feature uses; denial snackbar never fires spuriously | Device cold start on clean install | S |
| 6.4 | Drop unused Gradle deps: `msgpack-core`, `juniversalchardet`, `unbescape`, `preference-ktx`, `lifecycle-livedata-ktx` (verified 0 Kotlin refs). | `indra/build.gradle` | Deps gone, release APK smaller | `./gradlew :indra:assembleRelease` + size diff | S |
| 6.5 | Single source for plugin → route: add `pluginId` to `Screen`, delete the 36-branch `when` in `IndraNavHost`, fail loudly (or unit-test) on an unmapped plugin. | `IndraNavHost.kt:146-187`, `PluginManager.kt` | One table; unknown plugin is a test failure, not a silent dead card | New unit test: every `PluginManager` id maps to a `Screen` | M |
| 6.6 | Introduce `gradle/libs.versions.toml` for the Compose BOM / Hilt / Kotlin versions duplicated in 4 build files. | `gradle/`, `*/build.gradle` | Versions defined once | `./gradlew assembleDebug` | M |

### WS-7 — Long-running work & daemon hygiene (P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 7.1 | **Decision point** — foreground service. Either (a) remove the dead permissions (6.3) and accept LMK risk, or (b) add `ToolSessionService` (`foregroundServiceType="specialUse"`, notification "Tool running" + Stop) driven by a small `ActiveToolRegistry` in `core`. Recommend (b) for 30-min tools. | `core/.../ActiveToolRegistry.kt`, `indra/.../ToolSessionService.kt`, manifest, `SmartToolBox.execute` | Tool survives backgrounding + survives a low-memory kill of the Activity; Stop works from the notification | Device: start Radar, background app 10 min, `adb shell am kill` activity → tool still running, notification present | L |
| 7.2 | App-side credential hardening: stop hardcoding `android`/`DEADBEEF` in `CoreManager`; generate a per-install random secret at core-install time and write it into `<core>/users` (mirroring what `build.sh` does), keeping a compatibility fallback for already-installed cores. | `CoreManager.kt:54-55,401-437,626-637`, `KnownIssues`/install path | Fresh install uses a unique credential; daemon auth still works; old installs keep working until reinstall | Device: install core → `cat users` via root ≠ DEADBEEF; tools still run | M |
| 7.3 | Native (optional, one line each): gate `chmod(SOCKET_PATH, 0666)` on an explicit debug define instead of `!NDEBUG`; document the `umask(0)` consequence for files the daemon creates. Requires `./build.sh cores` + asset refresh. | `indra/jni/cSploitDaemon/cSploitd.c:190-192`, `build.sh` | Release core binds 0660 | `ls -l` socket on device | M (+ native build) |

### WS-8 — Tests & tooling (P1)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 8.1 | Add `features/src/test` with JVM tests using a **fake `SmartToolBox`** (interface or `open` class + constructor injection of a `Flow` factory): one contract test per VM family (scan, long-running with Stop, install-gated, streaming console). Start with 6 VMs, then extend to 36. | `features/src/test/...`, maybe `core/.../ToolRunner.kt` seam | ≥ 25 tests; covers final-emission vs streaming, error text, `isStreaming` handling | `./gradlew test` | XL (split over the sprint) |
| 8.2 | Add `testTag` to interactive nodes (start/stop buttons, target field, result list) on the 6 most-used screens; write 3 Compose UI tests (Port Scanner happy path, Stop, unavailable-tool state). | screens, `indra/src/androidTest` | 3 UI tests green on device/emulator | `./gradlew connectedDebugAndroidTest` | L |
| 8.3 | Re-baseline detekt after WS-1/WS-3/WS-6 (`./gradlew detektBaseline`) so the features baseline shrinks (389 → ≤ 60) and new violations actually fail. Never hand-edit the baseline. | `*/detekt-baseline.xml` | `./gradlew detekt` green; count recorded in PR | gradle | S |
| 8.4 | Add a debug-only StrictMode install (`detectAll` + `penaltyLog`) behind `BuildConfig.DEBUG` so Main-thread I/O regressions surface immediately. | `IndraApplication.kt` | Logcat shows no `StrictMode policy violation` on a full nav sweep | Device | S |
| 8.5 | CI: add `--no-daemon :indra:assembleDebug` + artifact upload; keep `detekt` and `verify-release-keeps.sh`. Consider a nightly `connectedAndroidTest` once 8.2 lands. | `.github/workflows/ci.yml` | Debug APK downloadable per run | CI | S |

### WS-9 — Localization (P2)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 9.1 | Move the 173 Compose strings to `strings.xml`, delete the cSploit-era entries (`indra.net` URLs, `LD_LIBRARY_PATH bug`, `arm_error*`, `fatal_error_html*`, splash/notification leftovers). | `res/values/strings.xml`, all screens | `rg 'Text\("[A-Z]'` ≤ 20 (intentional literals only) | gradle + grep | L |
| 9.2 | Add `values-de` (or another locale) as a proof the pipeline works; set `translatable` correctly (`app_name` etc.). | `res/values-<locale>/` | Switching device language localizes UI | Device | M |

### WS-10 — Adaptivity (P2)

| ID | Task | Files | Acceptance | Verify | Effort |
|---|---|---|---|---|---|
| 10.1 | Introduce `WindowSizeClass` (`material3-window-size-class`) and a `ContentWidth` wrapper (max ~720 dp, centered) so tablets/landscape don't stretch forms edge-to-edge. | `ui/.../`, `MainActivity.kt` | On a 2-pane-capable width, content is centered and readable | Resizable emulator screenshots (compact/medium/expanded) | M |
| 10.2 | Two-pane for list/detail screens where it matters (`NetworkRadar`, `Tools`, `Handshakes`, `Inspector`): results pane beside the form on `≥ Medium`. | 4 screens | Expanded width shows side-by-side; compact unchanged | Emulator screenshots | L |
| 10.3 | Landscape/height-class check on all screens: primary action always reachable without scrolling past a keyboard. | screens | No screen hides its primary action in landscape + IME | Device sweep | M |

---

## 4. Sequence (4 sprints, ~2 weeks of focused work)

**Sprint 1 — "Nothing blocks the UI thread" (3 days)**
`1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 1.6 → 8.4`
Exit criteria: audit of all 36 screens shows zero Main-thread JNI/`su` probes;
`:core:test` + new `SmartToolBox` probe-count test green; device sweep with StrictMode clean.

**Sprint 2 — "Honest and consistent" (4 days)**
`4.1 → 2.3 → 2.1 → 2.2 → 2.5 → 3.1 → 3.3 → 5.1 → 5.2 → 6.3`
Exit criteria: nuclei/msf screens show live output and real empty states; one visible
error style and one live-output console; no clipped chips; permissions match reality.
Device verification pass on moto g60 (artemis) with screenshots for each changed screen.

**Sprint 3 — "One scaffold, one table" (4 days)**
`3.2 (all remaining screens) → 3.4 → 6.5 → 6.1 → 6.2 → 5.3 → 5.4 → 8.1 (first 6 VMs)`
Exit criteria: 1 `Scaffold` in `features`, plugin→route table unit-tested, dead backend
API gone, form state survives process death, 25+ feature tests green.

**Sprint 4 — "System integration & hygiene" (3 days)**
`7.1 (decide/implement) → 7.2 → (7.3 optional) → 6.4 → 6.6 → 9.1 → 10.1 → 8.2 → 8.3 → 8.5 → 10.2/10.3`
Exit criteria: foreground service or permissions removed; per-install daemon secret;
APK deps trimmed; strings externalized; adaptive layout on expanded width; detekt
baseline shrunk; CI produces a debug artifact.

---

## 5. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `PluginScaffold` migration changes per-screen behavior (29 screens) | Medium | High | Pure-presentation refactor; 3-screen pilot; before/after screenshots per batch; no logic edits in the same commit |
| `requireTool` refactor masks an "unavailable" state that a screen handled specially | Medium | Medium | Keep messages distinguishable (daemon down vs artifact missing); contract tests in 8.1 run before the sweep |
| Foreground service keeps a root child alive after the user leaves | Medium | High (battery, surprise traffic) | Service binds to `ActiveToolRegistry`; leaving the screen still cancels the flow unless the tool is explicitly "kept running"; notification Stop always present |
| Per-install daemon credential breaks already-installed cores | Medium | Medium | Compatibility path: keep `DEADBEEF` fallback for cores whose `VERSION` predates the change; migrate on next core install |
| `Logger` async writer loses the last lines on crash | Low | Medium | Flush on `E` level and on `Application.onTrimMemory`; bounded channel keeps ordering |
| Localization churn conflicts with the scaffold migration | Medium | Low | Do WS-9 strictly after WS-3, in its own commits |
| Native rebuild (7.3) pollutes the sprint | Low | Medium | Optional, last; if skipped, record the socket-permission caveat in `AGENTS.md` |

---

## 6. Definition of done (per task)

- [ ] Diff limited to the task's files; no drive-by refactors.
- [ ] No new detekt violation (baseline untouched unless the task *is* 8.3).
- [ ] `./gradlew test detekt` green; `assembleDebug` builds.
- [ ] If UI-touching: verified on device (screenshot or ARTEMIS trace) in light **and** dark system theme.
- [ ] If threading-touching: StrictMode clean on the affected screen.
- [ ] If behavior-touching: an `AGENTS.md` "Critical Gotchas" line added when it is a trap future work could re-break.
- [ ] Audit finding(s) referenced in the commit message (`WS-1.2`, `B1`).

---

## 7. TODO — ordered execution checklist

### Sprint 1 — threading & lifecycle

- [x] 1.1 `SmartToolBox.requireTool()` (suspend, IO) — S
- [x] 1.2 Replace the 26 `isAvailable()` gates (+ `TerminalViewModel`, the 27th, in `indra/`) — M
- [x] 1.3 TTL-cache the `SmartTool` probe (no re-probe storm when down) — S
- [x] 1.4 Timeout + force-destroy in `CoreManager.startDaemon()` **and** `stopDaemon()` — M
- [x] 1.5 Async `Logger` writer (no disk I/O on JNI callback thread) — M
- [x] 1.6 Main-thread probe audit of remaining VMs (result: only `File.exists/canExecute` path building on Main; `TerminalViewModel` was a real straggler and is fixed) — S
- [x] 8.4 Debug StrictMode install — S
- [x] Sprint 1 device sweep + StrictMode clean (moto g60, 2026-10-27): 0 `StrictMode policy violation`
  after the cold-start fixes listed below; daemon-down gate message on Port Scanner; `seq 1 10000`
  through the Terminal with file logging on (7 189 log lines / 380 KB, no Main-thread writes)
- [x] Sweep findings fixed: `IndraApplication` prefs + `Logger.setFileLogging` moved to an IO scope,
  JNI bridge preloaded on IO (`System.loadLibrary` was a ~100 ms Main-thread read), `SettingsRepository.corePath`
  lazy (`getFilesDir` created the dir on Main)

### Sprint 2 — honest results & consistency
- [x] 4.1 Fix status/nav bar contrast; document dark-only contract — S (dark window theme, `SystemBarStyle.dark`,
  `values-night` theme/splash overrides deleted; verified on a light-mode device)
- [x] Honest plugin-card states (sweep finding, part of WS-2): `PluginAvailability.PluginStatus` names the real
  blocker (`daemon not running` / `not in native core` / `component not installed` / `needs root` / unsupported)
  instead of one `not in native core` label for everything, and a down daemon keeps the card openable so the
  screen's gate explains the fix; daemon liveness also covers a daemon that died under the app
  (`IndraClient.noteStartResult` + `startFailed`, since the native `connected` flag never clears)
- [x] 2.3 Replaced both `status == "running"` literals (`PortScannerViewModel`, `RouterPwnViewModel`)
  with `isStreaming`; `ToolResultTest` now pins that only `running` streams and that the final
  emissions never append a line — S
- [x] 2.1 Real console + findings for the nuclei/msf screens — L. `Nuclei.parseFindings` reads
  nuclei's `[severity] …` lines (case-sensitive, so `[INF]` banners never count as findings),
  `Nuclei.summary` renders `3 findings · 1 critical · 1 high`; `MsfSearch.parse` reads
  msfconsole's fixed-width module table by header offsets (space-splitting merged `Rank` and
  `Check`); all three screens now append `streamedLine` to a 500-line console and keep the full
  transcript for Copy. `nuclei` gained `-silent -nc` so stdout is one line per finding with no
  ANSI codes
- [x] 2.2 Per-tool success policy — M. `Nuclei.interpret` knows nuclei exits **1** when it found
  something: exit 1 + findings is a success (previously every finding was hidden behind
  "nuclei exited with code 1"), exit 1 with no recognised finding line is an explicit failure,
  and any other non-zero keeps the tool error. Zero findings renders "No findings", never a
  fabricated result row; a failed run renders the error and *no* findings verdict
- [x] 2.5 Screen-by-screen empty-state audit (null vs 0) — M. Six screens reported "could not
  look" and "looked, found nothing" the same way (or the reverse). Each now carries a `finished`
  (or `hasRun`) flag so the UI can tell the two apart, and a real zero renders a neutral empty
  state instead of a red error or silence:
  - **Port Scanner**: zero open ports was `error = "No open ports found"` (red, reads like a
    failure) → neutral `No open ports in <range>` once `finished`.
  - **Traceroute**: zero hops was `error = "No hops reported"` → neutral empty state.
  - **Inspector**: `osInfo` doubled as a message field (`osInfo = "No open ports found"`), so
    "no open ports" and "no OS match" were the same string, and both were indistinguishable from
    "not inspected yet" → `finished` plus two separate empty states (a host with no open ports
    gets no OS fingerprint, which is not the same as a failed fingerprint).
  - **Session Hijack**: the header printed `Captured sessions: 0` before anything ran →
    four states (not started / listening / captured N / captured nothing).
  - **Packet Capture**: the header printed `Captured (0 lines)` before Start →
    "Not capturing yet", and a finished capture with no lines now says so.
  - **WiFi Scanner**: a zero-result scan rendered nothing at all → `finished` + neutral
    "No networks found" and an "The scan did not complete" variant.
  Audit table (all 36 plugin screens) and the remaining silent consoles are recorded in
  [`docs/audit/2026-10-27-empty-states.md`](../audit/2026-10-27-empty-states.md)
- [x] 3.1 `PluginScaffold(title, onNavigateBack, actions, content)` in
  `ui/components/PluginScaffold.kt`, with Port Scanner / Inspector / Traceroute migrated (their
  hand-rolled `Scaffold` + `TopAppBar` + `IconButton` + `ArrowBack` blocks are gone, along with
  the `@OptIn(ExperimentalMaterial3Api)` that only existed for `TopAppBar`). Device screenshots
  before/after are pixel-identical; `Trace` still runs and the empty state still renders. The
  three `LongMethod` baseline IDs were re-keyed (the signature string changed) — same rule, same
  function, no new suppression — M
- [x] 3.3 `LiveOutputCard` / `EmptyState` / `FormError` are now the only error and console
  surfaces — L
  - **Errors**: all 28 hand-rolled error `Text`s (three shapes across 26 screens) are
    `uiState.error?.let { FormError(it) }`. `rg "color = MaterialColor\.colorScheme\.error"` in
    `features/.../plugins` now matches exactly one line — Wifite's monitor-mode danger notice,
    which is a warning affordance, not an error. RouterPwn's "Found N open services" header
    stopped using the error colour: a completed scan with findings is not a failure.
  - **Consoles**: 17 screens moved onto `LiveOutputCard` (Masscan, Gobuster, SearchSploit,
    Arsenal, Iptables, Bettercap, MacChanger, Reaver, Responder, DNS Spoofer, One-Tap MITM, MITM,
    Packet Capture, Port Scanner, WiFi Scanner, WiFi Audit, Wifite), on top of the three WS-2.1
    screens — 20 total. Each gains the same header (`· running` while live), Copy button and
    auto-follow; `heightIn(max = 160.dp)` and every hand-rolled `Card { LazyColumn { Text(…,
    FontFamily.Monospace) } }` are gone (`rg "heightIn\(max"` → 0).
  - **Auto-follow needs a monotonic key, not `lines.size`**: capped lists stop growing at the cap,
    so `WifiAuditUiState.lineCount` was added (Mitm's `logCount` and PacketCapture's `packetCount`
    already existed) and the uncapped consoles pass `lines.size`.
  - **Deliberately not migrated**: the static one-shot *result* cards (hydra transcript in Login
    Cracker / Credential Tester, hping3's stats in Packet Forger, DuckyScript's parse summary,
    Inspector's OS guesses, Traceroute's hops, Network Radar's hosts, Session Hijack's sessions).
    They are verdicts or structured rows rendered in a page-scrolling form, not live consoles;
    boxing them into a fixed-height console would hide content, not unify it.
  - Device-verified (moto g60): Masscan (`Output` + Copy + placeholder, then the real
    `FAIL: could not determine default interface` in the console and `masscan exited with code 1`
    in the shared error style), IPTables (`-L -n -v` real listing), Packet Capture
    (`Packets · running` while streaming, auto-followed, `Captured (500 lines)` after Stop at the
    cap), WiFi Scanner (placeholder `The scan did not complete` + the `iw` error line), Port
    Scanner (160 dp live console + neutral `No open ports in 1-9000`), 0 StrictMode violations.
- [x] 5.1 `HostSuggestions` (FlowRow, capped) on 14 screens — M. `ui/components/HostSuggestions.kt`
  replaces the identical non-scrollable `Row { AssistChip }` block on Credential Tester, Exploit
  Finder, Gobuster, Inspector, Login Cracker, Masscan, MITM, One-Tap MITM, Packet Forger, Port
  Scanner, RouterPwn, Traceroute, Vulnerability Scanner and Web Tester:
  - `FlowRow` wraps instead of clipping, so a long host list no longer runs off the right edge
    (the 5.1 bug: chips past the screen width were unreachable).
  - At most `MAX_VISIBLE_HOST_SUGGESTIONS` (8) chips; the rest sit behind a `+N more`
    `DropdownMenu`. The split is the pure `HostSuggestionLayout` in `core/system`, unit-tested by
    `HostSuggestionLayoutTest` (7 tests: empty, under, exactly at, one past, 20 hosts, order
    preserved, explicit cap).
  - Tap fills the field; long-press copies the host with a haptic tick. `AssistChip` has no
    long-press slot, so the chip is a `Surface` + `combinedClickable` styled like an assist chip,
    and it dims at Material's disabled alpha when `enabled = false` (Masscan / MITM / One-Tap MITM
    pass `!isRunning`, as before).
  - The `if (suggestions.isNotEmpty())` guard moved inside the component; each call site is now
    three lines (`hosts`, `onSelect`, optional `enabled`).
  Device-verified (moto g60): two real chips from the mobile-data interface, tap filled
  `10.161.185.30`, long-press + paste yielded `10.161.185.29`; with 20 hosts temporarily seeded in
  the VM (reverted before commit — no fake data shipped) the row wrapped to two lines as 7 chips +
  `+15 more`, the menu listed 10.9.9.6 … 10.9.9.20, and tapping an entry filled the field. 0
  StrictMode violations.
- [ ] 5.2 Cap/expire `discoveredHosts` + clear action — S
- [ ] 6.3 Remove unused permissions (unless 7.1) — S
- [ ] Sprint 2 device pass: screenshots light+dark — M (in progress; the WS-2.1/2.2/2.3 screens
  are verified, the rest land with the remaining tasks)

Device-pass findings (Sprint 2):
- **`ARTIFACT_MISSING` cards were dead ends** (regression from the Sprint 1 honest-card work):
  the card was disabled *because* nuclei / msf / gobuster was not installed, while the installer
  for that component lives inside the plugin screen — so it could never be installed. Fixed:
  `ARTIFACT_MISSING` is openable like `DAEMON_DOWN`; the label still says `component not installed`.
- **Alpine chroot is not runnable** (found while faking an msf chroot for the Exploit Finder test,
  still open): every file in `files/alpine` extracted as mode `600` — `bin/busybox`, `lib/ld-musl-*`
  included — so `chroot` cannot exec anything. `installAlpine` only sets `bin/sh` executable and
  that symlink target did not survive either; the extractor needs to restore tar modes (or a
  recursive `chmod` post-pass). Not fixed in Sprint 2 (it belongs with the Alpine/WS-7 work).
- **WiFi Scanner could not run on the tested device** (`2.5` sweep) — **fixed**: the screen ran
  `iw dev <iface> scan` through `shell`, but neither the native core (tools list: aircrack-ng,
  arpspoof, ettercap, fusemounts, gobuster, hping3, hydra, masscan, network-radar, nmap, reaver,
  tcpdump) nor the ROM ships `iw`, so every scan died with
  `shell exited with code 127: /system/bin/sh: iw: inaccessible or not found` while the card
  stayed openable because `requires = "shell"` is satisfied.
  The scanner now reads Android's own Wi-Fi service instead of a bundled binary:
  `cmd wifi status; cmd wifi start-scan; sleep 3; cmd wifi list-scan-results` through `shell`
  (root). No binary to ship, and it lists exactly the access points the system Wi-Fi picker
  shows. `WifiScan.parse` (pure, `core/system`, 12 tests in `WifiScanTest`) reads the table —
  the SSID column is not fixed-width (names contain spaces), so the numeric prefix is read
  positionally and the trailing `[...]` flag list is split off the end, and the flags are
  reduced to a readable label (`WPA2/WPA3`, `Open`, `OWE`, …). Results are sorted strongest
  first and rendered as two console lines (name, then
  `bssid · chN · −N dBm · security`). The interface field is gone — `cmd wifi` takes no
  interface. A radio that is off is a real `error` ("Wi-Fi is turned off — enable Wi-Fi to scan
  for networks"), not a silent empty list.
  Device-verified (moto g60): two live access points with SSID/BSSID/channel/signal/security,
  and the Wi-Fi-off path after `svc wifi disable`. 0 StrictMode violations.

Wireless feature audit (2026-10-27, out-of-band — not a plan task):
- All 15 wireless plugins were opened and exercised on the moto g60. Full table, evidence and
  findings: `docs/audit/2026-10-27-wireless-features.md`.
- **Working, device-verified**: WiFi Scanner, WiFi Audit (needs a `.cap`), Handshakes, Packet
  Capture (managed mode — 124 real lines on `rmnet_data2`), Network Radar, Session Hijacking,
  DNS Spoofing (start → iptables REDIRECT installed → removed → stop, rule verified with
  `iptables -t nat -S`), MAC Changer, GeoMac (valid KML), WhisperPair (BLE scan).
- **Monitor mode cannot work at all** (the one real blocker): `iw` ships in neither the core nor
  the ROM, so Wifite's stage 1, Packet Capture's handshake capture and Reaver are unreachable.
  Two honest paths recorded in the audit: vendor `libnl` + build `iw` into the core, or gate the
  two screens and drop the affordance.
- **Fixed on the way** — the old switch chain was
  `ip link set <if> down && iw dev <if> set type monitor && ip link set <if> up`, and `&&` stops
  at the first failure: with `iw` missing the interface was left **down** (verified on `dummy0`:
  `UP` → `DOWN`, exit 127). On `wlan0` that takes Wi-Fi off the air. `core/system/MonitorMode.kt`
  (pure, 8 tests) uses `;`, always restores the link and keeps `iw`'s exit status; Packet Capture
  and Wifite now report "Monitor mode needs `iw`, which the native core does not ship — the
  interface was left in its previous mode." Device-verified with `dummy0` forced `DOWN` and back
  to `UP`.
- **Fixed on the way** — WhisperPair never cleared `error` in `startScan()`, so the screen read
  `Scanning…` **and** `Bluetooth is disabled` after the radio was turned back on.
- **Open** — Reaver has no monitor-mode precondition (its field says "Interface (monitor mode)"
  and it starts regardless); Bettercap needs its ~67 MB artifact; MITM/One-Tap MITM were not run
  (arpspoof hard-resets this phone, 3/3).
- **Open, pre-existing** — one `DiskReadViolation` (69 ms) when Wifite opens: `defaultWordlist()`
  → `context.filesDir` runs in the ViewModel constructor on Main. Same shape in
  `WifiAuditViewModel.init` and `PacketCaptureViewModel`'s `capturePath()` initialiser. The
  systemic fix is to resolve those three paths once in `IndraApplication.startupScope` and serve
  cached strings; belongs with the Sprint 4 "nothing on Main" work, not with this audit.

All-tools audit (2026-10-27, out-of-band — not a plan task):
- All 37 registered plugins were opened on the moto g60 and driven far enough to tell real
  work from advertising. Full table, per-plugin evidence and findings F1–F7:
  `docs/audit/2026-10-27-all-tools.md`.
- **Nothing in the tree fabricates a result.** 21 plugins do real work on the device, 4 are
  honest gates for components that are genuinely not installed (nuclei templates, msf chroot,
  ExploitDB, bettercap), 3 are blocked by the missing `iw`/monitor mode, 2 are dead
  (`responder`, `topology`), 2 were not run (arpspoof hard-resets this phone).
- **Fixed — nuclei could never start**: it creates `~/.config/nuclei` before reading anything
  and the daemon's `HOME` is `/` (read-only), so every run died with
  `mkdir /.config: read-only file system` (and `-nc` does not skip the directory).
  `SmartToolBox.nuclei` now runs `mkdir -p <filesDir>/home && HOME=<filesDir>/home …` through
  `shell()`, which grew `label`/`timeoutMs` parameters so the 15-minute scan budget survives.
- **Fixed — one refused start wedged every gate**: `isDaemonConnected()` is client-side
  (`!hasStartFailed() && connected && authenticated`) and `startFailed` latched with no reset,
  because the gate itself refused to attempt another start. With `indrad` alive, Masscan and
  Packet Forger both answered "Native daemon is not running" and every later gate did too until
  the app was force-stopped. New `CoreManager.reconnect()` (attach-only, no `su`, returns
  immediately when the socket file is gone) is called once from `requireTool`. Verified: Port
  Scanner → IPTables back to back now lists real iptables chains.
- **Open — Go binaries cannot resolve DNS in the daemon**: Android has no `/etc/resolv.conf`,
  so Go's pure resolver falls back to `[::1]:53` → `connection refused`. Gobuster works for IP
  targets and fails on hostnames; nuclei additionally has no templates (`installNuclei` ships
  only the binary, and `-update-templates` needs DNS) → `no templates provided for scan`. The
  recommended fix is to install templates through the app's own OkHttp (Android resolver).
- **Open — masscan cannot pick an interface**: `could not determine default interface`; `-e
  rmnet_data2` hits libpcap's `LINUX_SLL` (`arptype 519 not supported`), `-e lo` opens but
  sends 0 pps. Needs `-e <active iface>` to work on Wi-Fi (unverifiable here: `wlan0` is
  unassociated and the only in-range APs are off-limits).
- **Open** — `responder` is registered but hard-blocked as `UNSUPPORTED` while
  `ResponderViewModel` is a working LLMNR listener (dead code: delete it or unblock it behind
  Wi-Fi + location permission); `topology` still has no data source (`2.4`); Arsenal ships no
  templates (works, but an empty list on a fresh install).

### Sprint 3 — consolidation
- [ ] 3.2 Migrate remaining 33 screens to `PluginScaffold` — L
- [ ] 3.4 Resolve the 6 shared components (use or delete) — M
- [ ] 6.5 Plugin→route single table + unit test — M
- [ ] 6.1 Delete unused backend API (`IndraSystem`, `CoreManager`, `PluginManager`, `@ApplicationScope`) — S
- [ ] 6.2 Trim unused `data` models — S
- [ ] 5.3 `SavedStateHandle` form persistence (batched) — L
- [ ] 5.4 Keyboard types + `imeAction = Go` — M
- [ ] 8.1 Feature-contract tests (first 6 VMs → 25+ tests) — XL

### Sprint 4 — system integration & hygiene
- [ ] 7.1 Decide: foreground service (`ToolSessionService`) or drop permissions — L
- [ ] 7.2 Per-install daemon credential + compatibility path — M
- [ ] 7.3 (optional) native socket-permission gate + core rebuild — M
- [ ] 6.4 Drop unused Gradle deps — S
- [ ] 6.6 Version catalog — M
- [ ] 9.1 Strings → `strings.xml`, purge cSploit-era entries — L
- [ ] 10.1 `WindowSizeClass` + centered content width — M
- [ ] 8.2 `testTag` + 3 Compose UI tests — L
- [ ] 8.3 Re-baseline detekt (features ≤ 60) — S
- [ ] 8.5 CI debug artifact — S
- [ ] 10.2 Two-pane on 4 screens — L
- [ ] 10.3 Landscape/IME sweep — M
- [ ] Final: re-measure the baseline table in §2, update `AGENTS.md`, close the audit

---

## Appendix A — Findings index

Referenced as `B*` (backend) and `F*` (frontend) from the audit report.

| ID | Finding | Task |
|---|---|---|
| B1 | Blocking JNI `isAvailable()` on Main in 26 VMs | 1.1, 1.2, 1.3 |
| B2 | `startDaemon()` unbounded read/wait under mutex | 1.4 |
| B3 | Static `DEADBEEF` credential + `chmod 0666` socket | 7.2, 7.3 |
| B4 | No foreground service despite 30-min tools | 7.1, 6.3 |
| B5 | Synchronous per-line file logging on JNI thread | 1.5 |
| B6 | 500-char single-finding result models (nuclei/msf/webtester) | 2.1, 2.2 |
| B7 | `status == "running"` literals | 2.3 |
| B8 | `installCore()` leaves stale binaries; symlinks skipped | backlog (low value) |
| B9 | Dead backend API + unused DI scope | 6.1, 6.2 |
| B10 | Cross-thread `StateFlow` read-modify-write | folded into 5.2 audit |
| F1 | Non-scrollable suggestion chip rows ×14 + unbounded host list | 5.1, 5.2 |
| F2 | No `rememberSaveable`/`SavedStateHandle` | 5.3 |
| F3 | Topology screen has no data source | 2.4 |
| F4 | No adaptive layout | 10.1–10.3 |
| F5 | 173 hardcoded strings, dead `strings.xml` | 9.1, 9.2 |
| F6 | No IME actions / keyboard types | 5.4 |
| F7 | No `testTag`, 2 instrumented tests | 8.2 |
| F8 | Inconsistent error surfaces | 3.3, 3.4 |
| — | Always-dark theme + light window theme mismatch | 4.1, 4.2, 4.3 |
| — | 389 detekt suppressions in `features`, 0 tests | 8.1, 8.3 |
| — | 5 unused Gradle deps | 6.4 |
| — | 5 unused permissions | 6.3 |
| — | Two parallel registries (PluginManager ↔ nav `when`) | 6.5 |

## Appendix B — Files by blast radius (touch order)

1. `core/src/main/java/org/indra/android/core/system/SmartToolBox.kt` (1.1, 1.3)
2. `core/.../CoreManager.kt`, `core/.../Logger.kt` (1.4, 1.5, 7.2)
3. `ui/src/main/java/org/indra/android/ui/components/*` (3.1, 3.3, 5.1, new files)
4. `features/.../plugins/*ViewModel.kt` ×36 (1.2, 2.1–2.3, 5.3)
5. `features/.../plugins/*Screen.kt` ×36 (3.2, 3.3, 5.1, 5.4)
6. `indra/.../ui/navigation/*`, `MainActivity.kt`, `res/values*` (4.1, 6.5, 9.1)
7. `*/build.gradle`, `gradle/libs.versions.toml`, `AndroidManifest.xml` (6.3–6.6)
8. `indra/jni/cSploitDaemon/cSploitd.c` (7.3, optional)
