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
        private val toolBox: SmartToolBox,
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
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(error = "Native daemon not available")
                return
            }
            _uiState.value = _uiState.value.copy(isRunning = true, error = null, output = emptyList())
            val cmd = "iptables $args"
            job =
                viewModelScope.launch {
                    toolBox.shell(cmd).collect { result ->
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
