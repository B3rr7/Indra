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
class VulnScannerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(VulnScannerUiState(nucleiInstalled = coreManager.isNucleiInstalled()))
        val uiState: StateFlow<VulnScannerUiState> = _uiState.asStateFlow()

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun installNuclei() {
            if (_uiState.value.isInstalling) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installNuclei { p ->
                        _uiState.value = _uiState.value.copy(installProgress = p)
                    }.onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                nucleiInstalled = true,
                                installProgress = 1f,
                            )
                    }.onFailure { e ->
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                error = "Nuclei install failed: ${e.message}",
                            )
                    }
            }
        }

        fun scan() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, vulnerabilities = emptyList())

                try {
                    if (!coreManager.isNucleiInstalled()) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = "Nuclei is not installed. Tap \"Install Nuclei\" first.",
                            )
                        return@launch
                    }

                    toolBox.nuclei(_uiState.value.target).collect { result ->
                        if (result.success) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    vulnerabilities = listOf(Vulnerability("Scan Result", "Info", result.rawOutput.take(500))),
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
