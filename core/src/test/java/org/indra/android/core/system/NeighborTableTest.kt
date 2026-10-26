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

    @Test
    fun `parseEntries keeps the link layer address`() {
        val output =
            """
            10.161.57.116 dev wlan0 lladdr 34:8A:12:AA:BB:CC REACHABLE
            10.161.57.1 dev wlan0 lladdr 00:11:22:33:44:55 router REACHABLE
            """.trimIndent()
        assertEquals(
            listOf(
                NeighborTable.Entry("10.161.57.116", "34:8A:12:AA:BB:CC"),
                NeighborTable.Entry("10.161.57.1", "00:11:22:33:44:55"),
            ),
            NeighborTable.parseEntries(output),
        )
    }

    @Test
    fun `parseEntries tolerates a missing or malformed lladdr`() {
        val output =
            """
            10.161.57.20 dev wlan0  PERMANENT
            10.161.57.21 dev wlan0 lladdr incomplete REACHABLE
            10.161.57.22 dev wlan0 lladdr 34:8a:12:aa:bb REACHABLE
            """.trimIndent()
        assertEquals(
            listOf(
                NeighborTable.Entry("10.161.57.20", ""),
                NeighborTable.Entry("10.161.57.21", ""),
                NeighborTable.Entry("10.161.57.22", ""),
            ),
            NeighborTable.parseEntries(output),
        )
    }

    @Test
    fun `parseEntries dedupes keeping the first mac`() {
        val output =
            """
            10.161.57.77 dev wlan0 lladdr d0:57:7b:11:22:33 STALE
            10.161.57.77 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            """.trimIndent()
        assertEquals(
            listOf(NeighborTable.Entry("10.161.57.77", "d0:57:7b:11:22:33")),
            NeighborTable.parseEntries(output),
        )
    }

    @Test
    fun `parseEntries applies the exclude set`() {
        val output =
            """
            10.148.72.22 dev wlan0 lladdr 3c:5a:b4:00:11:22 PERMANENT
            10.148.72.119 dev wlan0 lladdr 98:48:27:e8:28:60 DELAY
            """.trimIndent()
        assertEquals(
            listOf(NeighborTable.Entry("10.148.72.119", "98:48:27:e8:28:60")),
            NeighborTable.parseEntries(output, exclude = setOf("10.148.72.22")),
        )
    }

    @Test
    fun `one host seen over ipv4 and ipv6 collapses to the ipv4 entry`() {
        val output =
            """
            fe80::d02e:20ff:febe:8707 dev wlan0 lladdr d2:2e:20:be:87:07 STALE
            10.148.72.108 dev wlan0 lladdr d2:2e:20:be:87:07 REACHABLE
            fe80::1 dev wlan0 lladdr 34:8a:12:aa:bb:cc REACHABLE
            """.trimIndent()
        // The IPv6 line is listed first; the IPv4 form replaces it in place.
        assertEquals(
            listOf(
                NeighborTable.Entry("10.148.72.108", "d2:2e:20:be:87:07"),
                NeighborTable.Entry("fe80::1", "34:8a:12:aa:bb:cc"),
            ),
            NeighborTable.parseEntries(output),
        )
    }

    @Test
    fun `iface validation rejects anything that would split into extra args`() {
        assertTrue(NeighborTable.isValidIface("wlan0"))
        assertTrue(NeighborTable.isValidIface("rmnet_data3"))
        assertTrue(NeighborTable.isValidIface("veth@peer"))
        assertTrue(!NeighborTable.isValidIface(""))
        assertTrue(!NeighborTable.isValidIface("wlan0 dev eth0"))
        assertTrue(!NeighborTable.isValidIface("wlan0;reboot"))
    }
}
