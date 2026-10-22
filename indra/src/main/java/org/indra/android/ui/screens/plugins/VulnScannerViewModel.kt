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

@HiltViewModel
class VulnScannerViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(VulnScannerUiState())
    val uiState: StateFlow<VulnScannerUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, vulnerabilities = emptyList())

            try {
                val tool = toolBox.getTool("nuclei")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "nuclei is not part of the native core"
                    )
                    return@launch
                }

                toolBox.nuclei(_uiState.value.target).collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            vulnerabilities = listOf(Vulnerability("Scan Result", "Info", result.rawOutput.take(200)))
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = result.error)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
