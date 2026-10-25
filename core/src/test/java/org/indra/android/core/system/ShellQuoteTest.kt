package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SmartToolBox.shellQuote] now guards a real shell: `shell()` runs the script through
 * `/system/bin/sh -c`, so an unquoted user field is command injection as root.
 *
 * The daemon does not hand the string to a shell; it tokenizes it itself
 * (`cSploitClient/command.c: parse_cmd`) and `execvp()`s the argv. That tokenizer is
 * quote-aware, so a fully single-quoted script arrives at `sh` byte-for-byte — which is
 * what [daemonUnquote] models here.
 */
class ShellQuoteTest {
    @Test
    fun `quotes are escaped posix style`() {
        assertEquals("'plain'", SmartToolBox.shellQuote("plain"))
        assertEquals("''", SmartToolBox.shellQuote(""))
        assertEquals("'it'\\''s'", SmartToolBox.shellQuote("it's"))
    }

    @Test
    fun `a whole script survives the daemon tokenizer unchanged`() {
        val scripts =
            listOf(
                "grep -ril 'a b' /tmp 2>/dev/null | head -5",
                "ip link set wlan0 down && ip link set wlan0 up",
                "printf '%s' \"double quoted\" > /dev/hidg0",
                "echo line1\necho line2",
                "it's a 'quoted' string",
            )
        scripts.forEach { script ->
            assertEquals(script, daemonUnquote(SmartToolBox.shellQuote(script)))
        }
    }

    @Test
    fun `separators inside a quoted argument are inert`() {
        // shellQuote makes the value ONE argument: the daemon keeps the ';' inside the
        // token, so sh receives it as data, not as a command separator.
        val hostile = "'; rm -rf / #"
        assertEquals(hostile, daemonUnquote(SmartToolBox.shellQuote(hostile)))
    }

    /**
     * Minimal model of `parse_cmd`'s unquoting for a single token: text inside single
     * quotes is copied verbatim, a backslash escapes the next character, and quotes
     * themselves are not part of the result.
     */
    private fun daemonUnquote(token: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < token.length) {
            when (token[i]) {
                '\'' -> {
                    var j = i + 1
                    while (j < token.length && token[j] != '\'') {
                        out.append(token[j])
                        j++
                    }
                    i = j + 1
                }

                '"' -> {
                    var j = i + 1
                    while (j < token.length && token[j] != '"') {
                        if (token[j] == '\\' && j + 1 < token.length) j++
                        out.append(token[j])
                        j++
                    }
                    i = j + 1
                }

                '\\' -> {
                    if (i + 1 < token.length) out.append(token[i + 1])
                    i += 2
                }

                else -> {
                    out.append(token[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
