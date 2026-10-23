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
import java.io.File
import javax.inject.Inject

data class WifiteUiState(
    val iface: String = "wlan0",
    val wordlist: String = "",
    val capturePath: String = "",
    val stage: String = "idle", // idle, monitor, capture, crack, done
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class WifiteViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(WifiteUiState(wordlist = toolBox.defaultWordlist(), capturePath = toolBox.capturePath()))
    val uiState: StateFlow<WifiteUiState> = _uiState.asStateFlow()

    fun updateIface(v: String) { _uiState.value = _uiState.value.copy(iface = v) }
    fun updateWordlist(v: String) { _uiState.value = _uiState.value.copy(wordlist = v) }

    private var job: Job? = null
    private var pid = -1

    fun run() {
        if (job?.isActive == true) return
        val st = _uiState.value
        val iface = st.iface.trim().ifEmpty { "wlan0" }
        val runner = toolBox.getTool("shell")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = st.copy(error = "Native daemon not available")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, output = emptyList(), stage = "monitor")

        job = viewModelScope.launch {
            // Stage 1: enable monitor mode (risky)
            toolBox.shell("ip link set $iface down && iw dev $iface set type monitor && ip link set $iface up").collect { r ->
                if (r.rawOutput.isNotBlank() && !r.rawOutput.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + r.rawOutput)
                }
            }
            _uiState.value = _uiState.value.copy(stage = "capture", output = _uiState.value.output + "[monitor mode enabled]")

            // Stage 2: capture 50 packets to file
            val outFile = _uiState.value.capturePath
            File(outFile).parentFile?.mkdirs()
            toolBox.tcpdump(iface, "", 50, outFile).collect { r ->
                if (r.rawOutput.isNotBlank() && !r.rawOutput.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + r.rawOutput)
                }
                if (r.pid > 0) pid = r.pid
            }
            _uiState.value = _uiState.value.copy(stage = "crack", output = _uiState.value.output + "[capture done]")

            // Stage 3: crack
            val wordlist = _uiState.value.wordlist.trim()
            toolBox.aircrack("-w $wordlist $outFile").collect { r ->
                if (r.rawOutput.isNotBlank() && !r.rawOutput.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + r.rawOutput)
                }
                if (r.pid > 0) pid = r.pid
            }
            _uiState.value = _uiState.value.copy(stage = "done", isRunning = false, output = _uiState.value.output + "[wifite complete]")
            pid = -1
        }
    }

    fun stop() {
        if (pid > 0) toolBox.stop(pid)
        job?.cancel(); job = null; pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false, stage = "idle")
    }
}