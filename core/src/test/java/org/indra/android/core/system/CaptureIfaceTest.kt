package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * masscan's interface problem, as a rule instead of a device discovery.
 *
 * On a phone that is *also* tethering — the normal setup for this app — `ConnectivityManager`
 * calls the cellular device "active", and libpcap can only open that one as a cooked
 * `LINUX_SLL` socket, which masscan refuses:
 *
 * ```
 * [-] if(rmnet_data3): unknown data link type: 113(LINUX_SLL)
 * ```
 *
 * So the Wi-Fi AP interface has to win, and a user-typed name has to win over both.
 */
class CaptureIfaceTest {
    @Test
    fun `wifi, ethernet and usb tethering are usable`() {
        listOf("wlan0", "swlan0", "eth0", "rndis0", "usb0", "ap0").forEach {
            assertTrue(it, CaptureIface.isUsable(it))
        }
    }

    @Test
    fun `cellular and loopback are not`() {
        listOf("rmnet_data3", "rmnet_ipa0", "ccmni0", "clat4", "pdp_ip0", "lo", "dummy0", null).forEach {
            assertFalse("$it", CaptureIface.isUsable(it))
        }
    }

    @Test
    fun `the active interface is kept when libpcap can open it`() {
        assertEquals("wlan0", CaptureIface.pick("wlan0", listOf("wlan0", "rmnet_data3")))
    }

    @Test
    fun `cellular active loses to the wifi ap that is up`() {
        assertEquals(
            "wlan0",
            CaptureIface.pick("rmnet_data3", listOf("lo", "wlan0", "rmnet_data3")),
        )
    }

    @Test
    fun `the first usable up interface wins when several are`() {
        assertEquals("eth0", CaptureIface.pick("rmnet_data3", listOf("eth0", "wlan0")))
    }

    @Test
    fun `a cellular-only phone still names its cellular device`() {
        // Nothing better exists; masscan then prints its own LINUX_SLL error, which is honest.
        assertEquals("rmnet_data3", CaptureIface.pick("rmnet_data3", listOf("lo", "rmnet_data3")))
    }

    @Test
    fun `nothing up and nothing active leaves the choice to masscan`() {
        assertNull(CaptureIface.pick(null, emptyList()))
    }

    @Test
    fun `a typed interface wins and is quoted`() {
        assertEquals(
            "-e 'wlan0' -p 80",
            CaptureIface.masscanArgs("wlan0", "-p 80") { error("detection must not run") },
        )
        // The field runs as root, so a hostile value stays one POSIX word.
        assertEquals(
            "-e 'wlan0; id' ",
            CaptureIface.masscanArgs("wlan0; id", "") { error("detection must not run") },
        )
    }

    @Test
    fun `a free-form -e is left alone`() {
        assertEquals("-e eth0 -p 80", CaptureIface.masscanArgs("", "-e eth0 -p 80") { error("detection must not run") })
        assertEquals("-p 80 -e=eth0", CaptureIface.masscanArgs("", "-p 80 -e=eth0") { error("detection must not run") })
    }

    @Test
    fun `an empty field falls back to the detected interface`() {
        assertEquals("-e 'wlan0' -p 80", CaptureIface.masscanArgs("", "-p 80") { "wlan0" })
        assertEquals("-p 80", CaptureIface.masscanArgs("  ", "-p 80") { null })
    }
}
