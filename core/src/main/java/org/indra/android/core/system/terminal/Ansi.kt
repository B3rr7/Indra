// SGR parameters *are* numbers: 30..37 is "normal foreground", 90..97 "bright foreground",
// `38;5;n` the 256-colour form. Naming each one would hide the protocol behind constants.
@file:Suppress("MagicNumber")

package org.indra.android.core.system.terminal

/**
 * A colour a [AnsiSpan] can carry.
 *
 * Deliberately *not* a Compose `Color`: ANSI slots are resolved by the UI from a terminal
 * palette, so `Basic(8)` stays "bright black" whatever the Material theme does, and the
 * parsing stays unit-testable on a plain JVM.
 */
sealed interface AnsiColor {
    /** One of the 16 palette slots (`0`–`7` normal, `8`–`15` bright). */
    data class Basic(
        val index: Int,
    ) : AnsiColor

    /** A 256-colour palette index, sent as `38;5;n` / `48;5;n`. */
    data class Indexed(
        val index: Int,
    ) : AnsiColor

    /** Truecolour, sent as `38;2;r;g;b` / `48;2;r;g;b`. */
    data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    ) : AnsiColor
}

/** What one run of text looks like. `null` foreground/background means "terminal default". */
data class AnsiStyle(
    val foreground: AnsiColor? = null,
    val background: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        val DEFAULT = AnsiStyle()
    }
}

data class AnsiSpan(
    val text: String,
    val style: AnsiStyle = AnsiStyle.DEFAULT,
)

/**
 * Parses the SGR (colour/style) subset of ANSI escapes out of one line of tool output.
 *
 * A terminal transcript needs two things from this: the text *without* the escape noise
 * (cursor moves, erase-line, OSC titles — the daemon does not give us a tty, so tools that
 * try to redraw would otherwise print garbage), and the style runs that survived. `\r`
 * is treated as "rewrite this line", which is what progress bars mean by it.
 */
object Ansi {
    private const val ESC = '\u001B'
    private const val OSC_END = '\u0007'
    private const val ESCAPE_ARG_MAX = 32

    /** Visible spans of [raw], one per style run (adjacent equal styles are merged). */
    fun parse(raw: String): List<AnsiSpan> = Parser().apply { feed(raw) }.finish()

    /** [raw] with every escape and control character removed — what Copy puts on the clipboard. */
    fun strip(raw: String): String = parse(raw).joinToString("") { it.text }

    private class Parser {
        private val spans = mutableListOf<AnsiSpan>()
        private val text = StringBuilder()
        private var style = AnsiStyle.DEFAULT

        fun feed(raw: String) {
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                i =
                    when {
                        c == ESC -> consumeEscape(raw, i)
                        c == '\r' -> {
                            // A progress bar rewriting itself: drop what this line had.
                            text.setLength(0)
                            spans.clear()
                            i + 1
                        }
                        c.code < 0x20 -> i + 1
                        else -> {
                            text.append(c)
                            i + 1
                        }
                    }
            }
        }

        fun finish(): List<AnsiSpan> {
            flush()
            return spans.toList()
        }

        private fun consumeEscape(
            raw: String,
            start: Int,
        ): Int {
            if (start + 1 >= raw.length) return raw.length
            return when (raw[start + 1]) {
                '[' -> consumeCsi(raw, start + 2)
                ']' -> consumeOsc(raw, start + 2)
                else -> (start + 2).coerceAtMost(raw.length)
            }
        }

        /** CSI: `ESC [ params final`. Only `m` (SGR) changes anything; the rest is dropped. */
        private fun consumeCsi(
            raw: String,
            from: Int,
        ): Int {
            var i = from
            while (i < raw.length && raw[i].isParam() && i - from < ESCAPE_ARG_MAX) i++
            if (i >= raw.length) return raw.length
            if (raw[i] == 'm') applySgr(raw.substring(from, i))
            return i + 1
        }

        /** OSC: `ESC ] … BEL` or `ESC ] … ESC \` (window titles, hyperlinks) — swallowed whole. */
        private fun consumeOsc(
            raw: String,
            from: Int,
        ): Int {
            var i = from
            while (i < raw.length && raw[i] != OSC_END && raw[i] != ESC) i++
            return when {
                i >= raw.length -> raw.length
                raw[i] == OSC_END -> i + 1
                // `ESC \` is the ST terminator; ESC as the very last char just ends the string.
                else -> (i + 2).coerceAtMost(raw.length)
            }
        }

        private fun applySgr(params: String) {
            // Close the run *before* the style changes, otherwise the whole line would keep
            // whatever style the last escape left behind.
            flush()
            val parts = params.split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < parts.size) {
                val extended = extended(parts, i)
                if (extended != null) {
                    style = extended.first
                    i += extended.second
                } else {
                    style = applyColour(parts[i]) ?: applyAttribute(parts[i])
                    i++
                }
            }
        }

        /** A colour slot (`30…37`, `90…97`, `40…47`, `100…107`, `39`, `49`), else `null`. */
        private fun applyColour(code: Int): AnsiStyle? =
            when (code) {
                in 30..37 -> style.copy(foreground = AnsiColor.Basic(code - 30))
                in 90..97 -> style.copy(foreground = AnsiColor.Basic(code - 82))
                in 40..47 -> style.copy(background = AnsiColor.Basic(code - 40))
                in 100..107 -> style.copy(background = AnsiColor.Basic(code - 92))
                39 -> style.copy(foreground = null)
                49 -> style.copy(background = null)
                else -> null
            }

        private fun applyAttribute(code: Int): AnsiStyle =
            when (code) {
                0 -> AnsiStyle.DEFAULT
                1 -> style.copy(bold = true)
                2 -> style.copy(dim = true)
                3 -> style.copy(italic = true)
                4 -> style.copy(underline = true)
                7 -> style.copy(inverse = true)
                22 -> style.copy(bold = false, dim = false)
                23 -> style.copy(italic = false)
                24 -> style.copy(underline = false)
                27 -> style.copy(inverse = false)
                else -> style
            }

        /**
         * `38`/`48` with a `5` (256-colour) or `2` (truecolour) sub-command. Returns the new
         * style and how many parameters it consumed, or `null` when [at] is not one of them.
         */
        private fun extended(
            parts: List<Int>,
            at: Int,
        ): Pair<AnsiStyle, Int>? {
            val code = parts[at]
            val mode = parts.getOrNull(at + 1)
            val consumed =
                when (mode) {
                    5 -> 3
                    2 -> 5
                    else -> 0
                }
            val colour =
                when (mode) {
                    5 -> parts.getOrNull(at + 2)?.let { AnsiColor.Indexed(it and 0xFF) }
                    2 ->
                        parts.getOrNull(at + 4)?.let {
                            AnsiColor.Rgb(parts[at + 2] and 0xFF, parts[at + 3] and 0xFF, it and 0xFF)
                        }
                    else -> null
                }
            return when {
                colour == null || (code != 38 && code != 48) -> null
                code == 38 -> style.copy(foreground = colour) to consumed
                else -> style.copy(background = colour) to consumed
            }
        }

        private fun flush() {
            if (text.isEmpty()) return
            val chunk = text.toString()
            text.setLength(0)
            val last = spans.lastOrNull()
            if (last != null && last.style == style) {
                spans[spans.size - 1] = last.copy(text = last.text + chunk)
            } else {
                spans.add(AnsiSpan(chunk, style))
            }
        }
    }
}

private fun Char.isParam(): Boolean = isDigit() || this == ';' || this == ':' || this == '?'
