package org.indra.android.core.client.protocol

/**
 * Pure-Kotlin port of `cSploitClient/command.c: parse_cmd`.
 *
 * The daemon has **no shell**: a command string is split into argv tokens here (natively, before
 * the message is sent) and `execvp()`-ed directly. The tokenizer is not a POSIX shell:
 *
 *  - an argument **ends at the first closing quote**, so `'a b'c` is `a b` then `c`, not `a bc`;
 *  - only `\`, `"`, `'`, space and tab are backslash-escapable (`\x` yields `x`);
 *  - an empty quoted argument (`''`) is dropped;
 *  - an argument only starts on a plain character, so a leading escaped space/quote is dropped.
 *
 * This mirrors `ShellText.encodeArg`'s documented round-trip — the app encodes a script for the
 * tokenizer, and this decodes it again on the Kotlin side. Tests pin both directions.
 */
object CommandTokenizer {

    private const val INSIDE_SINGLE = 1
    private const val INSIDE_DOUBLE = 2
    private const val ESCAPE = 4
    private const val END = 8

    private val ESCAPABLE = charArrayOf('\\', '"', '\'', ' ', '\t')

    // A faithful port of parse_cmd's single state machine; splitting it would obscure the
    // branch-for-branch correspondence with the C source the tests pin.
    @Suppress("CyclomaticComplexMethod")
    fun tokenize(command: String): List<String> {
        val tokens = ArrayList<String>()
        var status = 0
        var start = -1
        var end = -1
        val length = command.length
        var i = 0

        while (true) {
            val c = if (i < length) command[i] else '\u0000'
            // A backslash continues the loop without clearing ESCAPE, exactly like the C
            // `continue`; every other character clears it after being handled.
            var continuation = false

            when (c) {
                '"' ->
                    when {
                        status and ESCAPE != 0 -> Unit
                        status and INSIDE_SINGLE != 0 -> Unit
                        status and INSIDE_DOUBLE != 0 -> {
                            status = status and INSIDE_DOUBLE.inv()
                            end = i
                        }
                        else -> status = status or INSIDE_DOUBLE
                    }
                '\'' ->
                    when {
                        status and ESCAPE != 0 -> Unit
                        status and INSIDE_DOUBLE != 0 -> Unit
                        status and INSIDE_SINGLE != 0 -> {
                            status = status and INSIDE_SINGLE.inv()
                            end = i
                        }
                        else -> status = status or INSIDE_SINGLE
                    }
                '\\' ->
                    if (status and ESCAPE == 0) {
                        status = status or ESCAPE
                        continuation = true
                    }
                ' ', '\t' ->
                    if (status and ESCAPE == 0 && status == 0 && start >= 0) end = i
                '\u0000' -> {
                    status = status or END
                    end = i
                }
                else -> if (start < 0) start = i
            }

            if (!continuation) status = status and ESCAPE.inv()

            if (!continuation && start >= 0 && end >= 0) {
                tokens += unescape(command, start, end)
                start = -1
                end = -1
            }

            if (status and END != 0) break
            i++
        }

        return tokens
    }

    /** Copy `[from, to)` removing the escapes the tokenizer honours; an unmatched `\` is dropped. */
    private fun unescape(source: String, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var escaping = false
        var i = from
        while (i < to) {
            val c = source[i]
            if (escaping) {
                escaping = false
                if (c !in ESCAPABLE) sb.append('\\')
                sb.append(c)
            } else if (c == '\\') {
                escaping = true
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
