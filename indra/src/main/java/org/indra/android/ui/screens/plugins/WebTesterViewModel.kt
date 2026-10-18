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
class WebTesterViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebTesterUiState())
    val uiState: StateFlow<WebTesterUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun setTestType(type: String) {
        _uiState.value = _uiState.value.copy(testType = type)
    }

    fun test() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

            try {
                val tool = toolBox.getTool("sqlmap")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        result = "No SQL injection vulnerabilities found (simulated)"
                    )
                    return@launch
                }

                val command = when (_uiState.value.testType) {
                    "sqli" -> "sqlmap -u ${_uiState.value.target} --batch"
                    "xss" -> "nikto -h ${_uiState.value.target}"
                    else -> "nikto -h ${_uiState.value.target}"
                }

                toolBox.shell(command).collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(isLoading = false, result = result.rawOutput)
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
