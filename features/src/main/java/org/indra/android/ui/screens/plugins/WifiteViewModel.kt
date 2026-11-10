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
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.engine.EngineInstallState
import org.indra.android.core.system.MonitorMode
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolRunner
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
    /** Set when monitor mode failed only because `iw` is missing: the fix, not a failure. */
    val gate: CapabilityFix? = null,
    /** The shared engine-install state (another screen may have started the same install). */
    val install: EngineInstallState = EngineInstallState.Idle,
)

@HiltViewModel
class WifiteViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
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

        init {
            // One install for the whole app: show whatever another screen started, and clear the
            // gate once it finished (pressing Run again is the retry — a whole run is not
            // something to restart behind the user's back).
            viewModelScope.launch {
                toolBox.engineInstall.collect { state ->
                    _uiState.value = _uiState.value.copy(install = state)
                    if (state is EngineInstallState.Done) {
                        _uiState.value = _uiState.value.copy(gate = null)
                    }
                }
            }
        }

        /** The fix card's button — starts a single-flight install shared with every screen. */
        fun installFix() {
            _uiState.value.gate?.let { toolBox.installEngineFix(it) }
        }

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
            message: String? = null,
        ) {
            _uiState.value =
                _uiState.value.copy(
                    isRunning = false,
                    stage = "idle",
                    error =
                        message
                            ?: "$what: ${result?.error ?: result?.rawOutput?.trim()?.ifEmpty { null } ?: "no output"}",
                )
            pid = -1
        }

        fun run() {
            if (job?.isActive == true) return
            val st = _uiState.value
            val iface = st.iface.trim().ifEmpty { "wlan0" }
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(isRunning = true, error = null, output = emptyList(), stage = "monitor")

                    // Each stage is reported from its child's exit status. The old code
                    // appended "[monitor mode enabled]" / "[capture done]" unconditionally,
                    // so a failure at any step still looked like a completed run.
                    val monitor = stage("monitor", toolBox.monitorMode(iface, monitor = true))
                    if (monitor?.success != true) {
                        // A missing `iw` is the one failure this screen can fix in-app, so it is
                        // not an error line: the card installs the rootfs or `iw` + `iproute2`,
                        // and Run retries.
                        if (MonitorMode.explainFailure(monitor?.error, monitor?.rawOutput) == null) {
                            abort("Monitor mode could not be enabled", monitor)
                        } else {
                            // A fresh fix gets a fresh card: the shared state still says
                            // "Installed." from the run that just finished.
                            toolBox.resetEngineInstall()
                            _uiState.value =
                                _uiState.value.copy(
                                    isRunning = false,
                                    stage = "idle",
                                    // The engine decides which fix: `apk add iw iproute2` when the
                                    // chroot exists, "Install Alpine" when there is no rootfs.
                                    gate = CapabilityFix.of(toolBox.capability(Capability.IW)),
                                )
                            pid = -1
                        }
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
                            toolBox.aircrackDirect(
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
