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

data class WifiAuditUiState(
    val capture: String = "",
    val wordlist: String = "",
    val bssid: String = "",
    val essid: String = "",
    val isActive: Boolean = false,
    val lines: List<String> = emptyList(),
    val keyFound: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WifiAuditViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiAuditUiState())
    val uiState: StateFlow<WifiAuditUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var pid = -1

    init {
        _uiState.value = _uiState.value.copy(
            wordlist = toolBox.defaultWordlist(),
            // prefill from PacketCapture's save-to-file path when a capture
            // already exists there
            capture = toolBox.capturePath().takeIf { p ->
                java.io.File(p).length() > 0
            } ?: ""
        )
    }

    fun updateCapture(capture: String) {
        _uiState.value = _uiState.value.copy(capture = capture)
    }

    fun updateWordlist(wordlist: String) {
        _uiState.value = _uiState.value.copy(wordlist = wordlist)
    }

    fun updateBssid(bssid: String) {
        _uiState.value = _uiState.value.copy(bssid = bssid)
    }

    fun updateEssid(essid: String) {
        _uiState.value = _uiState.value.copy(essid = essid)
    }

    /** bssid/essid strings go straight into an argv vector, so reject anything
     *  that could be parsed as another flag. */
    private fun sanitized(value: String): String? = value.trim().takeUnless {
        it.isEmpty() || it.startsWith("-")
    }

    fun crack() {
        if (job?.isActive == true) return
        val state = _uiState.value
        val capture = state.capture.trim()
        val wordlist = state.wordlist.trim()

        if (capture.isEmpty()) {
            _uiState.value = state.copy(error = "Capture file is required")
            return
        }
        if (wordlist.isEmpty()) {
            _uiState.value = state.copy(error = "Wordlist file is required")
            return
        }
        if (state.bssid.isNotBlank() && sanitized(state.bssid) == null) {
            _uiState.value = state.copy(error = "BSSID cannot start with '-'")
            return
        }
        if (state.essid.isNotBlank() && sanitized(state.essid) == null) {
            _uiState.value = state.copy(error = "ESSID cannot start with '-'")
            return
        }

        val runner = toolBox.getTool("raw")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = state.copy(error = "Native daemon not available")
            return
        }

        _uiState.value = state.copy(isActive = true, error = null, lines = emptyList(), keyFound = false)

        val args = buildString {
            append("-w ").append(wordlist)
            sanitized(state.bssid)?.let { append(" -b ").append(it) }
            sanitized(state.essid)?.let { append(" -e ").append(it) }
            append(' ').append(capture)
        }

        job = viewModelScope.launch {
            var found = false
            toolBox.aircrack(args).collect { result ->
                if (result.pid > 0) pid = result.pid
                val line = result.rawOutput
                when (result.status) {
                    "running" -> {
                        if (line.isNotBlank() && !line.startsWith("Executing:")) {
                            if (line.contains("KEY FOUND")) {
                                found = true
                            }
                            _uiState.value = _uiState.value.copy(
                                lines = (_uiState.value.lines + line).takeLast(MAX_LINES),
                                keyFound = _uiState.value.keyFound || line.contains("KEY FOUND")
                            )
                        }
                    }
                    "completed", "timeout" -> {
                        _uiState.value = _uiState.value.copy(
                            isActive = false,
                            keyFound = _uiState.value.keyFound || found,
                            error = if (result.success) null else result.error
                        )
                    }
                }
            }
            _uiState.value = _uiState.value.copy(isActive = false)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        toolBox.stop(pid)
        pid = -1
        _uiState.value = _uiState.value.copy(isActive = false)
    }

    override fun onCleared() {
        toolBox.stop(pid)
        super.onCleared()
    }

    companion object {
        private const val MAX_LINES = 400
    }
}
