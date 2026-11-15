package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidReports
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.hid.HidLayout
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class HidUiState(
    val text: String = "",
    val status: String = "Idle",
    val error: String? = null,
    /** The USB-gadget gate; null until the screen has asked the device. */
    val gadget: GadgetGate? = null,
    val preparing: Boolean = false,
    /** A sentence after a successful prepare; cleared by the next action. */
    val notice: String? = null
)

@HiltViewModel
class HidViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val toolBox: ToolRunner
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoredInputs(HidUiState()))
    val uiState: StateFlow<HidUiState> = _uiState.asStateFlow()

    /**
     * Input fields, so a process death does not throw away what the user typed.
     * Results, run state and errors stay volatile on purpose: a restored screen must
     * not claim a scan is still running.
     */
    private fun restoredInputs(state: HidUiState) =
        state.copy(
            text = savedState["text"] ?: state.text,
        )

    fun updateText(v: String) {
        savedState["text"] = v
        _uiState.value = _uiState.value.copy(text = v) }

    /** Asks the device whether it can present a HID keyboard, on entry to the screen. */
    fun checkGadget() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(gadget = toolBox.gadgetGate())
        }
    }

    /** Links a HID function into the live gadget, then re-reads the device instead of assuming. */
    fun prepareGadget() {
        val gate = _uiState.value.gadget
        if (gate !is GadgetGate.NeedsPrepare || _uiState.value.preparing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(preparing = true, error = null, notice = null)
            val prepared = toolBox.prepareHidGadget(gate.gadget)
            _uiState.value =
                _uiState.value.copy(
                    preparing = false,
                    gadget = toolBox.gadgetGate(),
                    notice = if (prepared.ready) HidLayout.PREPARED_MESSAGE else null,
                    error = if (prepared.ready) null else HidLayout.notPreparedMessage(prepared.output),
                )
        }
    }

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
            val gate = toolBox.requireTool("shell")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = gate.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(status = "Typing…", error = null, notice = null)
            // A missing /dev/hidg0 is the common case; the gate says which of the three states it is
            // instead of one sentence naming a symptom with no fix.
            val gadget = toolBox.gadgetGate()
            val blocked = gadget.blockedMessage()
            _uiState.value = _uiState.value.copy(gadget = gadget)
            if (blocked != null) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = blocked)
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
