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
    /**
     * Lines appended since the transcript was last cleared — unlike [output]
     * (capped, and reset by Clear) this only ever grows while a transcript is
     * live, so the UI can key auto-follow scroll effects on it.
     */
    val lineCount: Long = 0,
    val isRunning: Boolean = false,
    val error: String? = null,
    val chrootMode: Boolean = false,
    val alpineInstalled: Boolean = false,
)

/** Rolling transcript bound — verbose tools must not grow the list unbounded. */
private const val MAX_OUTPUT_LINES = 500

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

        private fun appendOutput(line: String) {
            val state = _uiState.value
            val appended = state.output + line
            _uiState.value =
                state.copy(
                    output = if (appended.size > MAX_OUTPUT_LINES) appended.takeLast(MAX_OUTPUT_LINES) else appended,
                    lineCount = state.lineCount + 1,
                )
        }

        fun toggleChroot(enabled: Boolean) {
            _uiState.value = _uiState.value.copy(chrootMode = enabled)
        }

        fun updateInput(text: String) {
            _uiState.value = _uiState.value.copy(input = text)
        }

        fun run() {
            val cmd = _uiState.value.input.trim()
            if (cmd.isEmpty()) return
            if (job?.isActive == true) {
                // A job is active: do not start a second one, but never let the
                // keystroke vanish silently — tell the user on the transcript.
                appendOutput("[busy] '$cmd' not run — a command is already running")
                _uiState.value = _uiState.value.copy(input = "")
                return
            }

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
                )
            appendOutput(prompt)

            job =
                viewModelScope.launch {
                    val flow = if (chrootMode) toolBox.chroot(cmd) else toolBox.shell(cmd)
                    flow.collect { result ->
                        if (result.pid > 0) pid = result.pid
                        // Only live lines are appended: the final emission repeats the
                        // whole transcript, which would print the command's output twice.
                        result.streamedLine?.let { line -> appendOutput(line) }
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
            appendOutput("[stopped]")
            _uiState.value = _uiState.value.copy(isRunning = false)
        }

        fun clear() {
            if (job?.isActive == true) return
            // Reset the counter with the transcript: leaving it behind would make
            // headTrimmed (= lineCount - output.size) jump by the whole cleared
            // length and hand the follower a bogus trim baseline.
            _uiState.value = _uiState.value.copy(output = emptyList(), lineCount = 0)
        }
    }
