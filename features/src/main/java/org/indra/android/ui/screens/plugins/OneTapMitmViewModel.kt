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

data class OneTapMitmUiState(
    val iface: String = "wlan0",
    val target: String = "",
    val gateway: String = "",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class OneTapMitmViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(OneTapMitmUiState())
    val uiState: StateFlow<OneTapMitmUiState> = _uiState.asStateFlow()

    fun updateIface(v: String) { _uiState.value = _uiState.value.copy(iface = v) }
    fun updateTarget(v: String) { _uiState.value = _uiState.value.copy(target = v) }
    fun updateGateway(v: String) { _uiState.value = _uiState.value.copy(gateway = v) }

    private var job: Job? = null
    private var pid = -1

    fun start() {
        if (job?.isActive == true) return
        val st = _uiState.value
        val iface = st.iface.trim().ifEmpty { "wlan0" }
        val target = st.target.trim()
        val gateway = st.gateway.trim()
        if (target.isEmpty() || gateway.isEmpty()) {
            _uiState.value = st.copy(error = "Target and gateway required")
            return
        }
        val runner = toolBox.getTool("arpspoof")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = st.copy(error = "arpspoof not available (root required)")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
        job = viewModelScope.launch {
            // ARP spoof: tell target that we are the gateway
            toolBox.arpspoof(iface, target, gateway).collect { result ->
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
        job?.cancel(); job = null; pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false)
    }
}