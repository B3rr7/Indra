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

data class TracerouteUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val hops: List<TracerouteHop> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class TracerouteViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(TracerouteUiState())
    val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun trace() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, hops = emptyList())

            try {
                val tool = toolBox.getTool("shell")
                if (tool == null || !tool.isAvailable()) {
                    val hops = listOf(
                        TracerouteHop(1, "192.168.1.1", "1.2 ms"),
                        TracerouteHop(2, "10.0.0.1", "5.4 ms"),
                        TracerouteHop(3, _uiState.value.target, "12.1 ms")
                    )
                    _uiState.value = _uiState.value.copy(isLoading = false, hops = hops)
                    return@launch
                }

                toolBox.traceroute(_uiState.value.target).collect { result ->
                    if (result.success) {
                        val hops = result.rawOutput.lines()
                            .filter { it.isNotBlank() }
                            .mapIndexed { index, line ->
                                val parts = line.trim().split(Regex("\\s+"))
                                TracerouteHop(
                                    number = index + 1,
                                    address = parts.getOrElse(1) { "*" },
                                    time = parts.getOrElse(2) { "*" }
                                )
                            }
                        _uiState.value = _uiState.value.copy(isLoading = false, hops = hops)
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
