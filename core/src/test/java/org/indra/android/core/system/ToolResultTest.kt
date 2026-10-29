package org.indra.android.core.system

import org.junit.Assert.*
import org.junit.Test

class ToolResultTest {
    @Test
    fun testToolResultDefaults() {
        val result = ToolResult(tool = "nmap", success = true)
        assertEquals("nmap", result.tool)
        assertTrue(result.success)
        assertEquals("", result.status)
        assertEquals(-1, result.pid)
        assertEquals("", result.rawOutput)
        assertNull(result.error)
        assertNull(result.exitValue)
    }

    @Test
    fun testOnlyRunningEmissionsStream() {
        val running = ToolResult(tool = "nmap", success = true, status = SmartToolBox.STATUS_RUNNING)
        assertTrue(running.isStreaming)
        assertEquals("53/tcp open domain", running.copy(rawOutput = "53/tcp open domain").streamedLine)

        listOf(
            SmartToolBox.STATUS_COMPLETED,
            SmartToolBox.STATUS_TIMEOUT,
            SmartToolBox.STATUS_FAILED,
            "",
        ).forEach { status ->
            val final = ToolResult(tool = "nmap", success = false, status = status, rawOutput = "53/tcp open domain")
            assertFalse("status=$status must not stream", final.isStreaming)
            assertNull("status=$status must not append a line", final.streamedLine)
        }
    }

    @Test
    fun testRunningLineSuppressesTheExecutingEcho() {
        val echo = ToolResult(tool = "shell", success = true, status = SmartToolBox.STATUS_RUNNING, rawOutput = "Executing: id")
        assertNull(echo.streamedLine)
        val blank = ToolResult(tool = "shell", success = true, status = SmartToolBox.STATUS_RUNNING, rawOutput = " ")
        assertNull(blank.streamedLine)
    }
}
