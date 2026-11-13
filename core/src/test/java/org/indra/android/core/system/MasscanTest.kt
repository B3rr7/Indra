package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript below is masscan's real output on the moto g60 while it was the hotspot: the
 * `-e wlan0` fix got past the LINUX_SLL error, and the scan then failed on a gateway that does
 * not exist in AP mode. Its own hints name `eth0`, which no Android phone has.
 */
class MasscanTest {
    private val arpFailure =
        """
        [+] resolving router 0.0.0.0 with ARP (may take some time)...
        [-] FAIL: ARP timed-out resolving MAC address for router wlan0: "0.0.0.0"
            [hint] try "--router-ip 192.0.2.1" to specify different router
            [hint] try "--router-mac 66-55-44-33-22-11" instead to bypass ARP
            [hint] try "--interface eth0" to change interface
        """.trimIndent()

    @Test
    fun `a missing gateway is named instead of masscan's desktop hints`() {
        val message = Masscan.explain(arpFailure)

        assertEquals(
            "masscan could not ARP a gateway on the scanning interface, so it sent no packets: " +
                "this phone has no default gateway there (hotspot/AP mode has none — the phone is the " +
                "router). Run masscan while the phone is a client on a Wi-Fi network that has a gateway.",
            message,
        )
    }

    @Test
    fun `a scan that only found nothing is not a failure`() {
        assertNull(Masscan.explain("[+] resolving router 10.0.0.1 with ARP (may take some time)..."))
        assertNull(Masscan.explain(""))
    }

    @Test
    fun `an unrelated failure keeps its own reason`() {
        assertNull(Masscan.explain("[-] if(rmnet_data3): unknown data link type: 113(LINUX_SLL)"))
    }

    @Test
    fun `progress redraws are recognised and result lines are not`() {
        // Device output: the redraw is one line per `\r`, and 200 of them evicted the finding.
        listOf(
            "rate:  0.00-kpps,  0.00% done,4294967295:15:15 remaining, found=0",
            "rate:  0.00-kpps, 100.00% done, waiting 9-secs, found=1",
            "  rate:  0.00-kpps, 100.00% done, waiting -3-secs, found=1",
        ).forEach { assertTrue(it, Masscan.isProgress(it)) }

        listOf(
            "Starting masscan 1.3.9-integration (http://bit.ly/14GZzcT) at 2026-11-12 12:23:10 GMT",
            "Initiating SYN Stealth Scan",
            "Scanning 1 hosts [1 port/host]",
            "Discovered open port 8080/tcp on 10.252.250.120",
            "[-] Passed the wait window but still running, forcing exit...",
        ).forEach { assertFalse(it, Masscan.isProgress(it)) }
    }
}
