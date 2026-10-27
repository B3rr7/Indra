package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overflow split for `HostSuggestions` (WS-5.1).
 *
 * The bug being pinned: 14 screens drew every discovered host in a non-scrollable `Row`, so hosts
 * past the screen width were clipped and unreachable. The split is pure so this can be checked
 * without a device.
 */
class HostSuggestionLayoutTest {
    private fun hosts(n: Int) = (1..n).map { "10.0.0.$it" }

    @Test
    fun `no hosts means no chips`() {
        val layout = HostSuggestionLayout.of(emptyList())
        assertTrue(layout.visible.isEmpty())
        assertTrue(layout.overflow.isEmpty())
    }

    @Test
    fun `fewer hosts than the cap all render as chips`() {
        val layout = HostSuggestionLayout.of(hosts(3))
        assertEquals(hosts(3), layout.visible)
        assertTrue(layout.overflow.isEmpty())
    }

    @Test
    fun `exactly the cap needs no overflow menu`() {
        val layout = HostSuggestionLayout.of(hosts(MAX_VISIBLE_HOST_SUGGESTIONS))
        assertEquals(MAX_VISIBLE_HOST_SUGGESTIONS, layout.visible.size)
        assertTrue(layout.overflow.isEmpty())
    }

    @Test
    fun `one past the cap reserves a slot for the overflow chip`() {
        val layout = HostSuggestionLayout.of(hosts(MAX_VISIBLE_HOST_SUGGESTIONS + 1))
        assertEquals(MAX_VISIBLE_HOST_SUGGESTIONS - 1, layout.visible.size)
        assertEquals(2, layout.overflow.size)
    }

    @Test
    fun `twenty hosts lose nothing`() {
        val all = hosts(20)
        val layout = HostSuggestionLayout.of(all)
        assertEquals(all, layout.visible + layout.overflow)
        assertEquals(MAX_VISIBLE_HOST_SUGGESTIONS - 1, layout.visible.size)
        assertEquals(20 - (MAX_VISIBLE_HOST_SUGGESTIONS - 1), layout.overflow.size)
    }

    @Test
    fun `order is preserved across the split`() {
        val all = hosts(12)
        val layout = HostSuggestionLayout.of(all)
        assertEquals(all.first(), layout.visible.first())
        assertEquals(all.last(), layout.overflow.last())
    }

    @Test
    fun `an explicit cap is honoured`() {
        val layout = HostSuggestionLayout.of(hosts(5), maxVisible = 3)
        assertEquals(2, layout.visible.size)
        assertEquals(3, layout.overflow.size)
    }
}
