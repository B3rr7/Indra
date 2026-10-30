package org.indra.android.core.system

/**
 * The two different kinds of quoting a command string needs on its way to the daemon.
 *
 * There are two layers, and confusing them silently breaks commands:
 *
 * 1. [encodeArg] — how one argument is handed to the daemon's own tokenizer, which is **not**
 *    a POSIX shell.
 * 2. [quote] — POSIX single-quoting of a *user-typed field* that ends up inside a script that
 *    `/system/bin/sh` will execute.
 *
 * A script is `sh -c <encodeArg(script)>`: the script text passes the tokenizer as one argument,
 * and its contents are then shell syntax (so `&&`, `|`, `>` work). Any user field interpolated
 * into it must be [quote]d first, because the shell runs as root.
 *
 * Pure and unit-tested (`ShellQuoteTest` ports `parse_cmd` and pins the round-trip), so the rules
 * are verified rather than discovered on a device.
 */
object ShellText {
    /**
     * Single-quotes a value for the shell that executes `shell()` command strings
     * (POSIX `'"'"'` escaping, same rule as `CoreManager.shellQuote`).
     * Every user-typed field interpolated into a command must go through this.
     *
     * This quoting is for `sh` *inside* the script. It is not how the script is handed to the
     * daemon — see [encodeArg].
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Encodes one argument for the daemon's tokenizer (`cSploitClient/command.c: parse_cmd`),
     * which is *not* a POSIX shell.
     *
     * A quote starts a quoted run and the argument **ends at the first closing quote**:
     * `a 'b c' d` is `a`, `b c`, `d`, but `'a b'c` is `a b` followed by `c`. So adjacent
     * quoted/unquoted segments cannot be joined into one word, and POSIX single-quoting a whole
     * script silently splits it — that turned `sh -c "./tools/tcpdump -i 'nosuchif0' -A …"` into
     * three arguments, `sh` ran `tcpdump -i`, and the screen reported tcpdump's usage text.
     *
     * What the tokenizer does honour is backslash-escaping of space, tab, `'`, `"` and `\`
     * (`\x` yields `x`, each escape counted and removed). Everything else — `;`, `|`, `>`, `$`,
     * `?`, `&`, newlines — passes through untouched for `sh` to interpret.
     *
     * Note an argument only starts on a plain character, so a *leading* escaped
     * space/tab/quote/backslash is dropped; see [startsWithDroppedChar].
     */
    fun encodeArg(value: String): String =
        buildString {
            value.forEach { c ->
                when (c) {
                    ' ', '\t', '\'', '"', '\\' -> append('\\').append(c)
                    else -> append(c)
                }
            }
        }

    /** True when [value] begins with a character [encodeArg] must escape but the tokenizer would
     *  drop (it only starts an argument on a plain character). */
    fun startsWithDroppedChar(value: String): Boolean =
        value.isNotEmpty() && value[0] in charArrayOf(' ', '\t', '\'', '"', '\\')
}
