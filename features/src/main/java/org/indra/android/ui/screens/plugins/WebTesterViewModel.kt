package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

@HiltViewModel
class WebTesterViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WebTesterUiState(nucleiInstalled = coreManager.isNucleiInstalled()))
        val uiState: StateFlow<WebTesterUiState> = _uiState.asStateFlow()

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun setTestType(type: String) {
            _uiState.value = _uiState.value.copy(testType = type)
        }

        fun installNuclei() {
            if (_uiState.value.isInstalling) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installNuclei { p ->
                        _uiState.value = _uiState.value.copy(installProgress = p)
                    }.onSuccess {
                        _uiState.value = _uiState.value.copy(isInstalling = false, nucleiInstalled = true, installProgress = 1f)
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(isInstalling = false, error = "Nuclei install failed: ${e.message}")
                    }
            }
        }

        fun test() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

                try {
                    if (!coreManager.isNucleiInstalled()) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = "Nuclei is not installed. Install it from the Vulnerability Scanner screen first.",
                            )
                        return@launch
                    }

                    val templates =
                        when (_uiState.value.testType) {
                            "sqli" -> "-tags sqli"
                            "xss" -> "-tags xss"
                            else -> ""
                        }

                    toolBox.nuclei(_uiState.value.target, templates).collect { result ->
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
