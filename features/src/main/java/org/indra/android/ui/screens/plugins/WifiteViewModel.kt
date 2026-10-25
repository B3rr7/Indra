package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import java.io.File
import javax.inject.Inject

data class WifiteUiState(
    val iface: String = "wlan0",
    val wordlist: String = "",
    val capturePath: String = "",
    val stage: String = "idle", // idle, monitor, capture, crack, done
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class WifiteViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WifiteUiState(wordlist = toolBox.defaultWordlist(), capturePath = toolBox.capturePath()))
        val uiState: StateFlow<WifiteUiState> = _uiState.asStateFlow()

        fun updateIface(v: String) {
            _uiState.value = _uiState.value.copy(iface = v)
        }

        fun updateWordlist(v: String) {
            _uiState.value = _uiState.value.copy(wordlist = v)
        }

        private var job: Job? = null
        private var pid = -1

        /**
         * Collects one stage, appending only live lines (the final emission repeats the
         * whole transcript) and returning the terminal result so the caller can decide
         * whether to continue.
         */
        private suspend fun stage(
            name: String,
            flow: Flow<ToolResult>,
        ): ToolResult? {
            _uiState.value = _uiState.value.copy(stage = name)
            var terminal: ToolResult? = null
            flow.collect { r ->
                r.streamedLine?.let { line ->
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                }
                if (r.pid > 0) pid = r.pid
                if (!r.isStreaming) terminal = r
            }
            return terminal
        }

        private fun abort(
            what: String,
            result: ToolResult?,
        ) {
            _uiState.value =
                _uiState.value.copy(
                    isRunning = false,
                    stage = "idle",
                    error = "$what: ${result?.error ?: result?.rawOutput?.trim()?.ifEmpty { null } ?: "no output"}",
                )
            pid = -1
        }

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

            job =
                viewModelScope.launch {
                    val qu = SmartToolBox.shellQuote(iface)

                    // Each stage is reported from its child's exit status. The old code
                    // appended "[monitor mode enabled]" / "[capture done]" unconditionally,
                    // so a failure at any step still looked like a completed run.
                    val monitor =
                        stage(
                            "monitor",
                            toolBox.shell("ip link set $qu down && iw dev $qu set type monitor && ip link set $qu up"),
                        )
                    if (monitor?.success != true) {
                        abort("Monitor mode could not be enabled", monitor)
                        return@launch
                    }

                    val outFile = _uiState.value.capturePath
                    File(outFile).parentFile?.mkdirs()
                    val capture = stage("capture", toolBox.tcpdump(iface, "", 50, outFile))
                    if (capture?.success != true) {
                        abort("Capture failed", capture)
                        return@launch
                    }

                    val wordlist = _uiState.value.wordlist.trim()
                    val crack =
                        stage(
                            "crack",
                            toolBox.aircrack(
                                "-w ${SmartToolBox.shellQuote(wordlist)} ${SmartToolBox.shellQuote(outFile)}",
                            ),
                        )

                    _uiState.value =
                        _uiState.value.copy(
                            stage = "done",
                            isRunning = false,
                            error = if (crack?.success == true) null else crack?.error ?: "aircrack did not report a result",
                        )
                    pid = -1
                }
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isRunning = false, stage = "idle")
        }
    }
