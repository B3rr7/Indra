package org.indra.android.core.system

import org.junit.Assert.*
import org.junit.Test

class RootManagerTest {
    @Test
    fun testProcessResultDefaults() {
        val result = ProcessResult()
        assertEquals(-1, result.exitCode)
        assertEquals(-1, result.pid)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
        assertEquals("", result.error)
        assertEquals("", result.output)
    }

    @Test
    fun testProcessResultCustom() {
        val result =
            ProcessResult(
                exitCode = 0,
                pid = 123,
                stdout = "output",
                stderr = "error",
                error = "err",
                output = "out",
            )
        assertEquals(0, result.exitCode)
        assertEquals(123, result.pid)
        assertEquals("output", result.stdout)
    }

    @Test
    fun testRootStatusData() {
        val status =
            RootManager.RootStatus(
                isRooted = true,
                rootType = RootManager.RootType.MAGISK,
                suPath = "/system/bin/su",
                magiskVersion = "26.4",
                kernelSuVersion = null,
            )
        assertTrue(status.isRooted)
        assertEquals(RootManager.RootType.MAGISK, status.rootType)
        assertEquals("26.4", status.magiskVersion)
    }

    /**
     * `suAccess`, `managerPackage` and the capability flags were added for the Magisk 31
     * rework; older call sites (and this test) only pass the original five fields, so the
     * derived default must stay consistent with `isRooted`.
     */
    @Test
    fun testRootStatusDerivesAccessFromIsRooted() {
        val granted =
            RootManager.RootStatus(
                isRooted = true,
                rootType = RootManager.RootType.MAGISK,
                suPath = "/system/bin/su",
                magiskVersion = null,
                kernelSuVersion = null,
            )
        assertEquals(RootAccess.SuAccess.GRANTED, granted.suAccess)
        assertNull("capabilities are unknown until probed", granted.hasNetRaw)
        assertNull(granted.managerPackage)

        val denied =
            RootManager.RootStatus(
                isRooted = false,
                rootType = RootManager.RootType.NONE,
                suPath = null,
                magiskVersion = null,
                kernelSuVersion = null,
            )
        assertEquals(RootAccess.SuAccess.NO_BINARY, denied.suAccess)
    }
}
