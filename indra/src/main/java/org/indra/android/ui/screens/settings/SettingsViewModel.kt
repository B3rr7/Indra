package org.indra.android.ui.screens.settings

import android.os.SystemClock
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.data.bundle.BundleOp
import org.indra.android.core.data.bundle.BundleUpdateManager
import org.indra.android.core.system.AppUpdateManager
import org.indra.android.core.system.CoreManager
import android.content.Context
import org.indra.android.core.system.LogExporter
import org.indra.android.core.system.CrashReporter
import org.indra.android.core.system.IndraWorkspace
import org.indra.android.core.system.Logger
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.NativeCoreAction
import org.indra.android.core.system.NativeCoreState
import org.indra.android.core.system.OtaUpdateManager
import org.indra.android.core.system.RootAccess
import org.indra.android.core.system.RootManager
import org.indra.android.core.system.wallpaper.WallpaperDim
import org.indra.android.core.system.wallpaper.WallpaperState
import org.indra.android.core.system.wallpaper.WallpaperStore
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class ToolStatus(
    val name: String,
    val available: Boolean,
)

/** One runtime-updatable bundle channel as the card draws it. */
data class BundleUiInfo(
    val id: String,
    val label: String,
    /** Installed version, or null when the bundle is not installed. */
    val installedVersion: String? = null,
    /** Where the installed tools came from: "APK" or "downloaded" (tools channel only). */
    val source: String? = null,
    /** Version offered by the latest release, or null when the channel is up to date. */
    val availableVersion: String? = null,
    val busy: Boolean = false,
    /** Progress/status line for the channel, or null when idle. */
    val status: String? = null,
)

data class SettingsUiState(
    val httpProxyPort: Int = 8080,
    val httpServerPort: Int = 8081,
    val httpsRedirectorPort: Int = 8082,
    val msfRpcPort: Int = 55553,
    val version: String = "2.0.0",
    val coreVersion: String = "Not installed",
    val daemonRunning: Boolean = false,
    /** The one state the Native Core card draws, and the source of its single button's action. */
    val coreState: NativeCoreState = NativeCoreState.NotInstalled,
    /** Action currently in flight, or null when the lifecycle button is idle. */
    val coreBusyAction: NativeCoreAction? = null,
    val coreMessage: String? = null,
    /** A failed lifecycle step. Kept apart from [coreMessage] so it renders as an error, not a note. */
    val coreError: String? = null,
    val otaChecking: Boolean = false,
    val otaUpdating: Boolean = false,
    val otaUpdateAvailable: Boolean = false,
    val otaMessage: String? = null,
    val otaManifestUrl: String = "",
    val otaAllowTestHosts: Boolean = false,
    val toolStatus: List<ToolStatus> = emptyList(),
    val rootlessMode: Boolean = false,
    val wakeLockEnabled: Boolean = true,
    val alpineInstalled: Boolean = false,
    val alpineBusy: Boolean = false,
    val alpineMessage: String? = null,
    val debugErrorLogging: Boolean = false,
    /** The user's app background, or none. */
    val wallpaper: WallpaperState = WallpaperState(),
    /** A failed wallpaper pick, shown under the card. */
    val wallpaperError: String? = null,
    /** True while an image is being copied and re-encoded. */
    val wallpaperBusy: Boolean = false,
    val crashLogCount: Int = 0,
    val debugLogSizeBytes: Long = 0,
    val allFilesAccess: Boolean = false,
    val indraDirPath: String = "",
    val workspacePresent: List<String> = emptyList(),
    val workspaceMissing: List<String> = emptyList(),
    val workspaceCreating: Boolean = false,
    val storageMessage: String? = null,
    val appUpdateChecking: Boolean = false,
    val appUpdateAvailable: Boolean = false,
    val appUpdateVersion: String? = null,
    val appUpdateMessage: String? = null,
    val isRooted: Boolean = false,
    val rootType: String = "No root",
    /** How to obtain/repair root, or null when there is nothing to fix (see RootAccess). */
    val rootGuidance: String? = null,
    /** Name of the root manager app that can grant root ("Magisk", "KernelSU", …). */
    val rootManagerLabel: String = "root manager",
    /** Runtime bundle channels (tools, msf) and their update state. */
    val bundles: List<BundleUiInfo> = emptyList(),
)

/** Port fields the daemon tools read from [SettingsRepository]. */
enum class PortField(
    val label: String,
) {
    HTTP_PROXY("HTTP Proxy Port"),
    HTTP_SERVER("HTTP Server Port"),
    HTTPS_REDIRECT("HTTPS Redirector Port"),
    MSF_RPC("MSF RPC Port"),
}

/**
 * How long a root probe stays usable before this screen may run `su` again.
 *
 * Long enough that switching tabs repeatedly does not re-exec `su` (which the root manager
 * reports as a request), short enough that a grant made in the manager app shows up when the
 * user comes back.
 */
private const val ROOT_PROBE_TTL_MS = 15_000L

/** Progress is a 0..1 fraction; the UI shows whole percent. */
private const val PERCENT = 100

@Suppress("LongParameterList") // Hilt-injected settings hub; splitting it is a separate refactor.
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val system: IndraSystem,
        private val settings: SettingsRepository,
        private val coreManager: CoreManager,
        private val otaManager: OtaUpdateManager,
        private val toolBox: SmartToolBox,
        private val crashReporter: CrashReporter,
        private val appUpdateManager: AppUpdateManager,
        private val rootManager: RootManager,
        private val wallpaperStore: WallpaperStore,
        private val bundleUpdateManager: BundleUpdateManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        /** Latest op of each channel, kept so either collector can rebuild the whole list. */
        @Volatile
        private var toolsOp: BundleOp = BundleOp.Idle("tools")

        @Volatile
        private var msfOp: BundleOp = BundleOp.Idle("msf")

        /** Installed versions read off IO in [loadSettings]; null until first read. */
        @Volatile
        private var toolsInstalledVersion: String? = null

        @Volatile
        private var toolsSource: String? = null

        @Volatile
        private var msfInstalledVersion: String? = null

        /**
         * Last root probe, kept so [openRootManager] can name a manager whose package
         * PackageManager hides (the `su` probe still knows it). Written on IO.
         */
        @Volatile
        private var lastRoot: RootManager.RootStatus? = null

        /** `SystemClock.elapsedRealtime()` of [lastRoot]; 0 = never probed. */
        @Volatile
        private var lastRootProbeAt: Long = 0L

        init {
            loadSettings(probeRoot = true)
            viewModelScope.launch {
                bundleUpdateManager.tools.op.collect { op ->
                    toolsOp = op
                    publishBundles()
                }
            }
            viewModelScope.launch {
                bundleUpdateManager.msf.op.collect { op ->
                    msfOp = op
                    publishBundles()
                }
            }
        }

        /** Rebuilds the bundle list from the two channel ops plus the installed versions. */
        private fun publishBundles() {
            _uiState.value =
                _uiState.value.copy(
                    bundles =
                        listOf(
                            bundleInfo("tools", "Tools", toolsInstalledVersion, toolsSource, toolsOp),
                            bundleInfo("msf", "Metasploit", msfInstalledVersion, null, msfOp),
                        ),
                )
        }

        private fun bundleInfo(
            id: String,
            label: String,
            installedVersion: String?,
            source: String?,
            op: BundleOp,
        ): BundleUiInfo {
            val available = (op as? BundleOp.UpdateAvailable)?.manifest?.version
            return BundleUiInfo(id, label, installedVersion, source, available, bundleBusy(op), bundleStatus(op))
        }

        private fun bundleBusy(op: BundleOp): Boolean =
            op is BundleOp.Checking || op is BundleOp.Downloading || op is BundleOp.Verifying ||
                op is BundleOp.Staging || op is BundleOp.Swapping || op is BundleOp.PendingSwap

        private fun bundleStatus(op: BundleOp): String? =
            when (op) {
                is BundleOp.Checking -> "Checking…"
                is BundleOp.Downloading -> "Downloading… ${(op.progress * PERCENT).toInt()}%"
                is BundleOp.Verifying -> "Verifying signature…"
                is BundleOp.Staging -> "Staging…"
                is BundleOp.PendingSwap -> "Waiting to install: ${op.reason}"
                is BundleOp.Swapping -> "Installing…"
                is BundleOp.Done -> "Updated"
                is BundleOp.Error -> op.message
                is BundleOp.UpdateAvailable -> "Update available: ${op.manifest.version}"
                is BundleOp.Idle -> null
            }

        /** Checks both channels against GitHub Releases (one API call). */
        fun checkBundles() {
            bundleUpdateManager.checkForUpdates()
        }

        /** Installs the pending update of one channel ("tools" or "msf"). */
        fun installBundle(id: String) {
            when (id) {
                "tools" -> bundleUpdateManager.tools.installUpdate()
                "msf" -> bundleUpdateManager.msf.installUpdate()
            }
        }

        /**
         * Re-reads settings.
         *
         * [probeRoot] runs the `su` probe (`RootManager.detectRoot()`); without it the cached
         * status is reused unless it is older than [ROOT_PROBE_TTL_MS]. This screen reloads on
         * **every resume** ([ReloadOnResume]) and after every toggle, and each probe is a real
         * `su` exec — which the root manager reports as a fresh request, so an unapproved install
         * spammed a Magisk notification on every tab switch. Root state changes only when the user
         * edits it in the manager app, and the short TTL still picks that up. [refresh] forces it.
         */
        fun loadSettings(probeRoot: Boolean = false) {
            // Everything probed here blocks: `su`-based root detection (exec + waitFor),
            // workspace tree walks, log-file stats, and 17 handler probes (JNI) when the
            // daemon is down. ViewModelScope launches on Main — this screen used to ANR
            // on every resume. StateFlow assignment off Main is thread-safe.
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    val workspace = IndraWorkspace.inspect(IndraWorkspace.root())
                    // One probe, read three times: detectRoot() shells out to `su` (up to a
                    // 15 s bound) and used to be called twice per load — once for the flag
                    // and once more inside getRootTypeDisplayName().
                    val root = rootStatus(probeRoot)
                    // One probe pair feeds three fields: the version text, the hero's boolean and
                    // the single-button state the Native Core card draws. Reading them twice was
                    // how the card could say "Running" while the hero said "stopped".
                    val coreVersion = system.getCoreVersion()
                    val coreRunning = coreManager.isDaemonRunning
                    // Bundle versions are disk reads (sentinels + .bundle-version), so they
                    // belong on IO with the rest of this block.
                    toolsInstalledVersion = bundleUpdateManager.installedToolsVersion()
                    toolsSource = bundleUpdateManager.toolsSource()
                    msfInstalledVersion = bundleUpdateManager.installedMsfVersion()
                    _uiState.value =
                        _uiState.value.copy(
                            httpProxyPort = settings.httpProxyPort,
                            httpServerPort = settings.httpServerPort,
                            httpsRedirectorPort = settings.httpsRedirectorPort,
                            msfRpcPort = settings.msfRpcPort,
                            version = "2.0.0",
                            coreVersion = coreVersion ?: "Not installed",
                            daemonRunning = coreRunning,
                            coreState =
                                NativeCoreState.of(
                                    installed = coreVersion != null,
                                    running = coreRunning,
                                    rootless = settings.rootlessMode,
                                ),
                            isRooted = root.isRooted,
                            rootType = rootManager.getRootTypeDisplayName(root),
                            rootGuidance = rootManager.guidance(root),
                            rootManagerLabel = RootAccess.managerLabelFor(root),
                            otaManifestUrl = settings.otaManifestUrl,
                            otaAllowTestHosts = settings.otaAllowTestHosts,
                            rootlessMode = settings.rootlessMode,
                            wakeLockEnabled = settings.wakeLockEnabled,
                            alpineInstalled = coreManager.isAlpineInstalled(),
                            debugErrorLogging = settings.debugErrorLogging,
                            wallpaper = wallpaperStore.state(),
                            crashLogCount = crashReporter.getCrashFiles().size,
                            debugLogSizeBytes = crashReporter.getLogFile()?.length() ?: 0,
                            allFilesAccess = IndraWorkspace.hasAllFilesAccess(),
                            indraDirPath = workspace.rootPath,
                            workspacePresent = workspace.present,
                            workspaceMissing = workspace.missing,
                            toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                        )
                    publishBundles()
                }
            }
        }

        /**
         * Re-reads the workspace tree and the all-files-access flag without touching the rest
         * of the screen — the Storage card and the log sizes are the parts that go stale while
         * Settings stays open (e.g. after returning from the system "all files access" page).
         */
        fun refreshWorkspace() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    val workspace = IndraWorkspace.inspect(IndraWorkspace.root())
                    _uiState.value =
                        _uiState.value.copy(
                            allFilesAccess = IndraWorkspace.hasAllFilesAccess(),
                            indraDirPath = workspace.rootPath,
                            workspacePresent = workspace.present,
                            workspaceMissing = workspace.missing,
                        )
                }
            }
        }

        /**
         * Creates `/sdcard/Indra` and one subdirectory per kind of file. The app process can
         * only write there once "All files access" is granted, so the daemon (which runs as
         * root) does the `mkdir`; the result is read back from disk rather than assumed.
         */
        fun createWorkspaceDirs() {
            if (_uiState.value.workspaceCreating) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(workspaceCreating = true, storageMessage = null)
                withContext(Dispatchers.IO) {
                    runCatching { IndraWorkspace.allDirectories().forEach { it.mkdirs() } }
                    val alreadyVisible = IndraWorkspace.inspect(IndraWorkspace.root()).isReady
                    val viaDaemon = if (alreadyVisible) false else toolBox.ensureWorkspaceDirs()
                    val workspace = IndraWorkspace.inspect(IndraWorkspace.root())
                    _uiState.value =
                        _uiState.value.copy(
                            workspaceCreating = false,
                            allFilesAccess = IndraWorkspace.hasAllFilesAccess(),
                            indraDirPath = workspace.rootPath,
                            workspacePresent = workspace.present,
                            workspaceMissing = workspace.missing,
                            storageMessage =
                                IndraWorkspace.statusMessage(
                                    state = workspace,
                                    allFilesAccess = IndraWorkspace.hasAllFilesAccess(),
                                    rootCreatedByDaemon = viaDaemon,
                                ),
                        )
                }
            }
        }

        /** Persists one of the daemon-facing ports (validated by the UI: 1..65535). */
        fun setPort(
            field: PortField,
            port: Int,
        ) {
            if (port !in 1..65535) return
            when (field) {
                PortField.HTTP_PROXY -> settings.httpProxyPort = port
                PortField.HTTP_SERVER -> settings.httpServerPort = port
                PortField.HTTPS_REDIRECT -> settings.httpsRedirectorPort = port
                PortField.MSF_RPC -> settings.msfRpcPort = port
            }
            loadSettings()
        }

        /**
         * The single lifecycle entry point behind the Native Core card's one button.
         *
         * The caller never picks the step — [NativeCoreState] does — so the UI cannot offer an
         * action the core cannot take, and the three handlers below cannot drift apart the way
         * the old Install/Start/Stop triple did.
         */
        fun onCoreAction() {
            when (_uiState.value.coreState.action) {
                // Rootless: there is no step to take, and the card draws no button for it.
                // Reaching here means a stale tap landed after the mode changed.
                null -> Unit
                NativeCoreAction.INSTALL -> installCore()
                NativeCoreAction.START -> startDaemon()
                NativeCoreAction.STOP -> stopDaemon()
            }
        }

        /** Installs the bundled archive and starts the daemon in the same tap. */
        fun installCore() = runCoreAction(NativeCoreAction.INSTALL) { installThenStart() }

        fun startDaemon() = runCoreAction(NativeCoreAction.START) { startCoreDaemon() }

        fun stopDaemon() = runCoreAction(NativeCoreAction.STOP) { stopCoreDaemon() }

        /**
         * Runs one lifecycle step inside a single busy window: the button stays disabled from
         * the tap until the state has been re-probed on IO, so it never re-enables against a
         * stale state (the previous code cleared its busy flag *before* probing, which is how a
         * running daemon could briefly offer "Start" again).
         */
        private fun runCoreAction(
            action: NativeCoreAction,
            step: suspend () -> Unit,
        ) {
            if (_uiState.value.coreBusyAction != null) return
            _uiState.value = _uiState.value.copy(coreBusyAction = action, coreMessage = null, coreError = null)
            viewModelScope.launch {
                // `finally` so a thrown step cannot leave the button spinning forever.
                try {
                    step()
                } finally {
                    refreshCoreState()
                }
            }
        }

        /**
         * Install, then start. A freshly extracted core is inert until `indrad` runs, so asking
         * for a second tap would only be asking the user to finish our job. `CoreManager`
         * already stops a running daemon before it overwrites the archive, so a reinstall from
         * the Running state also comes back up.
         */
        private suspend fun installThenStart() {
            val install = coreManager.installCore()
            if (install.isFailure) {
                setCoreOutcome(error = "Install failed: ${install.exceptionOrNull()?.message}")
                return
            }
            // Move the busy marker on so the button says what is happening *now*.
            _uiState.value = _uiState.value.copy(coreBusyAction = NativeCoreAction.START)
            val start = coreManager.startDaemon()
            if (start.isSuccess) {
                setCoreOutcome(message = "Core installed · daemon running")
            } else {
                // The install itself worked, so the state is now "installed, stopped"; the error
                // line carries only why the start failed (no double prefix).
                setCoreOutcome(error = start.exceptionOrNull()?.message ?: "Core installed, but the daemon did not start")
            }
        }

        private suspend fun startCoreDaemon() {
            val start = coreManager.startDaemon()
            if (start.isSuccess) {
                setCoreOutcome(message = "Daemon running")
            } else {
                // CoreManager's message already names the failure ("Daemon failed to start
                // (exit=126): …"), so it is shown verbatim instead of being prefixed twice.
                setCoreOutcome(error = start.exceptionOrNull()?.message ?: "The daemon did not start")
            }
        }

        private suspend fun stopCoreDaemon() {
            val stop = coreManager.stopDaemon()
            if (stop.isSuccess) {
                setCoreOutcome(message = "Daemon stopped")
            } else {
                setCoreOutcome(error = "Stop failed: ${stop.exceptionOrNull()?.message}")
            }
        }

        /**
         * Records what the last step did. [coreMessage] is informational, [coreError] renders in
         * the error colour — a failed install must not read like a normal status note.
         */
        private fun setCoreOutcome(
            message: String? = null,
            error: String? = null,
        ) {
            _uiState.value = _uiState.value.copy(coreMessage = message, coreError = error)
        }

        fun installAlpine() {
            if (_uiState.value.alpineBusy) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(alpineBusy = true, alpineMessage = "Installing Alpine Linux…")
                coreManager
                    .installAlpine { p ->
                        _uiState.value = _uiState.value.copy(alpineMessage = "Installing Alpine Linux… ${(p * 100).toInt()}%")
                    }.onSuccess {
                        toolBox.resetChrootState()
                        _uiState.value =
                            _uiState.value.copy(
                                alpineMessage = "Alpine installed",
                                alpineInstalled = true,
                            )
                    }.onFailure { error ->
                        _uiState.value = _uiState.value.copy(alpineMessage = "Install failed: ${error.message}")
                    }
                _uiState.value = _uiState.value.copy(alpineBusy = false)
                loadSettings()
            }
        }

        fun refresh() {
            loadSettings(probeRoot = true)
        }

        /** Probe now, or reuse the last one while it is younger than [ROOT_PROBE_TTL_MS]. */
        private suspend fun rootStatus(force: Boolean): RootManager.RootStatus {
            val cached = lastRoot
            val age = SystemClock.elapsedRealtime() - lastRootProbeAt
            if (!force && cached != null && age in 0 until ROOT_PROBE_TTL_MS) return cached

            val fresh = rootManager.detectRoot()
            lastRoot = fresh
            lastRootProbeAt = SystemClock.elapsedRealtime()
            return fresh
        }

        /**
         * Opens the Magisk/KernelSU app so the user can grant Indra in its Superuser tab.
         * Magisk v31 removed the reactive prompt, so there is nothing to wait for in-app:
         * the grant has to be made in the manager, then this screen re-probes on resume.
         */
        fun openRootManager() {
            val status = lastRoot
            viewModelScope.launch {
                val opened = withContext(Dispatchers.IO) { rootManager.openRootManager(status) }
                if (!opened) {
                    _uiState.value =
                        _uiState.value.copy(
                            rootGuidance =
                                "Could not open the ${_uiState.value.rootManagerLabel} app. Open it from the " +
                                    "app drawer, then enable Indra under Superuser.",
                        )
                }
            }
        }

        fun setDebugErrorLogging(enabled: Boolean) {
            settings.debugErrorLogging = enabled
            Logger.setFileLogging(enabled)
            _uiState.value = _uiState.value.copy(debugErrorLogging = enabled)
        }

        /**
         * Copies the picked image in and makes it the app background.
         *
         * The URI comes from the system photo picker, which grants read access to that one file
         * only — no storage permission needed. Every failure is reported under the card and leaves
         * the previous wallpaper alone.
         */
        fun setWallpaper(uri: Uri) {
            if (_uiState.value.wallpaperBusy) return
            _uiState.value = _uiState.value.copy(wallpaperBusy = true, wallpaperError = null)
            viewModelScope.launch {
                val result = wallpaperStore.set(uri)
                _uiState.value =
                    result.fold(
                        onSuccess = { state ->
                            _uiState.value.copy(wallpaper = state, wallpaperBusy = false, wallpaperError = null)
                        },
                        onFailure = { error ->
                            _uiState.value.copy(
                                wallpaperBusy = false,
                                wallpaperError = error.message ?: "Could not set that image.",
                            )
                        },
                    )
            }
        }

        /** Removes the background, back to the plain theme colour. */
        fun clearWallpaper() {
            viewModelScope.launch {
                val state = wallpaperStore.clear()
                _uiState.value = _uiState.value.copy(wallpaper = state, wallpaperError = null)
            }
        }

        /** Changes the scrim weight without touching the stored image. */
        fun setWallpaperDim(dim: WallpaperDim) {
            _uiState.value = _uiState.value.copy(wallpaper = wallpaperStore.setDim(dim))
        }

        fun checkAppUpdate() {
            if (_uiState.value.appUpdateChecking) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(appUpdateChecking = true, appUpdateMessage = "Checking for app updates…")
                val check = appUpdateManager.checkForUpdate()
                val info = (check as? AppUpdateManager.Check.Found)?.info
                if (info == null) {
                    // "Refused" is a fixable state (bad signature / unsigned build) and must
                    // not read like a network failure.
                    val message = (check as? AppUpdateManager.Check.Refused)?.reason ?: "Update check failed"
                    _uiState.value = _uiState.value.copy(appUpdateChecking = false, appUpdateMessage = message)
                } else if (appUpdateManager.isNewer(info.version)) {
                    _uiState.value = _uiState.value.copy(
                        appUpdateChecking = false,
                        appUpdateAvailable = true,
                        appUpdateVersion = info.version,
                        appUpdateMessage = "Update available: ${info.version} (current ${appUpdateManager.currentVersion()})",
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        appUpdateChecking = false,
                        appUpdateAvailable = false,
                        appUpdateMessage = "App is up to date (${appUpdateManager.currentVersion()})",
                    )
                }
            }
        }

        fun installAppUpdate() {
            if (!_uiState.value.appUpdateAvailable) return
            if (_uiState.value.appUpdateChecking) return
            viewModelScope.launch {
                // appUpdateChecking doubles as the busy flag: without it a second tap
                // re-downloaded, and a null manifest left "Downloading update…" forever.
                _uiState.value = _uiState.value.copy(appUpdateChecking = true, appUpdateMessage = "Downloading update…")
                val check = appUpdateManager.checkForUpdate()
                val info = (check as? AppUpdateManager.Check.Found)?.info
                if (info == null) {
                    val message = (check as? AppUpdateManager.Check.Refused)?.reason ?: "Update check failed"
                    _uiState.value =
                        _uiState.value.copy(
                            appUpdateChecking = false,
                            appUpdateMessage = message,
                        )
                    return@launch
                }
                appUpdateManager
                    .downloadAndInstall(info) { p ->
                        _uiState.value = _uiState.value.copy(appUpdateMessage = "Downloading update… ${(p * 100).toInt()}%")
                    }.onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                appUpdateChecking = false,
                                appUpdateAvailable = false,
                                appUpdateMessage = "Installer launched",
                            )
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(appUpdateChecking = false, appUpdateMessage = "Update failed: ${e.message}")
                    }
            }
        }

        fun clearCrashLogs() {
            crashReporter.clearCrashes()
            _uiState.value = _uiState.value.copy(crashLogCount = 0)
        }

        fun clearDebugLog() {
            Logger.clearLog()
            _uiState.value = _uiState.value.copy(debugLogSizeBytes = 0)
        }

        /** Exports redacted logs as a zip file and shares via FileProvider. */
        fun exportLogs() {
            viewModelScope.launch {
                LogExporter(context)
                    .exportLogs()
                    .fold(
                        onSuccess = { uri -> shareExport(uri) },
                        onFailure = { error -> Logger.w("Log export failed: ${error.message}") },
                    )
            }
        }

        /** Hands the exported zip to the system share sheet; failures are logged, never thrown. */
        private fun shareExport(uri: Uri) {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The chooser is started from the application context, so it needs its own task.
            val chooser = Intent.createChooser(send, "Export Indra logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                Logger.w("No app can receive the log export: ${e.message}")
            }
        }

        fun getCrashLogFiles(): List<java.io.File> = crashReporter.getCrashFiles()

        fun getDebugLogFile(): java.io.File? = crashReporter.getLogFile()

        fun checkOtaUpdates() {
            if (_uiState.value.otaChecking || _uiState.value.otaUpdating) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(otaChecking = true, otaMessage = "Checking for updates…")
                val result = otaManager.checkForUpdate()
                val manifest = result.manifest
                _uiState.value =
                    if (manifest == null) {
                        _uiState.value.copy(
                            otaChecking = false,
                            otaMessage =
                                if (otaManager.isSignatureConfigured()) {
                                    "Update check failed"
                                } else {
                                    "Binary updates are not configured in this build " +
                                        "(no signing key provisioned)"
                                },
                        )
                    } else {
                        val outdated = manifest.binaries.count { result.localVersions[it.name] != it.version }
                        _uiState.value.copy(
                            otaChecking = false,
                            otaUpdateAvailable = outdated > 0,
                            otaMessage =
                                if (outdated > 0) {
                                    "Update available: $outdated binary(ies) outdated"
                                } else {
                                    "All binaries are up to date"
                                },
                        )
                    }
            }
        }

        fun setOtaManifestUrl(url: String) {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return
            settings.otaManifestUrl = trimmed
            loadSettings()
            _uiState.value = _uiState.value.copy(otaMessage = "Manifest URL updated")
        }

        fun setOtaAllowTestHosts(enabled: Boolean) {
            settings.otaAllowTestHosts = enabled
            loadSettings()
            _uiState.value =
                _uiState.value.copy(
                    otaMessage = if (enabled) "LAN test hosts enabled (dev only)" else "LAN test hosts disabled",
                )
        }

        fun setRootlessMode(enabled: Boolean) {
            settings.rootlessMode = enabled
            loadSettings()
            _uiState.value =
                _uiState.value.copy(
                    coreMessage =
                        if (enabled) {
                            "Rootless mode enabled — port scan, Wi-Fi scan, BLE and password audit " +
                                "work without root"
                        } else {
                            "Root mode enabled — start the daemon for the bundled tools"
                        },
                )
        }

        fun setWakeLockEnabled(enabled: Boolean) {
            settings.wakeLockEnabled = enabled
            _uiState.value = _uiState.value.copy(wakeLockEnabled = enabled)
            // Take effect immediately: the lock was never acquired before, so flipping the
            // switch changed nothing until the next launch (which also did nothing).
            system.applyWakeLockSetting()
        }

        fun installOtaUpdates() {
            if (_uiState.value.otaChecking || _uiState.value.otaUpdating) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(otaUpdating = true, otaMessage = "Installing updates…")
                val manifest = otaManager.checkForUpdate().manifest
                if (manifest == null) {
                    _uiState.value =
                        _uiState.value.copy(
                            otaUpdating = false,
                            otaMessage =
                                if (otaManager.isSignatureConfigured()) {
                                    "No update manifest available"
                                } else {
                                    "Binary updates are not configured in this build " +
                                        "(no signing key provisioned)"
                                },
                        )
                } else {
                    otaManager
                        .installLatest(manifest)
                        .onSuccess {
                            // The update is applied, so the single button must fall back to
                            // "Check for updates" instead of offering the same install again.
                            _uiState.value =
                                _uiState.value.copy(otaUpdateAvailable = false, otaMessage = "Updates installed")
                        }.onFailure { error ->
                            _uiState.value =
                                _uiState.value.copy(
                                    otaMessage = "Update failed (rolled back): ${error.message}",
                                )
                        }
                    _uiState.value = _uiState.value.copy(otaUpdating = false)
                    refreshCoreState()
                }
            }
        }

        /**
         * Re-probes the core on IO and only then lets the lifecycle button out of its busy state.
         *
         * Order matters twice here:
         * - `reload()` runs **before** the handler probes, so the tool list describes the daemon
         *   that was just started/stopped instead of the previous one (`isAvailable()` answers
         *   from a 1 s cache that install/start/stop is exactly the event to invalidate).
         * - the busy marker clears **after** the probe, so the next tap is answered from a state
         *   that was just read. Clearing it first (as this used to) let the button re-enable
         *   against the old state for the length of a JNI round-trip.
         */
        private fun refreshCoreState() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    toolBox.reload()
                    val coreVersion = system.getCoreVersion()
                    val running = coreManager.isDaemonRunning
                    _uiState.value =
                        _uiState.value.copy(
                            coreVersion = coreVersion ?: "Not installed",
                            daemonRunning = running,
                            coreState =
                                NativeCoreState.of(
                                    installed = coreVersion != null,
                                    running = running,
                                    rootless = settings.rootlessMode,
                                ),
                            coreBusyAction = null,
                            toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                        )
                }
            }
        }
    }
