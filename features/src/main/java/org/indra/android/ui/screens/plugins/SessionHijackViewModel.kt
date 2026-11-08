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

/** Port 80 plus the usual alternates; editable on the screen. */
private const val DEFAULT_HTTP_FILTER = "tcp port 80 or tcp port 8080"

data class HijackedSession(val host: String, val cookie: String)

data class SessionHijackUiState(
    val iface: String = "wlan0",
    /** BPF expression handed to tcpdump. Was hardcoded to `tcp port 80`, so cookies on
     *  any other HTTP port (8080, 8000, …) were invisible even though the screen claimed
     *  to capture HTTP traffic. */
    val filter: String = DEFAULT_HTTP_FILTER,
    val isRunning: Boolean = false,
    /**
     * True once a capture has been started. The header used to print
     * `Captured sessions: 0` before anything ran, which reads like a completed capture
     * that found nothing instead of "not started yet".
     */
    val hasRun: Boolean = false,
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

    fun updateFilter(v: String) { _uiState.value = _uiState.value.copy(filter = v) }

    private var job: Job? = null
    private var pid = -1

    fun start() {
        if (job?.isActive == true) return
        val iface = _uiState.value.iface.trim().ifEmpty { "wlan0" }
        val filter = _uiState.value.filter.trim().ifEmpty { DEFAULT_HTTP_FILTER }
        job = viewModelScope.launch {
            val gate = toolBox.requireDirectTool("shell")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(error = gate.message)
                return@launch
            }
            _uiState.value =
                _uiState.value.copy(
                    isRunning = true,
                    hasRun = true,
                    error = null,
                    sessions = emptyList(),
                    output = emptyList(),
                )
            // stderr is kept: tcpdump's "SIOCGIFHWADDR: No such device" and its capture
            // summary are the only explanation a failing run gives, and ToolResult.error
            // carries the last output line.
            toolBox.shellDirect(
                "./tools/tcpdump/tcpdump -i ${SmartToolBox.shellQuote(iface)} -A -l " +
                    SmartToolBox.shellQuote(filter),
            ).collect { result ->
                if (result.pid > 0) pid = result.pid
                result.streamedLine?.let { line ->
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
                if (!result.isStreaming) {
                    _uiState.value = _uiState.value.copy(isRunning = false)
                    pid = -1
                    if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null; pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false)
    }
}
