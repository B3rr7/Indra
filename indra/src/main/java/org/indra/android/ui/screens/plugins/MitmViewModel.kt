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
class MitmViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(MitmUiState())
    val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

    fun updateTarget(target: String) {
        _uiState.value = _uiState.value.copy(target = target)
    }

    fun setAttackType(type: String) {
        _uiState.value = _uiState.value.copy(attackType = type)
    }

    fun start() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isActive = true,
                log = _uiState.value.log + "Starting ${_uiState.value.attackType} attack on ${_uiState.value.target}"
            )

            try {
                val tool = toolBox.getTool("bettercap")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        log = _uiState.value.log + "Native daemon not available - simulated mode"
                    )
                    return@launch
                }

                val args = when (_uiState.value.attackType) {
                    "sniff" -> "-T ${_uiState.value.target} -X"
                    "spoof" -> "-T ${_uiState.value.target} --dns.spoof"
                    "inject" -> "-T ${_uiState.value.target} --http.proxy"
                    else -> "-T ${_uiState.value.target}"
                }

                toolBox.bettercap(args).collect { result ->
                    _uiState.value = _uiState.value.copy(
                        log = _uiState.value.log + result.rawOutput
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    log = _uiState.value.log + "Error: ${e.message}"
                )
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isActive = false,
                log = _uiState.value.log + "Stopping attack"
            )
        }
    }
}
