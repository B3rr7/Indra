// Terminal colours are data, not logic: every literal here is a palette slot or an xterm
// RGB level, so the "magic number" rule has nothing useful to say about them.
@file:Suppress("MagicNumber")

package org.indra.android.ui.screens.terminal

import androidx.compose.ui.graphics.Color
import org.indra.android.core.system.terminal.AnsiColor

/**
 * The terminal's colours.
 *
 * A terminal has its own palette; it must not inherit the Material theme, or a red `[exit 1]`
 * would turn teal on a device with dynamic colours enabled. The 16 base slots follow the
 * usual xterm/One Dark-ish values, then 256-colour and truecolour resolve the same way an
 * xterm does (16 + 6×6×6 cube + 24-step grey ramp).
 */
object TerminalPalette {
    /** The console background — deliberately darker than any Material surface ("screen"). */
    val background = Color(0xFF0B0F14)

    /** Default text, and the colour `\e[0m` falls back to. */
    val foreground = Color(0xFFE6EDF3)

    /** Command lines the user typed (the prompt echo). */
    val prompt = Color(0xFF8AE234)

    /** App chatter that is not tool output, e.g. `[stopped]` or `export FOO kept`. */
    val info = Color(0xFF8B949E)

    /** Failures: `[exit 127]`, a refused command, the daemon being down. */
    val error = Color(0xFFFF7B86)

    /** Chrome accent (bubble ring, panel border, cursor). */
    val accent = Color(0xFF61AFEF)

    private val BASIC =
        listOf(
            Color(0xFF3B4048),
            Color(0xFFE06C75),
            Color(0xFF98C379),
            Color(0xFFE5C07B),
            Color(0xFF61AFEF),
            Color(0xFFC678DD),
            Color(0xFF56B6C2),
            Color(0xFFDCDFE4),
            Color(0xFF5C6370),
            Color(0xFFFF7B86),
            Color(0xFFB5E890),
            Color(0xFFFFD68A),
            Color(0xFF7CC0FF),
            Color(0xFFE39FF6),
            Color(0xFF7FE1EA),
            Color(0xFFFFFFFF),
        )

    /** The six levels of each channel in the 256-colour cube (xterm values). */
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    private const val CUBE_START = 16
    private const val GREY_START = 232
    private const val GREY_BASE = 8
    private const val GREY_STEP = 10
    private const val CUBE_SIZE = 6

    fun of(color: AnsiColor): Color =
        when (color) {
            is AnsiColor.Basic -> BASIC[color.index.coerceIn(0, BASIC.lastIndex)]
            is AnsiColor.Indexed -> indexed(color.index)
            is AnsiColor.Rgb -> Color(color.red, color.green, color.blue)
        }

    private fun indexed(index: Int): Color =
        when {
            index < CUBE_START -> BASIC[index.coerceAtLeast(0)]
            index < GREY_START -> cube(index - CUBE_START)
            else -> {
                val level = GREY_BASE + GREY_STEP * (index - GREY_START).coerceAtMost(GREY_START / 2)
                Color(level, level, level)
            }
        }

    private fun cube(offset: Int): Color {
        val red = CUBE[offset / (CUBE_SIZE * CUBE_SIZE) % CUBE_SIZE]
        val green = CUBE[offset / CUBE_SIZE % CUBE_SIZE]
        val blue = CUBE[offset % CUBE_SIZE]
        return Color(red, green, blue)
    }
}
