package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    @Test
    fun `press report is an 8 byte boot keyboard report`() {
        val report = HidReports.pressReport(HidReports.MOD_SHIFT, 0x0B)
        assertEquals(8, report.size)
        assertEquals(0x02.toByte(), report[0])
        assertEquals(0x00.toByte(), report[1])
        assertEquals(0x0B.toByte(), report[2])
        assertEquals(0, report.drop(3).count { it != 0.toByte() })
        assertEquals(List(8) { 0 }, HidReports.releaseReport().map { it.toInt() })
    }

    @Test
    fun `letters are mapped to usage page 07 with shift only for uppercase`() {
        assertEquals(listOf(0x02 to 0x0B, 0x00 to 0x0C), HidReports.keystrokes("Hi"))
        assertEquals(listOf(0x00 to 0x04), HidReports.keystrokes("a"))
    }

    @Test
    fun `digits and shifted symbols share a key`() {
        assertEquals(listOf(0 to 0x1E, HidReports.MOD_SHIFT to 0x1E), HidReports.keystrokes("1!"))
        assertEquals(listOf(0 to 0x27), HidReports.keystrokes("0"))
    }

    @Test
    fun `characters the boot keyboard cannot produce are skipped`() {
        assertEquals(emptyList<Pair<Int, Int>>(), HidReports.keystrokes("é✓"))
    }

    @Test
    fun `write command uses octal escapes instead of printf hex`() {
        val cmd = HidReports.writeCommand(HidReports.pressReport(HidReports.MOD_SHIFT, 0x0B))
        assertEquals("printf '\\0002\\0000\\0013\\0000\\0000\\0000\\0000\\0000' > /dev/hidg0", cmd)
        // Hex escapes are not portable to Android's printf.
        assertFalse(cmd.contains("\\x"))
        assertEquals("printf '\\0377' > /dev/hidg0", HidReports.writeCommand(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `typing script presses and releases every character`() {
        val script = HidReports.typingScript("ab")
        assertEquals(4, script.split("printf").size - 1)
        assertEquals(4, script.split(" > /dev/hidg0").size - 1)
        assertTrue(script.startsWith("printf '\\0000\\0000\\0004"))
        assertTrue(script.contains("; printf '\\0000\\0000\\0000\\0000\\0000\\0000\\0000\\0000' > /dev/hidg0"))
    }

    @Test
    fun `empty text produces an empty script`() {
        assertEquals("", HidReports.typingScript(""))
    }
}
