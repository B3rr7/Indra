package org.indra.android.core.system

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAddressTest {
    @Test
    fun `random mac has six octets`() {
        repeat(200) {
            val mac = MacAddress.random()
            assertEquals("wrong octet count in $mac", MacAddress.OCTET_COUNT, mac.split(":").size)
            assertTrue("not a valid mac: $mac", MacAddress.isValid(mac))
        }
    }

    @Test
    fun `random mac is locally administered and unicast`() {
        repeat(200) {
            val mac = MacAddress.random()
            val first = mac.substring(0, 2).toInt(16)
            assertEquals("multicast bit set in $mac", 0, first and 0x01)
            assertEquals("locally administered bit clear in $mac", 0x02, first and 0x02)
            assertTrue("second digit not in 2/6/a/e: $mac", mac[1].lowercaseChar() in setOf('2', '6', 'a', 'e'))
        }
    }

    @Test
    fun `random mac honours the supplied source`() {
        // Deterministic source: the same seed must give the same address, and two
        // different seeds must not collapse to one.
        assertEquals(MacAddress.random(Random(7)), MacAddress.random(Random(7)))
        assertTrue(MacAddress.random(Random(7)) != MacAddress.random(Random(8)))
    }

    @Test
    fun `validate rejects malformed addresses`() {
        assertTrue(MacAddress.isValid("aa:bb:cc:dd:ee:ff"))
        assertTrue(MacAddress.isValid("AA:BB:CC:DD:EE:FF"))
        // The bug this pins: 256 colon separated pairs, and the old "aa2bb:..." splice.
        assertFalse(MacAddress.isValid(List(256) { "aa" }.joinToString(":")))
        assertFalse(MacAddress.isValid("aa2bb:cc:dd:ee:ff"))
        assertFalse(MacAddress.isValid("aa:bb:cc:dd:ee"))
        assertFalse(MacAddress.isValid("aa:bb:cc:dd:ee:ff:00"))
        assertFalse(MacAddress.isValid("aa-bb-cc-dd-ee-ff"))
        assertFalse(MacAddress.isValid("zz:bb:cc:dd:ee:ff"))
        assertFalse(MacAddress.isValid(""))
    }
}
