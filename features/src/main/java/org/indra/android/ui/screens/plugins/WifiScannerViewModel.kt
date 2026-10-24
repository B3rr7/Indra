package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class WifiScannerUiState(
    val iface: String = "wlan0",
    val isScanning: Boolean = false,
    val networks: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class WifiScannerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WifiScannerUiState())
        val uiState: StateFlow<WifiScannerUiState> = _uiState.asStateFlow()

        fun updateIface(v: String) {
            _uiState.value = _uiState.value.copy(iface = v)
        }

        private var job: Job? = null
        private var pid = -1

        fun scan() {
            if (job?.isActive == true) return
            val iface =
                _uiState.value.iface
                    .trim()
                    .ifEmpty { "wlan0" }
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(error = "Native daemon not available")
                return
            }
            _uiState.value = _uiState.value.copy(isScanning = true, error = null, networks = emptyList())
            job =
                viewModelScope.launch {
                    toolBox.shell("iw dev $iface scan").collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput
                        if (line.isNotBlank() && !line.startsWith("Executing:")) {
                            // Collect BSS lines as network entries
                            if (line.trim().startsWith("BSS ")) {
                                _uiState.value = _uiState.value.copy(networks = _uiState.value.networks + line.trim())
                            }
                        }
                        if (result.status != "running") {
                            _uiState.value = _uiState.value.copy(isScanning = false)
                            pid = -1
                            if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                        }
                    }
                }
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isScanning = false)
        }
    }
