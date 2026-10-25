package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidCapability
import org.indra.android.core.system.HidReports
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
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
        val script = HidReports.typingScript(text)
        if (script.isEmpty()) {
            _uiState.value = st.copy(error = "Nothing in that text can be typed on a boot keyboard")
            return
        }
        // The text is sent as boot-keyboard reports, not ASCII: the gadget only understands
        // 8-byte reports. /dev/hidg0 itself only exists once a hid.* function is bound to a
        // UDC, so the outcome is reported from the child's exit status instead of assumed.
        viewModelScope.launch {
            _uiState.value = st.copy(status = "Typing…", error = null)
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = "Native daemon not available")
                return@launch
            }
            // A missing /dev/hidg0 (no gadget bound to a UDC) is the common case; report it
            // instead of letting the write silently go nowhere.
            if (toolBox.shell("[ -e ${HidReports.DEVICE} ]").lastOrNull()?.success != true) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = HidReports.DEVICE_MISSING_MESSAGE)
                return@launch
            }
            toolBox.shell(script).collect { r ->
                r.streamedLine?.let { line ->
                    _uiState.value = _uiState.value.copy(status = line)
                }
                if (!r.isStreaming) {
                    _uiState.value =
                        _uiState.value.copy(
                            status =
                                if (r.success) {
                                    "Typed ${text.length} characters"
                                } else {
                                    "Idle"
                                },
                            error = if (r.success) null else (r.error ?: "HID write failed"),
                        )
                }
            }
        }
    }
}