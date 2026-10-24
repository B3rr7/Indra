package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PluginUiState(
    val isLoading: Boolean = false,
    val output: String = "",
    val error: String? = null,
)

@HiltViewModel
class PluginViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(PluginUiState())
        val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()

        @Suppress("UNUSED_PARAMETER")
        fun execute(command: String) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                // TODO: Execute via System/ChildManager
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
