package org.indra.android.ui.screens.tools

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

data class TerminalUiState(
    val input: String = "",
    val output: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var pid = -1

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(input = text)
    }

    fun run() {
        if (job?.isActive == true) return
        val cmd = _uiState.value.input.trim()
        if (cmd.isEmpty()) return

        val runner = toolBox.getTool("shell")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = _uiState.value.copy(error = "Native daemon not available")
            return
        }

        // Echo the command as a prompt line.
        val prompt = "$ $cmd"
        _uiState.value = _uiState.value.copy(
            isRunning = true,
            error = null,
            input = "",
            output = _uiState.value.output + prompt
        )

        job = viewModelScope.launch {
            toolBox.shell(cmd).collect { result ->
                if (result.pid > 0) pid = result.pid
                val line = result.rawOutput
                if (result.status == "running") {
                    if (line.isNotBlank() && !line.startsWith("Executing:")) {
                        _uiState.value = _uiState.value.copy(
                            output = _uiState.value.output + line
                        )
                    }
                } else {
                    // completed / timeout
                    if (line.isNotBlank() && !line.startsWith("Executing:")) {
                        _uiState.value = _uiState.value.copy(
                            output = _uiState.value.output + line
                        )
                    }
                    _uiState.value = _uiState.value.copy(isRunning = false)
                    pid = -1
                }
            }
        }
    }

    fun stop() {
        if (pid > 0) toolBox.stop(pid)
        job?.cancel()
        job = null
        pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false, output = _uiState.value.output + "[stopped]")
    }

    fun clear() {
        if (job?.isActive == true) return
        _uiState.value = _uiState.value.copy(output = emptyList())
    }
}