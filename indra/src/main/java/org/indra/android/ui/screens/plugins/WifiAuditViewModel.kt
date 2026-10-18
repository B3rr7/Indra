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
class WifiAuditViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiAuditUiState())
    val uiState: StateFlow<WifiAuditUiState> = _uiState.asStateFlow()

    fun scan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, networks = emptyList())

            try {
                val tool = toolBox.getTool("aircrack")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        networks = listOf(
                            WifiNetwork("HomeNetwork", -45, "WPA2", false),
                            WifiNetwork("GuestWiFi", -62, "WPA2", false),
                            WifiNetwork("OldRouter", -70, "WEP", true)
                        )
                    )
                    return@launch
                }

                toolBox.shell("iwlist wlan0 scan").collect { result ->
                    if (result.success) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
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
