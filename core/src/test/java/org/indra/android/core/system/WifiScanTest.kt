package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing for the WiFi Scanner's scan output.
 *
 * The screen used to run `iw dev <iface> scan`, and `iw` ships in neither the native core nor the
 * ROM, so every scan died with `exit 127`. It now reads Android's own
 * `cmd wifi list-scan-results`, whose real output (captured on the moto g60, Android 16) is the
 * fixture below. The header row must be ignored and the SSID column must survive spaces.
 */
class WifiScanTest {
    private val realOutput =
        """
        |    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
        |  06:6d:a9:76:96:83       2457    -53(0:-53)            9.257    OPPO Reno14 5G i8p5               [WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]
        |  ce:4d:ff:92:05:38       2412    -84(0:-84)           10.025    1+                                [ESS]
        """.trimMargin()

    @Test
    fun `parses the real scan table`() {
        val networks = WifiScan.parse(realOutput)
        assertEquals(2, networks.size)
        assertEquals("OPPO Reno14 5G i8p5", networks[0].ssid)
        assertEquals("06:6d:a9:76:96:83", networks[0].bssid)
        assertEquals(2457, networks[0].frequencyMhz)
        assertEquals(-53, networks[0].rssiDbm)
        assertEquals("WPA2/WPA3", networks[0].security)
    }

    @Test
    fun `the header row is not a network`() {
        val onlyHeader = realOutput.lines().first()
        assertTrue(WifiScan.parse(onlyHeader).isEmpty())
    }

    @Test
    fun `an open network is labelled open`() {
        assertEquals("Open", WifiScan.parse(realOutput)[1].security)
    }

    @Test
    fun `strongest signal comes first`() {
        val networks = WifiScan.parse(realOutput)
        assertTrue(networks[0].rssiDbm > networks[1].rssiDbm)
    }

    @Test
    fun `ssids with spaces survive`() {
        assertEquals("OPPO Reno14 5G i8p5", WifiScan.parse(realOutput)[0].ssid)
    }

    @Test
    fun `an ssid is never swallowed by the flag list`() {
        val networks = WifiScan.parse(realOutput)
        assertTrue(networks.none { it.ssid.contains("[") })
    }

    @Test
    fun `security labels`() {
        assertEquals("WPA2/WPA3", WifiScan.securityOf("[WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]"))
        assertEquals("WPA3", WifiScan.securityOf("[RSN-SAE-CCMP-128][ESS]"))
        assertEquals("WPA2", WifiScan.securityOf("[WPA2-PSK-CCMP-128][ESS]"))
        assertEquals("WPA", WifiScan.securityOf("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WEP", WifiScan.securityOf("[WEP][ESS]"))
        assertEquals("OWE", WifiScan.securityOf("[RSN-OWE-CCMP-128][ESS]"))
        assertEquals("Open", WifiScan.securityOf("[ESS]"))
        assertEquals("Unknown", WifiScan.securityOf(""))
    }

    @Test
    fun `channels are derived from frequency`() {
        assertEquals(1, WifiScan.channelOf(2412))
        assertEquals(6, WifiScan.channelOf(2437))
        assertEquals(10, WifiScan.channelOf(2457))
        assertEquals(14, WifiScan.channelOf(2484))
        assertEquals(36, WifiScan.channelOf(5180))
        assertEquals(1, WifiScan.channelOf(5955))
        assertEquals(0, WifiScan.channelOf(0))
    }

    @Test
    fun `bands are derived from frequency`() {
        assertEquals("2.4 GHz", WifiScan.bandOf(2457))
        assertEquals("5 GHz", WifiScan.bandOf(5180))
        assertEquals("6 GHz", WifiScan.bandOf(5955))
    }

    @Test
    fun `an empty scan is an empty list`() {
        assertTrue(WifiScan.parse("").isEmpty())
        assertTrue(WifiScan.parse("Wifi is disabled").isEmpty())
    }

    @Test
    fun `describe keeps the name on its own line`() {
        val text = WifiScan.parse(realOutput)[0].describe()
        assertEquals(2, text.lines().size)
        assertEquals("OPPO Reno14 5G i8p5", text.lines()[0])
        assertTrue(text.lines()[1].contains("ch10"))
        assertTrue(text.lines()[1].contains("-53dBm"))
        assertTrue(text.lines()[1].contains("WPA2/WPA3"))
    }

    @Test
    fun `a hidden ssid is named rather than left blank`() {
        val hidden = WifiScan.parse("  06:6d:a9:76:96:83  2457  -53(0:-53)  1.0  [WPA2-PSK-CCMP-128][ESS]")
        assertEquals(1, hidden.size)
        assertEquals("", hidden[0].ssid)
        assertTrue(hidden[0].describe().startsWith(HIDDEN_SSID))
    }
}
