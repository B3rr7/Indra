package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidCapability
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class HidUiState(
    val capability: HidCapability.Capability? = null,
    val text: String = "",
    val status: String = "Idle",
    val error: String? = null
)

@HiltViewModel
class HidViewModel @Inject constructor(
    private val hidCapability: HidCapability,
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(HidUiState(capability = hidCapability.detect()))
    val uiState: StateFlow<HidUiState> = _uiState.asStateFlow()

    fun updateText(v: String) { _uiState.value = _uiState.value.copy(text = v) }

    fun typeText() {
        val st = _uiState.value
        val text = st.text
        if (text.isBlank()) return
        val cap = st.capability ?: hidCapability.detect()
        if (!cap.supportsHid) {
            _uiState.value = st.copy(error = "HID not supported on this device (no configfs/UDC)")
            return
        }
        // Writing to /dev/hidg0 requires the gadget to be configured; this is a best-effort.
        viewModelScope.launch {
            _uiState.value = st.copy(status = "Typing…", error = null)
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = "Native daemon not available")
                return@launch
            }
            // Escape single quotes for shell
            val escaped = text.replace("'", "'\\''")
            toolBox.shell("printf '%s' '$escaped' > /dev/hidg0 2>&1 || echo 'hidg0 not available'").collect { r ->
                if (r.rawOutput.isNotBlank() && !r.rawOutput.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(status = r.rawOutput)
                }
                if (r.status != "running") {
                    _uiState.value = _uiState.value.copy(status = "Done")
                }
            }
        }
    }
}