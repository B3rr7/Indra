package org.indra.android.ui.screens.plugins

/**
 * Minimal DuckyScript parser. Converts a DuckyScript source into a list of
 * executable steps. Execution requires a USB gadget HID interface (/dev/hidg0).
 */
sealed class DuckyStep {
    data class TypeText(val text: String) : DuckyStep()
    data class Delay(val ms: Int) : DuckyStep()
    data class Key(val code: String, val modifiers: List<String> = emptyList()) : DuckyStep()
    data class Repeat(val count: Int) : DuckyStep()
}

object DuckyScriptParser {
    fun parse(source: String): List<DuckyStep> {
        val steps = mutableListOf<DuckyStep>()
        source.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("REM")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            val cmd = parts[0].uppercase()
            val arg = if (parts.size > 1) parts[1] else ""
            when (cmd) {
                "STRING" -> steps.add(DuckyStep.TypeText(arg))
                "DELAY" -> arg.toIntOrNull()?.let { steps.add(DuckyStep.Delay(it)) }
                "ENTER" -> steps.add(DuckyStep.Key("ENTER"))
                "TAB" -> steps.add(DuckyStep.Key("TAB"))
                "ESCAPE" -> steps.add(DuckyStep.Key("ESCAPE"))
                "SPACE" -> steps.add(DuckyStep.Key("SPACE"))
                "BACKSPACE" -> steps.add(DuckyStep.Key("BACKSPACE"))
                "DELETE" -> steps.add(DuckyStep.Key("DELETE"))
                "HOME" -> steps.add(DuckyStep.Key("HOME"))
                "END" -> steps.add(DuckyStep.Key("END"))
                "PAGEUP" -> steps.add(DuckyStep.Key("PAGEUP"))
                "PAGEDOWN" -> steps.add(DuckyStep.Key("PAGEDOWN"))
                "UPARROW" -> steps.add(DuckyStep.Key("UP"))
                "DOWNARROW" -> steps.add(DuckyStep.Key("DOWN"))
                "LEFTARROW" -> steps.add(DuckyStep.Key("LEFT"))
                "RIGHTARROW" -> steps.add(DuckyStep.Key("RIGHT"))
                "CAPSLOCK" -> steps.add(DuckyStep.Key("CAPSLOCK"))
                "REPEAT" -> arg.toIntOrNull()?.let { steps.add(DuckyStep.Repeat(it)) }
                "GUI", "WINDOWS" -> steps.add(DuckyStep.Key("GUI"))
                "ALT" -> steps.add(DuckyStep.Key("ALT"))
                "CTRL", "CONTROL" -> steps.add(DuckyStep.Key("CTRL"))
                "SHIFT" -> steps.add(DuckyStep.Key("SHIFT"))
                else -> { /* unknown command, skip */ }
            }
        }
        return steps
    }

    /** Render steps back to a human-readable summary. */
    fun summarize(steps: List<DuckyStep>): String =
        steps.joinToString("\n") { s ->
            when (s) {
                is DuckyStep.TypeText -> "TYPE: ${s.text}"
                is DuckyStep.Delay -> "DELAY: ${s.ms}ms"
                is DuckyStep.Key -> "KEY: ${s.modifiers.joinToString("+")}+${s.code}".trimStart('+')
                is DuckyStep.Repeat -> "REPEAT: ${s.count}"
            }
        }
}