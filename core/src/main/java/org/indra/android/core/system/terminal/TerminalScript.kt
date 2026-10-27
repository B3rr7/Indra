package org.indra.android.core.system.terminal

import org.indra.android.core.system.SmartToolBox

/**
 * Builds the script one typed terminal command is turned into, and reads back the state the
 * shell could not tell us otherwise.
 *
 * The daemon `execvp()`s argv (no pty, no session): every command starts a **fresh**
 * `/system/bin/sh`. To make the terminal behave like one, the shell we do get is asked to
 * `cd` back into the last known directory, re-export the variables the user set, and finally
 * print one marker line carrying the exit status and the resulting `$PWD` — which is what
 * makes `cd` stick for the *next* command.
 *
 * Pure on purpose (see `TerminalScriptTest`): the quoting and the marker are exactly the
 * parts that are painful to debug on a device.
 */
object TerminalScript {
    /** Marker prefix of the trailing state line; never shown in the transcript. */
    const val MARKER = "__INDRA_DONE__ "

    /** How long a terminal command may run before the app gives up on it (30 min). */
    const val TIMEOUT_MS = 30 * 60_000L

    private const val EXPORT = "export "

    /**
     * TERM/colour hints: without a tty most tools drop their colours, and a terminal that
     * renders ANSI should at least give them the chance to emit it.
     */
    private const val COLOUR_ENV = "export TERM=xterm-256color CLICOLOR_FORCE=1 FORCE_COLOR=1; "

    /** The shell state the terminal keeps between commands. */
    data class State(
        val cwd: String = "",
        val env: Map<String, String> = emptyMap(),
    )

    data class Sentinel(
        val exitCode: Int,
        val cwd: String,
    )

    /**
     * The script for [command], prefixed with the restored cwd/env and suffixed with the
     * state marker and an exit that preserves the command's own status.
     */
    fun build(
        command: String,
        state: State,
    ): String =
        buildString {
            if (state.cwd.isNotBlank()) append("cd ").append(SmartToolBox.shellQuote(state.cwd)).append("; ")
            state.env.forEach { (key, value) ->
                append("export ").append(key).append('=').append(SmartToolBox.shellQuote(value)).append("; ")
            }
            append(COLOUR_ENV)
            // The command goes on its own line so `$?` below is unambiguously its status.
            append(command).append('\n')
            append("__indra_st=\$?\n")
            append("printf '").append(MARKER).append("%s %s\\n' \"\$__indra_st\" \"\$PWD\"\n")
            append("exit \$__indra_st")
        }

    /** `export NAME=value` — a single assignment, the form worth remembering between commands. */
    fun parseExport(command: String): Pair<String, String>? {
        val body = command.removePrefix(EXPORT).trim()
        val name = body.substringBefore('=').trim()
        val single = !body.contains(' ') && !body.contains(';') && body.contains('=')
        return if (command.startsWith(EXPORT) && single && isIdentifier(name)) {
            name to body.substringAfter('=')
        } else {
            null
        }
    }

    /** True for `export NAME` (no value) — which only makes sense for a variable we kept. */
    fun parseExportName(command: String): String? {
        if (!command.startsWith(EXPORT)) return null
        val body = command.removePrefix(EXPORT).trim()
        return body.takeIf { !it.contains(' ') && !it.contains('=') && isIdentifier(it) }
    }

    fun parseSentinel(line: String): Sentinel? {
        if (!line.startsWith(MARKER)) return null
        val rest = line.removePrefix(MARKER)
        val split = rest.indexOf(' ')
        val code = if (split > 0) rest.substring(0, split).toIntOrNull() else null
        return code?.let { Sentinel(exitCode = it, cwd = rest.substring(split + 1).trimEnd()) }
    }

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() && name[0].let { it.isLetter() || it == '_' } && name.all { it.isLetterOrDigit() || it == '_' }
}
