package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.engine.EngineInstallState
import org.indra.android.core.system.MonitorMode
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.streamedLine
import org.indra.android.core.system.isStreaming
import javax.inject.Inject

data class PacketCaptureUiState(
    val iface: String = "wlan0",
    val filter: String = "",
    val count: String = "",
    val saveToFile: Boolean = false,
    val capturePath: String = "",
    val isActive: Boolean = false,
    /** True once a capture has been started, so a finished empty capture is not
     *  reported as "not started yet" (and a pre-start header never reads "Captured (0 lines)"). */
    val hasRun: Boolean = false,
    val monitorMode: Boolean = false,
    val monitorWarningShown: Boolean = false,
    val packets: List<String> = emptyList(),
    /**
     * Total lines ever appended to [packets] — monotonic, so the auto-follow
     * scroll effect keeps a usable key after the 500-line cap stops [packets]
     * from growing (a size-keyed effect freezes at the cap).
     */
    val packetCount: Long = 0,
    val error: String? = null,
    /**
     * Set when the mode switch failed only because `iw` is missing: the fix, not a failure, so
     * the screen offers the `apk add` itself instead of telling the user to open a Terminal.
     */
    val gate: CapabilityFix? = null,
    /** The shared engine-install state (another screen may have started the same install). */
    val install: EngineInstallState = EngineInstallState.Idle,
)

@HiltViewModel
@Suppress("TooManyFunctions")
class PacketCaptureViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val toolBox: ToolRunner,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(restoredInputs(PacketCaptureUiState(capturePath = toolBox.capturePath()),))
        val uiState: StateFlow<PacketCaptureUiState> = _uiState.asStateFlow()

        /**
         * Input fields, so a process death does not throw away what the user typed.
         * Results, run state and errors stay volatile on purpose: a restored screen must
         * not claim a scan is still running.
         */
        private fun restoredInputs(state: PacketCaptureUiState) =
            state.copy(
                iface = savedState["iface"] ?: state.iface,
                filter = savedState["filter"] ?: state.filter,
                count = savedState["count"] ?: state.count,
            )

        private var job: Job? = null
        private var pid = -1

        /** True when the user asked to enter monitor mode and only `iw` was in the way. */
        private var retryEnterMonitor = false

        init {
            // One install for the whole app — this screen shows whatever any other screen
            // started, and retries the switch the missing `iw` stopped.
            viewModelScope.launch {
                toolBox.engineInstall.collect { state ->
                    _uiState.value = _uiState.value.copy(install = state)
                    if (state !is EngineInstallState.Done) return@collect
                    _uiState.value = _uiState.value.copy(gate = null)
                    if (retryEnterMonitor) {
                        retryEnterMonitor = false
                        switchMode(enterMonitor = true)
                    }
                }
            }
        }

        /** The fix card's button — starts a single-flight install shared with every screen. */
        fun installFix() {
            _uiState.value.gate?.let { toolBox.installEngineFix(it) }
        }

        fun updateIface(iface: String) {
            savedState["iface"] = iface
            _uiState.value = _uiState.value.copy(iface = iface)
        }

        fun updateFilter(filter: String) {
            savedState["filter"] = filter
            _uiState.value = _uiState.value.copy(filter = filter)
        }

        fun updateCount(count: String) {
            savedState["count"] = count
            _uiState.value = _uiState.value.copy(count = count)
        }

        fun setSaveToFile(save: Boolean) {
            _uiState.value = _uiState.value.copy(saveToFile = save)
        }

        fun requestMonitorMode(enable: Boolean) {
            if (enable) {
                // Entering monitor mode is the risky direction — ask first.
                _uiState.value = _uiState.value.copy(monitorWarningShown = true, monitorMode = true)
            } else {
                // Leaving monitor mode is harmless: flip the switch and revert the
                // interface right away instead of showing the crash warning too.
                _uiState.value = _uiState.value.copy(monitorWarningShown = false, monitorMode = false)
                switchMode(enterMonitor = false)
            }
        }

        /** The warning dialog's button (accepted = Enable, rejected = Cancel). */
        fun onMonitorDialog(accepted: Boolean) {
            if (accepted) {
                _uiState.value = _uiState.value.copy(monitorWarningShown = false)
                switchMode(enterMonitor = true)
            } else {
                _uiState.value = _uiState.value.copy(monitorWarningShown = false, monitorMode = false)
            }
        }

        /** `ip`/`iw` mode change. On failure the switch reverts so the UI matches reality. */
        private fun switchMode(enterMonitor: Boolean) {
            val iface = _uiState.value.iface.trim()
            if (iface.isEmpty()) {
                _uiState.value =
                    _uiState.value.copy(
                        monitorMode = false,
                        error = "Interface is required",
                    )
                return
            }
            viewModelScope.launch {
                // The engine runs this inside the chroot when `iw` is installed there (the ROM
                // has none), and falls back to the host binary otherwise.
                toolBox.monitorMode(iface, enterMonitor).collect { result ->
                    if (result.isStreaming) return@collect
                    if (result.success) {
                        // The switch follows what actually happened, not what was requested —
                        // which matters for the retry an install triggers.
                        _uiState.value = _uiState.value.copy(monitorMode = enterMonitor, gate = null, error = null)
                        return@collect
                    }
                    val fixText = MonitorMode.explainFailure(result.error, result.rawOutput)
                    // Ask the engine which fix applies rather than assuming an `apk add`: with no
                    // rootfs at all the answer is `InstallEngine`, and `apk add` would fail.
                    val fix =
                        if (fixText == null) {
                            null
                        } else {
                            CapabilityFix.of(toolBox.capability(Capability.IW))
                        }
                    retryEnterMonitor = fix != null && enterMonitor
                    // A fresh fix gets a fresh card: the shared state still says "Installed."
                    // from the run that just finished, which would read as this fix's result.
                    if (fix != null) toolBox.resetEngineInstall()
                    _uiState.value =
                        _uiState.value.copy(
                            // The device did not switch — don't leave the switch on.
                            monitorMode = if (enterMonitor) false else _uiState.value.monitorMode,
                            gate = fix,
                            // The fixable case is not an error: the card carries the fix.
                            error =
                                if (fix != null) {
                                    null
                                } else {
                                    result.error ?: "monitor mode change failed"
                                },
                        )
                }
            }
        }

        fun start() {
            if (job?.isActive == true) return
            val state = _uiState.value
            val iface = state.iface.trim()
            if (iface.isEmpty()) {
                _uiState.value = state.copy(error = "Interface is required")
                return
            }

            val count = state.count.trim().toIntOrNull() ?: 0

            job =
                viewModelScope.launch {
                    val gate = toolBox.requireDirectTool("tcpdump")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(
                            isActive = true,
                            hasRun = true,
                            error = null,
                            packets = emptyList(),
                            packetCount = 0,
                        )
                    val outFile =
                        if (state.saveToFile) {
                            // The capture goes to the user-visible workspace
                            // (/sdcard/Indra/captures). The app cannot create that directory
                            // itself on Android 11+ without "All files access", so the root
                            // daemon creates it right before tcpdump writes into it.
                            if (!toolBox.ensureCaptureDir()) {
                                _uiState.value =
                                    _uiState.value.copy(
                                        isActive = false,
                                        error = "Could not create the capture folder; is the daemon running?",
                                    )
                                return@launch
                            }
                            toolBox.capturePath()
                        } else {
                            null
                        }
                    toolBox.tcpdumpDirect(iface, state.filter.trim(), count, outFile).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            val current = _uiState.value
                            _uiState.value =
                                current.copy(
                                    packets = (current.packets + line).takeLast(MAX_LINES),
                                    packetCount = current.packetCount + 1,
                                )
                        }
                        if (!result.isStreaming) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isActive = false,
                                    error = if (result.success) null else result.error,
                                )
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
            private const val MAX_LINES = 500
        }
    }
