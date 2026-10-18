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
class LoginCrackerViewModel @Inject constructor(
    private val system: IndraSystem,
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

            try {
                val tool = toolBox.getTool("hydra")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        result = "Native daemon not available"
                    )
                    return@launch
                }

                val args = "-l ${_uiState.value.username} -P /usr/share/wordlists/rockyou.txt ${_uiState.value.target} ${_uiState.value.service}"
                toolBox.hydra(_uiState.value.target, _uiState.value.service, _uiState.value.username).collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            result = result.rawOutput
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
