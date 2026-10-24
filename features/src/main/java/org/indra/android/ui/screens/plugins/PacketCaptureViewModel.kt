package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import java.io.File
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
        @ApplicationContext private val context: Context,
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
            _uiState.value = _uiState.value.copy(monitorWarningShown = true, monitorMode = enable)
        }

        fun confirmMonitorMode() {
            val iface = _uiState.value.iface.trim()
            if (iface.isEmpty()) return
            val enable = _uiState.value.monitorMode
            val cmd =
                if (enable) {
                    "ip link set $iface down && iw dev $iface set type monitor && ip link set $iface up"
                } else {
                    "ip link set $iface down && iw dev $iface set type managed && ip link set $iface up"
                }
            viewModelScope.launch {
                toolBox.shell(cmd).collect { result ->
                    if (!result.success && result.status != "running") {
                        _uiState.value = _uiState.value.copy(error = result.error ?: "monitor mode change failed")
                    }
                }
            }
        }

        fun cancelMonitorMode() {
            _uiState.value = _uiState.value.copy(monitorWarningShown = false, monitorMode = false)
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
            val outFile =
                if (state.saveToFile) {
                    // The daemon runs as root and writes the file into the app's own
                    // private dir; create the dir here so the path always exists.
                    File(context.filesDir, SmartToolBox.CAPTURE_PATH)
                        .parentFile
                        ?.mkdirs()
                    toolBox.capturePath()
                } else {
                    null
                }

            _uiState.value = state.copy(isActive = true, error = null, packets = emptyList())

            job =
                viewModelScope.launch {
                    toolBox.tcpdump(iface, state.filter.trim(), count, outFile).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput
                        if (result.status == "running") {
                            if (line.isNotBlank() && !line.startsWith("Executing:")) {
                                _uiState.value =
                                    _uiState.value.copy(
                                        packets = (_uiState.value.packets + line).takeLast(MAX_LINES),
                                    )
                            }
                        } else if (result.status == "completed" || result.status == "timeout") {
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
