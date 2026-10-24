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

data class ReaverUiState(
    val iface: String = "wlan0",
    val bssid: String = "",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ReaverViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReaverUiState())
        val uiState: StateFlow<ReaverUiState> = _uiState.asStateFlow()

        fun updateIface(v: String) {
            _uiState.value = _uiState.value.copy(iface = v)
        }

        fun updateBssid(v: String) {
            _uiState.value = _uiState.value.copy(bssid = v)
        }

        private var job: Job? = null
        private var pid = -1

        fun run() {
            if (job?.isActive == true) return
            val st = _uiState.value
            val iface = st.iface.trim().ifEmpty { "wlan0" }
            val bssid = st.bssid.trim()
            if (bssid.isEmpty()) {
                _uiState.value = st.copy(error = "BSSID required")
                return
            }
            val runner = toolBox.getTool("reaver")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = st.copy(error = "reaver is not bundled")
                return
            }
            _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
            job =
                viewModelScope.launch {
                    toolBox.reaver(iface, bssid).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput
                        if (line.isNotBlank() && !line.startsWith("Executing:")) {
                            _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                        }
                        if (result.status != "running") {
                            _uiState.value = _uiState.value.copy(isRunning = false)
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
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }
