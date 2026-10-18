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
    }

    @Test
    fun testToolResultJsonSerialization() {
        val result = ToolResult(
            tool = "nmap",
            success = true,
            status = "completed",
            pid = 123,
            rawOutput = "Port 80 open"
        )
        val json = result.toJson()
        assertNotNull(json)
        assertTrue(json.contains("nmap"))
        assertTrue(json.contains("Port 80 open"))

        val parsed = ToolResult.fromJson(json)
        assertNotNull(parsed)
        assertEquals("nmap", parsed?.tool)
        assertEquals(123, parsed?.pid)
    }

    @Test
    fun testToolResultJsonDeserialization() {
        val json = """{"tool":"hydra","success":false,"error":"not found"}"""
        val result = ToolResult.fromJson(json)
        assertNotNull(result)
        assertEquals("hydra", result?.tool)
        assertFalse(result?.success ?: true)
        assertEquals("not found", result?.error)
    }

    @Test
    fun testToolResultJsonInvalid() {
        val result = ToolResult.fromJson("invalid json")
        assertNull(result)
    }
}
