package org.indra.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.OtaUpdateManager
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
    val rootlessMode: Boolean = false
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

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val system: IndraSystem,
        private val settings: SettingsRepository,
        private val coreManager: CoreManager,
        private val otaManager: OtaUpdateManager,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            loadSettings()
        }

        fun loadSettings() {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        httpProxyPort = settings.httpProxyPort,
                        httpServerPort = settings.httpServerPort,
                        httpsRedirectorPort = settings.httpsRedirectorPort,
                        msfRpcPort = settings.msfRpcPort,
                        version = "2.0.0",
                        coreVersion = system.getCoreVersion() ?: "Not installed",
                        daemonRunning = coreManager.isDaemonRunning,
                        otaManifestUrl = settings.otaManifestUrl,
                        otaAllowTestHosts = settings.otaAllowTestHosts,
                        rootlessMode = settings.rootlessMode,
                        toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                    )
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
                        _uiState.value.copy(otaChecking = false, otaMessage = "Update check failed")
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

        fun installOtaUpdates() {
            if (_uiState.value.otaChecking || _uiState.value.otaUpdating) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(otaUpdating = true, otaMessage = "Installing updates…")
                val manifest = otaManager.checkForUpdate().manifest
                if (manifest == null) {
                    _uiState.value = _uiState.value.copy(otaUpdating = false, otaMessage = "No update manifest available")
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
            _uiState.value =
                _uiState.value.copy(
                    coreBusy = false,
                    coreVersion = system.getCoreVersion() ?: "Not installed",
                    daemonRunning = coreManager.isDaemonRunning,
                    toolStatus = toolBox.getAllTools().map { ToolStatus(it.name, it.isAvailable()) },
                )
            toolBox.reload()
        }
    }
