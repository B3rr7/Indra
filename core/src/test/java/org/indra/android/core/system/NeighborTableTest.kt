package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborTableTest {
    @Test
    fun `counts reachable and stale hosts`() {
        val output =
            """
            10.161.57.116 dev wlan0 lladdr 34:8a:12:aa:bb:cc REACHABLE
            10.161.57.77 dev wlan0 lladdr d0:57:7b:11:22:33 STALE
            10.161.57.20 dev wlan0 lladdr 8c:aa:b5:44:55:66 DELAY
            10.161.57.9 dev wlan0  FAILED
            10.161.57.10 dev wlan0  INCOMPLETE
            10.161.57.1 dev wlan0 lladdr 00:11:22:33:44:55 router REACHABLE
            """.trimIndent()
        assertEquals(
            listOf("10.161.57.116", "10.161.57.77", "10.161.57.20", "10.161.57.1"),
            NeighborTable.parse(output),
        )
    }

    @Test
    fun `ignores multicast broadcast noarp and blank lines`() {
        val output =
            """
            224.0.0.251 dev wlan0 lladdr 01:00:5e:00:00:fb NOARP
            255.255.255.255 dev wlan0 lladdr ff:ff:ff:ff:ff:ff NOARP
            ff02::1:2 dev wlan0 lladdr 33:33:00:01:00:02 NOARP
            fe80::1 dev wlan0 lladdr 34:8a:12:aa:bb:cc router REACHABLE

            not-a-neighbour-line
            """.trimIndent()
        assertEquals(listOf("fe80::1"), NeighborTable.parse(output))
    }

    @Test
    fun `excludes our own address and gateway`() {
        val output =
            """
            10.69.195.23 dev wlan0 lladdr 3c:5a:b4:00:11:22 PERMANENT
            10.161.57.116 dev wlan0 lladdr 34:8a:12:aa:bb:cc REACHABLE
            10.161.57.77 dev wlan0 lladdr d0:57:7b:11:22:33 STALE
            """.trimIndent()
        assertEquals(
            listOf("10.161.57.77"),
            NeighborTable.parse(output, exclude = setOf("10.69.195.23", "10.161.57.116")),
        )
    }

    @Test
    fun `duplicate entries collapse`() {
        val output =
            """
            10.161.57.77 dev wlan0 lladdr d0:57:7b:11:22:33 REACHABLE
            10.161.57.77 dev wlan0 lladdr d0:57:7b:11:22:33 STALE
            """.trimIndent()
        assertEquals(listOf("10.161.57.77"), NeighborTable.parse(output))
    }

    @Test
    fun `an empty table is zero hosts, not an error`() {
        assertTrue(NeighborTable.parse("").isEmpty())
        assertTrue(NeighborTable.parse("10.161.57.9 dev wlan0  FAILED").isEmpty())
    }
}
