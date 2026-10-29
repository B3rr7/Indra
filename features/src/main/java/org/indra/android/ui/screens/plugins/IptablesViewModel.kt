package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class IptablesUiState(
    val args: String = "-L -n -v",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class IptablesViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(IptablesUiState())
        val uiState: StateFlow<IptablesUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun updateArgs(v: String) {
            _uiState.value = _uiState.value.copy(args = v)
        }

        fun run() {
            if (job?.isActive == true) return
            val args = _uiState.value.args.trim()
            // Argument vector, not a script: the field is "iptables arguments", so a stray
            // ";" must reach iptables as an argument instead of chaining a second command.
            val cmd = "iptables $args"
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(isRunning = true, error = null, output = emptyList())
                    toolBox.command("iptables", cmd).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
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
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }
