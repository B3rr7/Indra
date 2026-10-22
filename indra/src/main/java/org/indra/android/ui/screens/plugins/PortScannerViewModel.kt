package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class PortScannerUiState(
    val target: String = "",
    val portRange: String = "1-1000",
    val isLoading: Boolean = false,
    val results: List<PortScanResult> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class PortScannerViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortScannerUiState())
    val uiState: StateFlow<PortScannerUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun updatePortRange(range: String) {
        _uiState.value = _uiState.value.copy(portRange = range)
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, results = emptyList())

            try {
                val nmapTool = toolBox.getTool("nmap")
                if (nmapTool == null || !nmapTool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "NMap handler is not available. Install the core and start the daemon in Settings."
                    )
                    return@launch
                }

                if (_uiState.value.target.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Enter a target host")
                    return@launch
                }

                toolBox.nmap(_uiState.value.target, _uiState.value.portRange).collect { toolResult ->
                    when (toolResult.status) {
                        "running" -> Unit
                        else -> {
                            if (!toolResult.success) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = toolResult.error ?: "Scan failed"
                                )
                            } else {
                                val results = toolResult.ports
                                    .map { PortScanResult(it.port, it.service ?: it.protocol ?: "unknown") }
                                    .ifEmpty { parsePorts(toolResult.rawOutput) }
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    results = results,
                                    error = if (results.isEmpty()) "No open ports found" else null
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun parsePorts(output: String): List<PortScanResult> {
        val discovered = Regex("Discovered open port (\\d+)/(\\w+)").findAll(output)
        if (discovered.any()) {
            return discovered.map { PortScanResult(it.groupValues[1].toInt(), it.groupValues[2]) }
                .distinctBy { it.port }
                .sortedBy { it.port }
                .toList()
        }
        // Fallback for standard nmap output: "22/tcp open ssh"
        return Regex("^(\\d+)/(\\w+)\\s+open\\s*(\\S*)", RegexOption.MULTILINE)
            .findAll(output)
            .map { match ->
                PortScanResult(
                    port = match.groupValues[1].toInt(),
                    service = match.groupValues[3].ifEmpty { match.groupValues[2] }
                )
            }
            .distinctBy { it.port }
            .sortedBy { it.port }
            .toList()
    }
}
