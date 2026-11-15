package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidReports
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.hid.HidLayout
import javax.inject.Inject

private fun hidKeyCode(name: String): Int = when (name.uppercase()) {
    "A" -> 0x04; "B" -> 0x05; "C" -> 0x06; "D" -> 0x07; "E" -> 0x08
    "F" -> 0x09; "G" -> 0x0A; "H" -> 0x0B; "I" -> 0x0C; "J" -> 0x0D
    "K" -> 0x0E; "L" -> 0x0F; "M" -> 0x10; "N" -> 0x11; "O" -> 0x12
    "P" -> 0x13; "Q" -> 0x14; "R" -> 0x15; "S" -> 0x16; "T" -> 0x17
    "U" -> 0x18; "V" -> 0x19; "W" -> 0x1A; "X" -> 0x1B; "Y" -> 0x1C; "Z" -> 0x1D
    "1" -> 0x1E; "2" -> 0x1F; "3" -> 0x20; "4" -> 0x21; "5" -> 0x22
    "6" -> 0x23; "7" -> 0x24; "8" -> 0x25; "9" -> 0x26; "0" -> 0x27
    "ENTER" -> 0x28; "ESCAPE", "ESC" -> 0x29; "BACKSPACE" -> 0x2A; "TAB" -> 0x2B
    "SPACE" -> 0x2C; "MINUS", "-" -> 0x2D; "EQUALS", "=" -> 0x2E
    "LEFTBRACE", "[" -> 0x2F; "RIGHTBRACE", "]" -> 0x30; "BACKSLASH", "\\" -> 0x31
    "SEMICOLON", ";" -> 0x33; "QUOTE", "'" -> 0x34; "GRAVE", "`" -> 0x35
    "COMMA", "," -> 0x36; "DOT", "." -> 0x37; "SLASH", "/" -> 0x38
    "CAPSLOCK" -> 0x39; "F1" -> 0x3A; "F2" -> 0x3B; "F3" -> 0x3C; "F4" -> 0x3D
    "F5" -> 0x3E; "F6" -> 0x3F; "F7" -> 0x40; "F8" -> 0x41; "F9" -> 0x42
    "F10" -> 0x43; "F11" -> 0x44; "F12" -> 0x45
    "PRINTSCREEN" -> 0x46; "SCROLLLOCK" -> 0x47; "PAUSE" -> 0x48
    "INSERT" -> 0x49; "HOME" -> 0x4A; "PAGEUP" -> 0x4B; "DELETE" -> 0x4C
    "END" -> 0x4D; "PAGEDOWN" -> 0x4E; "RIGHT" -> 0x4F; "LEFT" -> 0x50
    "DOWN" -> 0x51; "UP" -> 0x52; "NUMLOCK" -> 0x53
    else -> 0
}

private fun hidModifier(name: String): Int = when (name.uppercase()) {
    "CTRL", "CONTROL" -> 0x01
    "SHIFT" -> 0x02
    "ALT" -> 0x04
    "GUI", "WINDOWS", "COMMAND" -> 0x08
    else -> 0
}

data class DuckyScriptUiState(
    val source: String = "REM Hello\nSTRING Hello World\nENTER",
    val parsed: List<DuckyStep> = emptyList(),
    val summary: String = "",
    val status: String = "Idle",
    val error: String? = null,
    /** The USB-gadget gate; null until the screen has asked the device. */
    val gadget: GadgetGate? = null,
    val preparing: Boolean = false,
    /** A sentence after a successful prepare; cleared by the next action. */
    val notice: String? = null
)

@HiltViewModel
class DuckyScriptViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val toolBox: ToolRunner
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoredInputs(DuckyScriptUiState()))
    val uiState: StateFlow<DuckyScriptUiState> = _uiState.asStateFlow()

    /**
     * Input fields, so a process death does not throw away what the user typed.
     * Results, run state and errors stay volatile on purpose: a restored screen must
     * not claim a scan is still running.
     */
    private fun restoredInputs(state: DuckyScriptUiState) =
        state.copy(
            source = savedState["source"] ?: state.source,
        )

    fun updateSource(v: String) {
        savedState["source"] = v
        _uiState.value = _uiState.value.copy(source = v) }

    fun parse() {
        val steps = DuckyScriptParser.parse(_uiState.value.source)
        _uiState.value = _uiState.value.copy(parsed = steps, summary = DuckyScriptParser.summarize(steps))
    }

    /**
     * Asks the device whether it can present a HID keyboard, on entry to the screen.
     *
     * One root shell, and it is what turns "hidg0 not available" into either a button or a reason.
     */
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

    fun run() {
        val st = _uiState.value
        if (st.parsed.isEmpty()) {
            _uiState.value = st.copy(error = "Nothing to run: parse the script first")
            return
        }
        viewModelScope.launch {
            val gate = toolBox.requireTool("shell")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = gate.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(status = "Running…", error = null, notice = null)
            // The node is the authority, but the *gate* is what explains it: without /dev/hidg0 every
            // write would silently vanish, and the one sentence that used to say so named no fix.
            val gadget = toolBox.gadgetGate()
            val blocked = gadget.blockedMessage()
            _uiState.value = _uiState.value.copy(gadget = gadget)
            if (blocked != null) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = blocked)
                return@launch
            }

            // Steps are awaited one at a time: firing them off with collect {} let a key's
            // press/release interleave with the following steps' writes.
            var failure: String? = null
            for (step in st.parsed) {
                failure = runStep(step)
                if (failure != null) break
            }
            _uiState.value =
                _uiState.value.copy(
                    status = if (failure == null) "Done (${st.parsed.size} steps)" else "Idle",
                    error = failure,
                )
        }
    }

    /** Runs one parsed step; returns a failure message, or null when it succeeded. */
    private suspend fun runStep(step: DuckyStep): String? =
        when (step) {
            is DuckyStep.TypeText -> {
                val script = HidReports.typingScript(step.text)
                if (script.isEmpty()) null else write(script)
            }

            is DuckyStep.Delay -> {
                delay(step.ms.toLong())
                null
            }

            is DuckyStep.Key -> runKey(step)

            is DuckyStep.Repeat -> "REPEAT is not supported"
        }

    private suspend fun runKey(step: DuckyStep.Key): String? {
        val code = hidKeyCode(step.code)
        val mod = step.modifiers.fold(0) { acc, m -> acc or hidModifier(m) }
        write(HidReports.writeCommand(HidReports.pressReport(mod, code)))?.let { return it }
        delay(KEY_HOLD_MS)
        return write(HidReports.writeCommand(HidReports.releaseReport()))
    }

    private suspend fun write(script: String): String? =
        toolBox.shell(script).lastOrNull()?.let { result ->
            if (result.success) null else result.error ?: "HID write failed"
        }

    private companion object {
        /** How long a key stays pressed before the release report. */
        const val KEY_HOLD_MS = 10L
    }
}
