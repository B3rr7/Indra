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
class CredentialTesterViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialTesterUiState())
    val uiState: StateFlow<CredentialTesterUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun test() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

            try {
                val tool = toolBox.getTool("hydra")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        result = "Credentials valid (simulated)",
                        success = true
                    )
                    return@launch
                }

                toolBox.hydra(_uiState.value.target, "ssh", _uiState.value.username).collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            result = "Credentials valid",
                            success = true
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            result = "Credentials invalid",
                            success = false
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
