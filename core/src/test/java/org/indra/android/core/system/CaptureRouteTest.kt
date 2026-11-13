package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame destination masscan needs on Android, as a rule instead of a device discovery.
 *
 * Device-verified on the moto g60 (a Wi-Fi client, gateway `10.252.250.212`): with only `-e`
 * masscan prints `resolving router 0.0.0.0 with ARP (may take some time)...` and sends nothing;
 * with the *gateway's* MAC it reports `0 found` for a host whose port 8080 answers TCP; with the
 * **target's** MAC it reports `Discovered open port 8080/tcp`.
 */
class CaptureRouteTest {
    private val laptop = "10.252.250.120"
    private val gateway = "10.252.250.212"
    private val laptopMac = "98:48:27:e8:28:60"
    private val gatewayMac = "ea:f6:bf:01:13:51"

    private val table = mapOf(laptop to laptopMac, gateway to gatewayMac)

    @Test
    fun `a single host the kernel knows is its own next hop`() {
        val hop = CaptureRoute.hop(laptop, gateway) { table[it] }

        assertEquals(CaptureRoute.Hop(laptop, laptopMac), hop)
        assertEquals("--router-mac '$laptopMac' ", CaptureRoute.flags(hop))
    }

    @Test
    fun `a known gateway still wins for a range`() {
        val hop = CaptureRoute.hop("10.252.250.0/24", gateway) { table[it] }

        assertEquals(CaptureRoute.Hop(gateway, gatewayMac), hop)
        assertEquals("--router-mac '$gatewayMac' ", CaptureRoute.flags(hop))
    }

    @Test
    fun `an unresolved host falls back to the gateway, named by address`() {
        val hop = CaptureRoute.hop("10.252.250.99", gateway) { table[it] }

        assertEquals(CaptureRoute.Hop(gateway, gatewayMac), hop)
        // Nothing known about the target, so the gateway is still the hop — with its MAC.
        assertEquals("--router-mac '$gatewayMac' ", CaptureRoute.flags(hop))
    }

    @Test
    fun `an unresolved table falls back to the gateway, named by address`() {
        val hop = CaptureRoute.hop(laptop, gateway) { null }

        // Neither the target nor the gateway has a MAC, so the gateway is named by address and
        // masscan ARPs it itself — instead of the `0.0.0.0` it cannot resolve on Android.
        assertEquals(CaptureRoute.Hop(gateway, null), hop)
        assertEquals("--router-ip '$gateway' ", CaptureRoute.flags(hop))
    }

    @Test
    fun `nothing known and no gateway leaves the decision to masscan`() {
        assertNull(CaptureRoute.hop(laptop, null) { null })
        assertNull(CaptureRoute.hop("10.0.0.0/24", "  ") { null })
        assertEquals("", CaptureRoute.flags(null))
    }

    @Test
    fun `only a plain ipv4 literal can be addressed by one MAC`() {
        listOf(laptop, " 192.168.1.1 ").forEach { assertTrue(it, CaptureRoute.isSingleHost(it)) }
        listOf("10.252.250.0/24", "10.0.0.1-10.0.0.9", "10.0.0.1,10.0.0.2", "", "example.com", "::1").forEach {
            assertFalse(it, CaptureRoute.isSingleHost(it))
        }
    }

    @Test
    fun `free-form router flags are not overridden`() {
        assertTrue(CaptureRoute.namesNextHop("--router-mac aa:bb:cc:dd:ee:ff"))
        assertTrue(CaptureRoute.namesNextHop("-p 80 --router-ip=1.1.1.1"))
        assertFalse(CaptureRoute.namesNextHop("-p 80 --rate=1000"))
        // `-e` names the interface, not the hop.
        assertFalse(CaptureRoute.namesNextHop("-e eth0"))
    }

    @Test
    fun `a single host the table has not resolved is warmed first`() {
        // Reached only when the target's entry aged out: the hop fell back to the gateway, and
        // that hop reported `0 found` for a host whose port answers (device-verified). One
        // echo request makes the kernel resolve it, so the target's own MAC can be used.
        assertTrue(CaptureRoute.needsWarm(CaptureRoute.Hop(gateway, gatewayMac), laptop))
        assertTrue(CaptureRoute.needsWarm(null, laptop))
        // Already the target: nothing to warm.
        assertFalse(CaptureRoute.needsWarm(CaptureRoute.Hop(laptop, laptopMac), laptop))
        // A range cannot be addressed by one MAC, so its hop is never about the target.
        assertFalse(CaptureRoute.needsWarm(CaptureRoute.Hop(gateway, gatewayMac), "10.252.250.0/24"))
    }

    @Test
    fun `the neighbour probe follows the scan interface`() {
        // The table is per interface; a MAC learned on wlan0 is not an address on dummy0, and
        // masscan sends to it out of the interface it was given. Device-verified: a dummy0 run
        // was handed the laptop's wlan0 MAC.
        assertEquals("ip neigh show dev 'wlan0'", CaptureRoute.neighCommand("wlan0"))
        assertEquals("ip neigh show dev 'eth0'", CaptureRoute.neighCommand("eth0"))

        // No usable name: the whole table is the only probe left, and `ip` must not be handed
        // extra arguments (its usage text would be read as a table).
        assertEquals("ip neigh show", CaptureRoute.neighCommand(null))
        assertEquals("ip neigh show", CaptureRoute.neighCommand("  "))
        assertEquals("ip neigh show", CaptureRoute.neighCommand("wl an0"))
    }
}
