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

data class MasscanUiState(
    val target: String = "",
    val ports: String = "1-65535",
    val rate: String = "1000",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class MasscanViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(MasscanUiState())
    val uiState: StateFlow<MasscanUiState> = _uiState.asStateFlow()

    fun updateTarget(v: String) { _uiState.value = _uiState.value.copy(target = v) }
    fun updatePorts(v: String) { _uiState.value = _uiState.value.copy(ports = v) }
    fun updateRate(v: String) { _uiState.value = _uiState.value.copy(rate = v) }

    private var job: Job? = null
    private var pid = -1

    fun run() {
        if (job?.isActive == true) return
        val st = _uiState.value
        val target = st.target.trim()
        if (target.isEmpty()) { _uiState.value = st.copy(error = "Target required"); return }
        val runner = toolBox.getTool("masscan")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = st.copy(error = "masscan is not bundled")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
        val ports = st.ports.trim().ifEmpty { "1-65535" }
        val rate = st.rate.trim().ifEmpty { "1000" }
        job = viewModelScope.launch {
            toolBox.masscan(target, "-p $ports --rate=$rate").collect { result ->
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