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
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class WifiScannerUiState(
    val iface: String = "wlan0",
    val isScanning: Boolean = false,
    val networks: List<String> = emptyList(),
    /** True once a scan finished — an empty [networks] before the first scan is not the same
     *  as a scan that saw nothing. */
    val finished: Boolean = false,
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
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(isScanning = true, error = null, finished = false, networks = emptyList())
                    toolBox.shell("iw dev ${SmartToolBox.shellQuote(iface)} scan").collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            // Collect BSS lines as network entries
                            if (line.trim().startsWith("BSS ")) {
                                _uiState.value = _uiState.value.copy(networks = _uiState.value.networks + line.trim())
                            }
                        }
                        if (!result.isStreaming) {
                            _uiState.value = _uiState.value.copy(isScanning = false, finished = true)
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
