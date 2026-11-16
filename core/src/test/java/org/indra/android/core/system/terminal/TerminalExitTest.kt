package org.indra.android.core.system.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which lines end a tab.
 *
 * The distinction is the whole point: `exit` and `exit 0` are a shell user leaving, while
 * `exit now` and `exit; id` are something else and must reach the shell so it reports its own
 * error — a rule that silently swallowed the second group would close a tab because of a typo.
 */
class TerminalExitTest {
    @Test
    fun `exit and logout end the tab`() {
        assertTrue(TerminalExit.isExit("exit"))
        assertTrue(TerminalExit.isExit("logout"))
    }

    @Test
    fun `surrounding whitespace and a trailing semicolon do not stop it`() {
        assertTrue(TerminalExit.isExit("  exit  "))
        assertTrue(TerminalExit.isExit("exit;"))
        assertTrue(TerminalExit.isExit("\texit\t"))
    }

    @Test
    fun `an exit status is accepted`() {
        // `exit 0` is what a shell line looks like; there is no parent process to report it to.
        assertTrue(TerminalExit.isExit("exit 0"))
        assertTrue(TerminalExit.isExit("exit 3"))
        assertTrue(TerminalExit.isExit("exit    1"))
        assertTrue(TerminalExit.isExit("logout 0"))
        // Numeric, so the shell would swallow it (and print no status line) if it were handed on.
        assertTrue(TerminalExit.isExit("exit -1"))
    }

    @Test
    fun `a non-numeric argument reaches the shell`() {
        assertFalse(TerminalExit.isExit("exit now"))
        assertFalse(TerminalExit.isExit("logout all"))
        assertFalse(TerminalExit.isExit("exit 1 2"))
    }

    @Test
    fun `a compound line reaches the shell`() {
        assertFalse(TerminalExit.isExit("exit; id"))
        assertFalse(TerminalExit.isExit("exit 0; echo hi"))
        assertFalse(TerminalExit.isExit("exit && id"))
    }

    @Test
    fun `look-alikes are ordinary commands`() {
        assertFalse(TerminalExit.isExit("exitcode"))
        assertFalse(TerminalExit.isExit("echo exit"))
        assertFalse(TerminalExit.isExit("EXIT"))
        assertFalse(TerminalExit.isExit("export exit=1"))
        assertFalse(TerminalExit.isExit(""))
    }
}
