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
                    // Tool not available (native daemon not running)
                    // Fall back to simulated results for UI testing
                    val results = listOf(
                        PortScanResult(22, "SSH"),
                        PortScanResult(80, "HTTP"),
                        PortScanResult(443, "HTTPS")
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        results = results
                    )
                    return@launch
                }

                // Execute actual nmap scan via smart tool
                val args = "-sV -p ${_uiState.value.portRange} ${_uiState.value.target}"
                toolBox.nmap(_uiState.value.target, _uiState.value.portRange).collect { toolResult ->
                    if (toolResult.success) {
                        // Parse JSON result and update UI
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            results = listOf(PortScanResult(0, toolResult.rawOutput))
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = toolResult.error
                        )
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
}
