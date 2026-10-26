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
    val monitorMode: Boolean = false,
    val monitorWarningShown: Boolean = false,
    val packets: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PacketCaptureViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                PacketCaptureUiState(capturePath = toolBox.capturePath()),
            )
        val uiState: StateFlow<PacketCaptureUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun updateIface(iface: String) {
            _uiState.value = _uiState.value.copy(iface = iface)
        }

        fun updateFilter(filter: String) {
            _uiState.value = _uiState.value.copy(filter = filter)
        }

        fun updateCount(count: String) {
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
            val qu = SmartToolBox.shellQuote(iface)
            val cmd =
                if (enterMonitor) {
                    "ip link set $qu down && iw dev $qu set type monitor && ip link set $qu up"
                } else {
                    "ip link set $qu down && iw dev $qu set type managed && ip link set $qu up"
                }
            viewModelScope.launch {
                toolBox.shell(cmd).collect { result ->
                    if (!result.isStreaming && !result.success) {
                        _uiState.value =
                            _uiState.value.copy(
                                // The device did not switch — don't leave the switch on.
                                monitorMode = if (enterMonitor) false else _uiState.value.monitorMode,
                                error = result.error ?: "monitor mode change failed",
                            )
                    }
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

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = state.copy(error = "Native daemon not available")
                return
            }

            val count = state.count.trim().toIntOrNull() ?: 0

            _uiState.value = state.copy(isActive = true, error = null, packets = emptyList())

            job =
                viewModelScope.launch {
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
                    toolBox.tcpdump(iface, state.filter.trim(), count, outFile).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            _uiState.value =
                                _uiState.value.copy(
                                    packets = (_uiState.value.packets + line).takeLast(MAX_LINES),
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
