package org.indra.android.core.system.handshake

import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrackRunTest {
    private fun resultOf(
        run: CrackRun?,
        status: String = SmartToolBox.STATUS_COMPLETED,
    ): ToolResult =
        ToolResult(
            tool = "handshakes",
            success = run is CrackRun.Accepted || run is CrackRun.Exhausted,
            status = status,
            parsedData = run?.let { mapOf(CrackRun.RESULT_KEY to it) },
        )

    @Test
    fun `the verdict comes off the final emission`() {
        val run = CrackRun.Accepted("hunter2", 12)

        assertEquals(run, CrackRun.from(resultOf(run)))
    }

    @Test
    fun `a live line carries no verdict`() {
        assertNull(CrackRun.from(resultOf(null, SmartToolBox.STATUS_RUNNING)))
        assertNull(CrackRun.from(resultOf(null)))
    }

    @Test
    fun `a found key is exposed and never an error`() {
        val run = CrackRun.Accepted("hunter2", 12)

        assertEquals("hunter2", run.key)
        assertNull(run.error)
        assertTrue(run.summary, run.summary.contains("12"))
    }

    @Test
    fun `exhaustion is a completed run, not an error`() {
        val run = CrackRun.Exhausted(184)

        assertNull(run.error)
        assertNull(run.key)
        assertTrue(run.summary, run.summary.contains("184"))
        assertFalse(run.summary.contains("found", ignoreCase = true))
    }

    @Test
    fun `every failure carries the sentence that names the fix`() {
        val failures =
            listOf(
                CrackRun.NoHandshake("no exchange"),
                CrackRun.NoNetwork("02:00:00:00:00:01"),
                CrackRun.BadCapture("not a capture"),
                CrackRun.Unsupported("cmac"),
                CrackRun.Failed("io"),
            )

        failures.forEach { run ->
            assertNotNull(run.summary, run.error)
            assertTrue(run.summary.isNotBlank())
            assertNull(run.key)
        }
    }

    @Test
    fun `a missing network name names the bssid and the way out`() {
        val run = CrackRun.NoNetwork("02:00:00:00:00:01")

        val error = requireNotNull(run.error)
        assertTrue(error, error.contains("02:00:00:00:00:01"))
        assertTrue(error, error.contains("SSID"))
    }
}
