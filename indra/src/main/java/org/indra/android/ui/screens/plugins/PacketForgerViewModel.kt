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
class PacketForgerViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(PacketForgerUiState())
    val uiState: StateFlow<PacketForgerUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun setProtocol(protocol: String) {
        _uiState.value = _uiState.value.copy(protocol = protocol)
    }

    fun updatePayload(payload: String) {
        _uiState.value = _uiState.value.copy(payload = payload)
    }

    fun send() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

            try {
                val tool = toolBox.getTool("hping3")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "hping3 is not part of the native core"
                    )
                    return@launch
                }

                val args = "-${_uiState.value.protocol} -p 80 -d ${_uiState.value.payload.length} ${_uiState.value.target}"
                toolBox.hping3(_uiState.value.target, args).collect { result ->
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
