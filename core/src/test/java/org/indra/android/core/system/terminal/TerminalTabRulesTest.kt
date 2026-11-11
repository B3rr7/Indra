package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tab arithmetic: the cases that only happen after the user has already opened, closed
 * and re-opened a few tabs, which is exactly when a hand-rolled counter goes wrong.
 */
class TerminalTabRulesTest {
    @Test
    fun `a full strip refuses one more tab`() {
        assertTrue(TerminalTabRules.canOpen(0))
        assertTrue(TerminalTabRules.canOpen(TerminalTabRules.MAX_TABS - 1))
        assertFalse(TerminalTabRules.canOpen(TerminalTabRules.MAX_TABS))
        assertFalse(TerminalTabRules.canOpen(TerminalTabRules.MAX_TABS + 3))
    }

    @Test
    fun `the first tab is number one`() {
        assertEquals(1, TerminalTabRules.nextId(emptyList()))
    }

    @Test
    fun `a new tab counts up while the numbers are contiguous`() {
        assertEquals(4, TerminalTabRules.nextId(listOf(1, 2, 3)))
    }

    @Test
    fun `a closed gap is filled before the number grows`() {
        // Closed tab 2 of three: the next tab is 2 again, not 4.
        assertEquals(2, TerminalTabRules.nextId(listOf(1, 3)))
        assertEquals(2, TerminalTabRules.nextId(listOf(3, 1)))
    }

    @Test
    fun `closing a middle tab selects the one on its right`() {
        assertEquals(3, TerminalTabRules.neighbour(listOf(1, 2, 3, 4), closing = 2))
    }

    @Test
    fun `closing the last tab selects the one on its left`() {
        assertEquals(2, TerminalTabRules.neighbour(listOf(1, 2, 3), closing = 3))
    }

    @Test
    fun `closing the only tab answers itself`() {
        assertEquals(1, TerminalTabRules.neighbour(listOf(1), closing = 1))
    }

    @Test
    fun `an id that is not open falls back to the first tab`() {
        assertEquals(1, TerminalTabRules.neighbour(listOf(1, 2), closing = 9))
        assertEquals(9, TerminalTabRules.neighbour(emptyList(), closing = 9))
    }
}
