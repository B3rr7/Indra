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

/**
 * hydra prints `[22][ssh] host: 10.0.0.1   login: root   password: toor` on success.
 * Plain string checks are used because Android's ICU regex rejects several
 * patterns that the JVM accepts.
 */
private fun isHydraSuccess(line: String): Boolean =
    line.contains("host:") && (line.contains("login:") || line.contains("password:"))

@HiltViewModel
class CredentialTesterViewModel @Inject constructor(
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
            val state = _uiState.value
            val target = state.target.trim()
            val user = state.username.trim()
            val password = state.password

            if (target.isEmpty() || user.isEmpty() || password.isEmpty()) {
                _uiState.value = state.copy(error = "Target, username and password are required")
                return@launch
            }
            if (password.contains(' ')) {
                _uiState.value = state.copy(error = "Passwords containing spaces are not supported")
                return@launch
            }

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = state.copy(
                    isLoading = false,
                    error = "Native daemon not available"
                )
                return@launch
            }

            _uiState.value = state.copy(isLoading = true, error = null, result = "", success = false)

            val output = StringBuilder()
            var failure: String? = null
            var found = false

            toolBox.hydraCredential(target, "ssh", user, password).collect { result ->
                val line = result.rawOutput
                if (result.status == "running" && line.isNotBlank() && !line.startsWith("Executing:")) {
                    output.appendLine(line)
                    if (isHydraSuccess(line)) found = true
                    _uiState.value = _uiState.value.copy(
                        result = output.toString().trim(),
                        success = found
                    )
                } else if (result.status == "completed" || result.status == "timeout") {
                    if (!result.success) failure = result.error
                }
            }

            val text = output.toString().trim()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                result = text.ifEmpty { if (failure == null) "No result reported by hydra" else "" },
                success = found,
                error = failure
            )
        }
    }
}
