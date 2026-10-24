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
    val capability: HidCapability.Capability? = null
)

@HiltViewModel
class DuckyScriptViewModel @Inject constructor(
    private val hidCapability: HidCapability,
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val _uiState = MutableStateFlow(DuckyScriptUiState(capability = hidCapability.detect()))
    val uiState: StateFlow<DuckyScriptUiState> = _uiState.asStateFlow()

    fun updateSource(v: String) { _uiState.value = _uiState.value.copy(source = v) }

    fun parse() {
        val steps = DuckyScriptParser.parse(_uiState.value.source)
        _uiState.value = _uiState.value.copy(parsed = steps, summary = DuckyScriptParser.summarize(steps))
    }

    fun run() {
        val st = _uiState.value
        val cap = st.capability ?: hidCapability.detect()
        if (!cap.supportsHid) {
            _uiState.value = st.copy(error = "HID not supported on this device")
            return
        }
        viewModelScope.launch {
            _uiState.value = st.copy(status = "Running…", error = null)
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = "Native daemon not available")
                return@launch
            }
            // Best-effort: type text and inject keys via /dev/hidg0 HID reports.
            st.parsed.forEach { step ->
                when (step) {
                    is DuckyStep.TypeText -> {
                        val escaped = step.text.replace("'", "'\\''")
                        toolBox.shell("printf '%s' '$escaped' > /dev/hidg0").collect {}
                    }
                    is DuckyStep.Delay -> kotlinx.coroutines.delay(step.ms.toLong())
                    is DuckyStep.Key -> {
                        val code = hidKeyCode(step.code)
                        val mod = step.modifiers.fold(0) { acc, m -> acc or hidModifier(m) }
                        // Press report: [modifier, reserved, key1..key6]
                        val press = byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0)
                        val release = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
                        toolBox.shell("printf '\\x${press.joinToString("") { "%02x".format(it.toInt() and 0xff) }}' > /dev/hidg0").collect {}
                        kotlinx.coroutines.delay(10)
                        toolBox.shell("printf '\\x${release.joinToString("") { "%02x".format(it.toInt() and 0xff) }}' > /dev/hidg0").collect {}
                    }
                    is DuckyStep.Repeat -> { /* repeat last step; simplified: skip */ }
                }
            }
            _uiState.value = _uiState.value.copy(status = "Done")
        }
    }
}