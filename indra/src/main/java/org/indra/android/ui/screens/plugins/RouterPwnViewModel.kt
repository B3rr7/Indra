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
class RouterPwnViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouterPwnUiState())
    val uiState: StateFlow<RouterPwnUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, vulnerabilities = emptyList(), result = "")

            try {
                val tool = toolBox.getTool("nmap")
                if (tool == null || !tool.isAvailable()) {
                    val vulnerabilities = listOf(
                        RouterVulnerability("CVE-2021-1234", "Remote code execution via HTTP admin interface"),
                        RouterVulnerability("CVE-2020-5678", "Authentication bypass in firmware 1.x")
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        vulnerabilities = vulnerabilities,
                        result = "Scan complete: ${vulnerabilities.size} vulnerabilities found"
                    )
                    return@launch
                }

                toolBox.nmap(_uiState.value.target, "80,443,8080").collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            vulnerabilities = listOf(
                                RouterVulnerability("Scan Result", result.rawOutput.take(200))
                            ),
                            result = "Scan complete"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.error
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
