package org.indra.android.core.system.terminal

/**
 * The command lines a tab answers itself instead of handing to a shell.
 *
 * There is no persistent shell process (see [TerminalScript]): every command is a fresh
 * `sh -c`, so `exit` used to end *that throwaway process* and nothing else — the tab stayed
 * open, the next command ran in a new shell, and the only visible effect was a missing status
 * line. The tab is the closest thing to a session this terminal has, so `exit`/`logout` end
 * **the tab** instead.
 *
 * A numeric argument is accepted because `exit 0` is what a shell line looks like; anything
 * else after the word (`exit now`, `exit; id`) is refused here so it still reaches the shell
 * and reports its own error rather than silently closing a tab.
 */
object TerminalExit {
    private val NAMES = setOf("exit", "logout")

    /** Splits `exit   0` on any run of whitespace; the second part is the optional status. */
    private val SPACE = Regex("\\s+")

    fun isExit(command: String): Boolean {
        val parts = command.trim().trimEnd(';').trim().split(SPACE, limit = 2)
        if (parts.first() !in NAMES) return false
        return parts.size == 1 || parts[1].trim().toIntOrNull() != null
    }
}
