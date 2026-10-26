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
import org.indra.android.core.system.OtaUpdateManager
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
    val coreBusy: Boolean = false,
    val coreMessage: String? = null,
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
                    _uiState.value =
                        _uiState.value.copy(
                            httpProxyPort = settings.httpProxyPort,
                            httpServerPort = settings.httpServerPort,
                            httpsRedirectorPort = settings.httpsRedirectorPort,
                            msfRpcPort = settings.msfRpcPort,
                            version = "2.0.0",
                            coreVersion = system.getCoreVersion() ?: "Not installed",
                            daemonRunning = coreManager.isDaemonRunning,
                            isRooted = rootManager.detectRoot().isRooted,
                            rootType = rootManager.getRootTypeDisplayName(),
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

        fun installCore() {
            if (_uiState.value.coreBusy) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(coreBusy = true, coreMessage = "Installing native core…")
                coreManager
                    .installCore()
                    .onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                coreMessage = "Core installed",
                                coreVersion = coreManager.getCoreInfo().version,
                            )
                    }.onFailure { error ->
                        _uiState.value = _uiState.value.copy(coreMessage = "Install failed: ${error.message}")
                    }
                refreshCoreState()
            }
        }

        fun installAlpine() {
            if (_uiState.value.alpineBusy) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(alpineBusy = true, alpineMessage = "Installing Alpine Linux…")
                coreManager
                    .installAlpine { p ->
                        _uiState.value = _uiState.value.copy(alpineMessage = "Installing Alpine Linux… ${(p * 100).toInt()}%")
                    }.onSuccess {
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

        fun requestRootAccess() {
            rootManager.requestRootAccess()
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
                val info = appUpdateManager.checkForUpdate()
                if (info == null) {
                    _uiState.value = _uiState.value.copy(appUpdateChecking = false, appUpdateMessage = "Update check failed")
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
                val info = appUpdateManager.checkForUpdate()
                if (info == null) {
                    _uiState.value =
                        _uiState.value.copy(
                            appUpdateChecking = false,
                            appUpdateMessage = "Update check failed",
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

        fun startDaemon() {
            if (_uiState.value.coreBusy) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(coreBusy = true, coreMessage = "Starting daemon…")
                coreManager
                    .startDaemon()
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(coreMessage = "Daemon running")
                    }.onFailure { error ->
                        _uiState.value = _uiState.value.copy(coreMessage = "Daemon failed: ${error.message}")
                    }
                refreshCoreState()
            }
        }

        fun stopDaemon() {
            if (_uiState.value.coreBusy) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(coreBusy = true, coreMessage = "Stopping daemon…")
                coreManager
                    .stopDaemon()
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(coreMessage = "Daemon stopped")
                    }.onFailure { error ->
                        _uiState.value = _uiState.value.copy(coreMessage = "Stop failed: ${error.message}")
                    }
                refreshCoreState()
            }
        }

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

        private fun refreshCoreState() {
            // Release the busy flag immediately (as before), then probe off Main:
            // version probe + 17 handler checks (JNI) + reload() all block.
            _uiState.value = _uiState.value.copy(coreBusy = false)
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    _uiState.value =
                        _uiState.value.copy(
                            coreVersion = system.getCoreVersion() ?: "Not installed",
                            daemonRunning = coreManager.isDaemonRunning,
                            toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                        )
                    toolBox.reload()
                }
            }
        }
    }
