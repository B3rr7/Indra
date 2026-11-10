package org.indra.android.core.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NetworkRepository` keeps the hosts a scan found so every plugin can offer them as chips. That
 * list must not survive a network change — a host from the café's Wi-Fi is not a valid target on
 * the office LAN — but only a **known** difference is a change: mobile data has no SSID and a
 * denied location permission hides it too, so treating "unknown" as "different" would throw away
 * real results on every refresh.
 */
class NetworkChangedTest {
    @Test
    fun `same ssid and gateway is the same network`() {
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = "Office",
                previousGateway = "10.0.0.1",
                nextSsid = "Office",
                nextGateway = "10.0.0.1",
            ),
        )
    }

    @Test
    fun `a different ssid is a different network`() {
        assertFalse(
            NetworkChanged.isSameNetwork(
                previousSsid = "Cafe",
                previousGateway = "10.0.0.1",
                nextSsid = "Office",
                nextGateway = "10.0.0.1",
            ),
        )
    }

    @Test
    fun `a different gateway is a different network even with the same ssid`() {
        // Two hotels can both broadcast "Guest" — the gateway is what actually tells them apart.
        assertFalse(
            NetworkChanged.isSameNetwork(
                previousSsid = "Guest",
                previousGateway = "10.0.0.1",
                nextSsid = "Guest",
                nextGateway = "192.168.1.1",
            ),
        )
    }

    @Test
    fun `an unknown ssid on either side never declares a change`() {
        // Mobile data, or Android 9+ with location denied: the SSID is blank and there is nothing
        // to compare, so the hosts must survive the refresh.
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = null,
                previousGateway = "10.0.0.1",
                nextSsid = "Office",
                nextGateway = "10.0.0.1",
            ),
        )
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = "Office",
                previousGateway = "10.0.0.1",
                nextSsid = "   ",
                nextGateway = "10.0.0.1",
            ),
        )
    }

    @Test
    fun `an unknown gateway on either side never declares a change`() {
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = "Office",
                previousGateway = "",
                nextSsid = "Office",
                nextGateway = "10.0.0.1",
            ),
        )
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = "Office",
                previousGateway = "10.0.0.1",
                nextSsid = "Office",
                nextGateway = null,
            ),
        )
    }

    @Test
    fun `whitespace around a known value is not a change`() {
        assertTrue(
            NetworkChanged.isSameNetwork(
                previousSsid = " Office ",
                previousGateway = " 10.0.0.1 ",
                nextSsid = "Office",
                nextGateway = "10.0.0.1",
            ),
        )
    }
}
