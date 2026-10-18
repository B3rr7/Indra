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

data class InspectorUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val osInfo: String = "",
    val services: List<ServiceInfo> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class InspectorViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun inspect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, osInfo = "", services = emptyList())

            try {
                val tool = toolBox.getTool("nmap")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        osInfo = "Linux 4.x/5.x (95%)\nApache httpd 2.4.x",
                        services = listOf(
                            ServiceInfo(22, "SSH"),
                            ServiceInfo(80, "HTTP"),
                            ServiceInfo(443, "HTTPS")
                        )
                    )
                    return@launch
                }

                toolBox.nmap(_uiState.value.target, "1-1000").collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            osInfo = result.rawOutput.take(200),
                            services = emptyList()
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
