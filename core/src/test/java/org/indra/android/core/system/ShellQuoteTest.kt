package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The daemon does not hand the command string to a shell: it tokenizes it itself
 * (`cSploitClient/command.c: parse_cmd`) and `execvp()`s the argv. [SmartToolBox.shell]
 * therefore has to encode its script so that tokenizer yields exactly one argument for
 * `sh -c`.
 *
 * The tokenizer is *not* a POSIX shell: an argument ends at its first closing quote, so
 * adjacent quoted/unquoted segments are split into separate arguments. That is why
 * `shellQuote`-ing a whole script (the original implementation) silently broke every
 * script containing a quoted field — on device `sh -c "./tools/tcpdump -i 'nosuchif0' …"`
 * arrived as `[sh, -c, "./tools/tcpdump -i", "nosuchif0", " -A …"]`, `sh` ran
 * `tcpdump -i`, and the screen showed tcpdump's usage text instead of the failure.
 *
 * [parseCmd] below is a straight port of the C loop so the tests pin the real behaviour.
 */
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
class ShellQuoteTest {
    @Test
    fun `quotes are escaped posix style`() {
        assertEquals("'plain'", SmartToolBox.shellQuote("plain"))
        assertEquals("''", SmartToolBox.shellQuote(""))
        assertEquals("'it'\\''s'", SmartToolBox.shellQuote("it's"))
    }

    @Test
    fun `a whole script reaches sh as one argument`() {
        val scripts =
            listOf(
                "grep -ril 'a b' /tmp 2>/dev/null | head -5",
                "ip link set wlan0 down && ip link set wlan0 up",
                "printf '%s' \"double quoted\" > /dev/hidg0",
                "echo line1\necho line2",
                "it's a 'quoted' string",
                // The shapes that actually ship: a shellQuote'd user field inside a script.
                "ip link set ${SmartToolBox.shellQuote("wlan0")} down && " +
                    "ip link set ${SmartToolBox.shellQuote("wlan0")} address " +
                    "${SmartToolBox.shellQuote("46:17:85:d0:5d:dd")} && ip link set wlan0 up",
                "./tools/tcpdump/tcpdump -i ${SmartToolBox.shellQuote("nosuchif0")} -A -l " +
                    "tcp port 80 2>/dev/null",
                "echo status=\$? && cat /sdcard/My Documents/a'b\"c.txt",
                "mkdir -p '/data/user/0/org.indra.android/files/hid' && printf '\\x04' > /dev/hidg0",
            )
        scripts.forEach { script ->
            assertEquals(script, shellArgv(script).last())
            assertEquals(3, shellArgv(script).size)
        }
    }

    @Test
    fun `the command name and -c are separate arguments`() {
        assertEquals(
            listOf(SmartToolBox.SH_BINARY, "-c", "id"),
            shellArgv("id"),
        )
    }

    @Test
    fun `separators inside a quoted field are still data for sh`() {
        // shellQuote keeps the value as one POSIX word for sh, and encodeArg keeps that
        // word inside the one argument the daemon is given: `;` stays data, not a command
        // separator, because it travels inside a single-quoted run.
        val hostile = "'; rm -rf / #"
        val script = "echo ${SmartToolBox.shellQuote(hostile)}"
        assertEquals(script, shellArgv(script).last())
    }

    @Test
    fun `a script that starts with a quote keeps its leading character`() {
        // parse_cmd only opens an argument on a plain character, so a leading escaped
        // quote/space would be dropped; shell() prefixes a newline for those. The leading
        // quote here is what shellQuote produces for a command that begins with a field.
        val script = "${SmartToolBox.shellQuote("a b")} && echo done"
        assertEquals("\n$script", shellArgv(script).last())
        assertEquals(script, shellArgv(script).last().trimStart('\n'))
    }

    @Test
    fun `posix quoting alone does not survive - documents why encodeArg exists`() {
        // Not a requirement, a regression note: the previous implementation wrapped the
        // whole script in single quotes and the tokenizer split it at every inner quote.
        val script = "ip link set ${SmartToolBox.shellQuote("wlan0")} down"
        assertNotEquals(listOf(script), parseCmd("/system/bin/sh -c ${SmartToolBox.shellQuote(script)}").drop(2))
    }

    /** The argv the daemon would `execvp()` for [script] run through [SmartToolBox.shell]. */
    private fun shellArgv(script: String): List<String> {
        val startsDropped = script.isNotEmpty() && script[0] in charArrayOf(' ', '\t', '\'', '"', '\\')
        val encoded = SmartToolBox.encodeArg(if (startsDropped) "\n$script" else script)
        return parseCmd("${SmartToolBox.SH_BINARY} -c $encoded")
    }

    /**
     * Port of `parse_cmd` (`cSploitClient/command.c`): status flags, quote toggles, and
     * the separate copy loop that removes backslash escapes. Only the argument contents
     * are modelled — not the message sizing.
     */
    private fun parseCmd(command: String): List<String> {
        val insideSingle = 1
        val insideDouble = 2
        val escapeFound = 4
        val args = mutableListOf<String>()
        var status = 0
        var start = -1
        var end = -1
        var pos = 0
        while (true) {
            val c = if (pos < command.length) command[pos] else '\u0000'
            var continueNext = false
            when (c) {
                '"' ->
                    when {
                        status and escapeFound != 0 -> Unit
                        status and insideSingle != 0 -> Unit
                        status and insideDouble != 0 -> {
                            status = status and insideDouble.inv()
                            end = pos
                        }
                        else -> status = status or insideDouble
                    }
                '\'' ->
                    when {
                        status and escapeFound != 0 -> Unit
                        status and insideDouble != 0 -> Unit
                        status and insideSingle != 0 -> {
                            status = status and insideSingle.inv()
                            end = pos
                        }
                        else -> status = status or insideSingle
                    }
                '\\' ->
                    if (status and escapeFound != 0) {
                        status = status and escapeFound.inv()
                    } else {
                        status = status or escapeFound
                        continueNext = true
                    }
                ' ', '\t' -> if (status and escapeFound == 0 && status == 0 && start >= 0) end = pos
                '\u0000' -> {
                    end = pos
                    if (start >= 0) args += unquote(command, start, end)
                    break
                }
                else -> if (start < 0) start = pos
            }
            if (!continueNext) status = status and escapeFound.inv()
            if (start >= 0 && end >= 0) {
                args += unquote(command, start, end)
                start = -1
                end = -1
            }
            pos++
        }
        return args
    }

    /** The C copy loop: `\x` yields `x` for space/tab/quote/backslash, `\x` otherwise. */
    private fun unquote(
        command: String,
        from: Int,
        to: Int,
    ): String {
        val out = StringBuilder()
        var escaping = false
        for (i in from until to) {
            val c = command[i]
            if (escaping) {
                if (c != '\\' && c != '"' && c != '\'' && c != ' ' && c != '\t') out.append('\\')
                out.append(c)
                escaping = false
            } else if (c == '\\') {
                escaping = true
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }
}
