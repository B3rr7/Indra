package org.indra.android.core.system

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PortScanTest {
    @Test
    fun `parses a comma list`() {
        assertEquals(listOf(22, 80, 443), PortScan.parseSpec("22,80,443"))
    }

    @Test
    fun `parses ranges and mixed specs, sorted and de-duplicated`() {
        assertEquals(listOf(1, 2, 3, 4, 5), PortScan.parseSpec("1-5"))
        assertEquals(listOf(1, 2, 3, 8080), PortScan.parseSpec("1-3,8080"))
        assertEquals(listOf(22, 80, 81, 82), PortScan.parseSpec(" 80-82 , 22 , 80 "))
        assertEquals(listOf(80), PortScan.parseSpec("80,80-80"))
    }

    @Test
    fun `rejects invalid specs instead of guessing`() {
        assertNull(PortScan.parseSpec(""))
        assertNull(PortScan.parseSpec(" , "))
        assertNull(PortScan.parseSpec("abc"))
        assertNull(PortScan.parseSpec("0"))
        assertNull(PortScan.parseSpec("65536"))
        assertNull(PortScan.parseSpec("10-"))
        assertNull(PortScan.parseSpec("-10"))
        assertNull(PortScan.parseSpec("20-10"))
        assertNull(PortScan.parseSpec("80,abc"))
    }

    @Test
    fun `keeps the boundary ports`() {
        assertEquals(listOf(1, 65535), PortScan.parseSpec("1,65535"))
    }

    @Test
    fun `returns only open ports, in port order`() =
        runBlocking {
            val open = PortScan.run(listOf(10, 2, 8, 5, 4), probe = { port -> port % 2 == 0 })
            assertEquals(listOf(2, 4, 8, 10), open)
        }

    @Test
    fun `reports each open port as it is found`() =
        runBlocking {
            val seen = mutableListOf<Int>()
            PortScan.run(
                ports = listOf(1, 2, 3, 4),
                probe = { it == 2 || it == 4 },
                onOpen = { seen += it },
            )
            assertEquals(listOf(2, 4), seen.sorted())
        }

    @Test
    fun `never exceeds the concurrency limit`() =
        runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            PortScan.run(
                ports = (1..64).toList(),
                concurrency = 4,
                probe = {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { max -> maxOf(max, now) }
                    delay(2)
                    inFlight.decrementAndGet()
                    false
                },
            )
            assertTrue("peak was ${peak.get()}", peak.get() <= 4)
        }

    @Test
    fun `an empty port list is zero open ports, not an error`() =
        runBlocking {
            assertTrue(PortScan.run(emptyList(), probe = { true }).isEmpty())
        }
}
