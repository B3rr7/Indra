package org.indra.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.AppUpdateManager
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.CrashReporter
import org.indra.android.core.system.IndraWorkspace
import org.indra.android.core.system.Logger
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.NativeCoreAction
import org.indra.android.core.system.NativeCoreState
import org.indra.android.core.system.OtaUpdateManager
import org.indra.android.core.system.RootAccess
import org.indra.android.core.system.RootManager
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class ToolStatus(
    val name: String,
    val available: Boolean,
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

@Suppress("LongParameterList") // Hilt-injected settings hub; splitting it is a separate refactor.
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val system: IndraSystem,
        private val settings: SettingsRepository,
        private val coreManager: CoreManager,
        private val otaManager: OtaUpdateManager,
        private val toolBox: SmartToolBox,
        private val crashReporter: CrashReporter,
        private val appUpdateManager: AppUpdateManager,
        private val rootManager: RootManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        /**
         * Last root probe, kept so [openRootManager] can name a manager whose package
         * PackageManager hides (the `su` probe still knows it). Written on IO.
         */
        @Volatile
        private var lastRoot: RootManager.RootStatus? = null

        init {
            loadSettings()
        }

        fun loadSettings() {
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
                    val root = rootManager.detectRoot()
                    lastRoot = root
                    // One probe pair feeds three fields: the version text, the hero's boolean and
                    // the single-button state the Native Core card draws. Reading them twice was
                    // how the card could say "Running" while the hero said "stopped".
                    val coreVersion = system.getCoreVersion()
                    val coreRunning = coreManager.isDaemonRunning
                    _uiState.value =
                        _uiState.value.copy(
                            httpProxyPort = settings.httpProxyPort,
                            httpServerPort = settings.httpServerPort,
                            httpsRedirectorPort = settings.httpsRedirectorPort,
                            msfRpcPort = settings.msfRpcPort,
                            version = "2.0.0",
                            coreVersion = coreVersion ?: "Not installed",
                            daemonRunning = coreRunning,
                            coreState = NativeCoreState.of(installed = coreVersion != null, running = coreRunning),
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
                            crashLogCount = crashReporter.getCrashFiles().size,
                            debugLogSizeBytes = crashReporter.getLogFile()?.length() ?: 0,
                            allFilesAccess = IndraWorkspace.hasAllFilesAccess(),
                            indraDirPath = workspace.rootPath,
                            workspacePresent = workspace.present,
                            workspaceMissing = workspace.missing,
                            toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                        )
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
            loadSettings()
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
                    coreMessage = if (enabled) "Rootless mode enabled (restart daemon)" else "Root mode enabled",
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
                            _uiState.value = _uiState.value.copy(otaMessage = "Updates installed")
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
                            coreState = NativeCoreState.of(installed = coreVersion != null, running = running),
                            coreBusyAction = null,
                            toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                        )
                }
            }
        }
    }
