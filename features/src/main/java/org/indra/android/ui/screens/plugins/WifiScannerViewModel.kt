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
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.WifiScan
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class WifiScannerUiState(
    val isScanning: Boolean = false,
    val networks: List<String> = emptyList(),
    /** True once a scan finished — an empty [networks] before the first scan is not the same
     *  as a scan that saw nothing. */
    val finished: Boolean = false,
    val error: String? = null,
)

/** `cmd wifi start-scan` returns immediately; the results are only in the table after a moment. */
private const val SCAN_SETTLE_SECONDS = 3

/**
 * Nearby access points via Android's own Wi-Fi service.
 *
 * This used to run `iw dev <iface> scan` through `shell`, but `iw` ships in neither the native
 * core nor the ROM, so every scan died with `exit 127` while the card stayed openable. `cmd wifi`
 * is part of the platform, so there is no binary to ship.
 */
@HiltViewModel
class WifiScannerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WifiScannerUiState())
        val uiState: StateFlow<WifiScannerUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun scan() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(isScanning = true, error = null, finished = false, networks = emptyList())
                    val transcript = mutableListOf<String>()
                    toolBox.shell(SCAN_SCRIPT).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { transcript += it }
                        if (!result.isStreaming) finish(transcript, result)
                    }
                }
        }

        private fun finish(
            transcript: List<String>,
            result: ToolResult,
        ) {
            pid = -1
            val text = transcript.joinToString("\n")
            _uiState.value =
                _uiState.value.copy(
                    isScanning = false,
                    finished = true,
                    networks = WifiScan.parse(text).map { it.describe() },
                    error =
                        when {
                            wifiIsOff(text) -> "Wi-Fi is turned off — enable Wi-Fi to scan for networks."
                            !result.success -> result.error ?: "The scan did not complete"
                            else -> null
                        },
                )
        }

        private fun wifiIsOff(text: String): Boolean =
            text.lineSequence().any { it.trim().equals("Wifi is disabled", ignoreCase = true) }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isScanning = false)
        }

        private companion object {
            /**
             * `status` first so a radio that is off is reported as "turn Wi-Fi on" rather than
             * as "no networks found"; `start-scan` forces a fresh sweep instead of listing a
             * cached table that may be minutes old.
             */
            const val SCAN_SCRIPT =
                "cmd wifi status; cmd wifi start-scan; sleep $SCAN_SETTLE_SECONDS; cmd wifi list-scan-results"
        }
    }
