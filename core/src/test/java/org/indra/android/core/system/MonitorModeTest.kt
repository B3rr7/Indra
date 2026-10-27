package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ip`/`iw` mode switch behind Packet Capture's monitor toggle and Wifite's first stage.
 *
 * The regression this pins: `down && iw … && up` leaves the interface **down** when `iw` is
 * missing (it is missing — the core does not ship it), which on `wlan0` means Wi-Fi is off the
 * air with no way back from inside the app.
 */
class MonitorModeTest {
    private val monitor = MonitorMode.script("wlan0", monitor = true)

    @Test
    fun `the link is brought back up whatever iw does`() {
        assertTrue(monitor.contains("; ip link set 'wlan0' up;"))
    }

    @Test
    fun `no and-and chain can strand the interface`() {
        assertTrue("'&&' stops before the restore step", "&&" !in monitor)
    }

    @Test
    fun `the exit status is iw's, so a missing binary still fails`() {
        assertTrue(monitor.endsWith("exit \$status"))
    }

    @Test
    fun `monitor and managed ask for different types`() {
        assertTrue(monitor.contains("set type monitor"))
        assertTrue(MonitorMode.script("wlan0", monitor = false).contains("set type managed"))
    }

    @Test
    fun `the interface is shell quoted`() {
        assertTrue(MonitorMode.script("a b", monitor = true).contains("'a b'"))
    }

    @Test
    fun `a missing iw is explained`() {
        val error = "shell exited with code 127: /system/bin/sh: iw: inaccessible or not found"
        assertEquals(MonitorMode.MISSING_IW_MESSAGE, MonitorMode.explainFailure(error, null))
    }

    @Test
    fun `the explanation also matches the short not-found form`() {
        assertEquals(
            MonitorMode.MISSING_IW_MESSAGE,
            MonitorMode.explainFailure(null, "sh: iw: not found"),
        )
    }

    @Test
    fun `a real interface error is not dressed up as a missing binary`() {
        assertNull(MonitorMode.explainFailure("shell exited with code 1: Cannot find device \"nosuchif0\"", null))
        assertNull(MonitorMode.explainFailure(null, null))
    }
}
