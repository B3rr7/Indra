package org.indra.android.core.longrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things a screen-off scan can silently get wrong: holding a foreground service with
 * nothing running (a permanent, undismissable notification), and reporting the wrong count.
 */
class LongRunNoticeTest {
    @Test
    fun `nothing running means no service`() {
        assertFalse(LongRunNotice.isActive(0))
        assertTrue(LongRunNotice.isActive(1))
        assertTrue(LongRunNotice.isActive(5))
    }

    @Test
    fun `one tool is singular`() {
        assertEquals("1 tool running", LongRunNotice.title(1))
    }

    @Test
    fun `several tools are counted`() {
        assertEquals("3 tools running", LongRunNotice.title(3))
        assertEquals("12 tools running", LongRunNotice.title(12))
    }

    @Test
    fun `the starting state does not invent a count`() {
        assertEquals("Starting tool…", LongRunNotice.TITLE_STARTING)
        assertFalse(LongRunNotice.TITLE_STARTING.contains("0 tools"))
    }

    @Test
    fun `a short command is not worth a foreground service`() {
        // Everything the app runs is a tracked child, including Home's ~50 ms `ip neigh`
        // probe; promoting on the first track() crashed the process on every cold start.
        assertTrue("promotion must wait longer than a startup probe", LongRunController.PROMOTE_AFTER_MS >= 1_000L)
        assertTrue("must still catch a real scan", LongRunController.PROMOTE_AFTER_MS <= 10_000L)
    }

    @Test
    fun `the text says why the notification cannot be dismissed`() {
        assertTrue(LongRunNotice.TEXT.contains("screen is off"))
        assertTrue(LongRunNotice.TEXT.contains("Stop"))
    }
}
