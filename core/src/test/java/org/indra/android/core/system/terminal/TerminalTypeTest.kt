package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript's zoom: what the rocker may do, and what a line looks like afterwards.
 *
 * The bug being pinned in advance: a zoom control that clamps at one end but not the other, or a
 * floating panel that follows the screen's size down past legibility. Both are one-line bugs whose
 * only visible symptom is a terminal that cannot be read.
 */
class TerminalTypeTest {
    @Test
    fun `the rocker steps by two points`() {
        assertEquals(14, TerminalType.step(TerminalType.DEFAULT, 1))
        assertEquals(10, TerminalType.step(TerminalType.DEFAULT, -1))
    }

    @Test
    fun `zooming in stops at the maximum`() {
        assertEquals(TerminalType.MAX, TerminalType.step(TerminalType.MAX, 1))
        assertEquals(TerminalType.MAX, TerminalType.step(TerminalType.MAX - 1, 1))
        assertFalse(TerminalType.canZoomIn(TerminalType.MAX))
        assertTrue(TerminalType.canZoomIn(TerminalType.MAX - 1))
    }

    @Test
    fun `zooming out stops at the minimum`() {
        assertEquals(TerminalType.MIN, TerminalType.step(TerminalType.MIN, -1))
        assertEquals(TerminalType.MIN, TerminalType.step(TerminalType.MIN + 1, -1))
        assertFalse(TerminalType.canZoomOut(TerminalType.MIN))
        assertTrue(TerminalType.canZoomOut(TerminalType.MIN + 1))
    }

    @Test
    fun `a size from outside the range is normalised before it is used`() {
        // A prefs file can hold anything a previous build wrote; the console must not draw 2sp.
        assertEquals(TerminalType.MIN, TerminalType.normalize(2))
        assertEquals(TerminalType.MAX, TerminalType.normalize(96))
        assertEquals(TerminalType.DEFAULT, TerminalType.normalize(TerminalType.DEFAULT))
    }

    @Test
    fun `the floating panel draws two points smaller but never below its floor`() {
        assertEquals(10, TerminalType.fontFor(12, compact = true))
        assertEquals(12, TerminalType.fontFor(12, compact = false))
        assertEquals(TerminalType.COMPACT_MIN, TerminalType.fontFor(TerminalType.MIN, compact = true))
    }

    @Test
    fun `leading keeps its ratio at every size`() {
        // 12sp/17sp is the pair the console shipped with, so the default must not move.
        assertEquals(17, TerminalType.lineHeight(12))
        // And a bigger font must not end up with tighter leading than a smaller one.
        val sizes = (TerminalType.MIN..TerminalType.MAX).map { TerminalType.lineHeight(it) }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(TerminalType.lineHeight(20) > TerminalType.lineHeight(10))
    }
}
