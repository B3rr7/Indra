package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

@HiltViewModel
class LoginCrackerViewModel @Inject constructor(
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginCrackerUiState())
    val uiState: StateFlow<LoginCrackerUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun updateService(service: String) {
        _uiState.value = _uiState.value.copy(service = service)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun crack() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isLoading = true, error = null, result = "")

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Native daemon not available"
                )
                return@launch
            }

            val output = StringBuilder()
            var failure: String? = null

            toolBox.hydra(state.target.trim(), state.service.trim(), state.username.trim())
                .collect { result ->
                    val line = result.rawOutput
                    if (result.status == "running" && line.isNotBlank() && !line.startsWith("Executing:")) {
                        output.appendLine(line)
                        _uiState.value = _uiState.value.copy(result = output.toString().trim())
                    } else if (result.status == "completed" || result.status == "timeout") {
                        if (result.success) {
                            if (output.isEmpty()) output.append(result.rawOutput)
                        } else {
                            failure = result.error
                        }
                    }
                }

            val text = output.toString().trim()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                result = text.ifEmpty { if (failure == null) "No result reported by hydra" else "" },
                error = failure
            )
        }
    }
}
