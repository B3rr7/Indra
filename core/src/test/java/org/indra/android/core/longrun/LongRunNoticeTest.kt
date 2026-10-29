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
    fun `the text says why the notification cannot be dismissed`() {
        assertTrue(LongRunNotice.TEXT.contains("screen is off"))
        assertTrue(LongRunNotice.TEXT.contains("Stop"))
    }
}
