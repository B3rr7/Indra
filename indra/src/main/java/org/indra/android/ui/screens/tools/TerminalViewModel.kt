package org.indra.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class TerminalUiState(
    val input: String = "",
    val output: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
    val chrootMode: Boolean = false,
    val alpineInstalled: Boolean = false,
)

@HiltViewModel
class TerminalViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TerminalUiState(alpineInstalled = coreManager.isAlpineInstalled()))
        val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun toggleChroot(enabled: Boolean) {
            _uiState.value = _uiState.value.copy(chrootMode = enabled)
        }

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

            val chrootMode = _uiState.value.chrootMode
            if (chrootMode && !coreManager.isAlpineInstalled()) {
                _uiState.value = _uiState.value.copy(error = "Alpine not installed. Install it in Settings first.")
                return
            }

            // Echo the command as a prompt line.
            val prompt = if (chrootMode) "[alpine] $ $cmd" else "$ $cmd"
            _uiState.value =
                _uiState.value.copy(
                    isRunning = true,
                    error = null,
                    input = "",
                    output = _uiState.value.output + prompt,
                )

            job =
                viewModelScope.launch {
                    val flow = if (chrootMode) toolBox.chroot(cmd) else toolBox.shell(cmd)
                    flow.collect { result ->
                        if (result.pid > 0) pid = result.pid
                        // Only live lines are appended: the final emission repeats the
                        // whole transcript, which would print the command's output twice.
                        result.streamedLine?.let { line ->
                            _uiState.value =
                                _uiState.value.copy(
                                    output = _uiState.value.output + line,
                                )
                        }
                        if (!result.isStreaming) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isRunning = false,
                                    error = if (result.success) null else result.error,
                                )
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
