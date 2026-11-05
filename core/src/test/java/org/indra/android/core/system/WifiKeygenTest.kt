package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiKeygenTest {

    @Test
    fun `wps checksum completes the classic valid pin`() {
        assertEquals('0', WifiKeygen.wpsChecksum("1234567"))
    }

    @Test
    fun `wps checksum weights even digits by three`() {
        // 1*3 + 1 + 1*3 + 1 + 1*3 + 1 + 1*3 = 15, so (10 - 5) % 10 = 5.
        assertEquals('5', WifiKeygen.wpsChecksum("1111111"))
    }

    @Test
    fun `wps checksum of all zeros is zero`() {
        assertEquals('0', WifiKeygen.wpsChecksum("0000000"))
    }

    @Test
    fun `wps checksum rejects a prefix that is not seven digits`() {
        assertNull(WifiKeygen.wpsChecksum("123456"))
        assertNull(WifiKeygen.wpsChecksum("12345678"))
        assertNull(WifiKeygen.wpsChecksum("12345ab"))
    }

    @Test
    fun `candidates are empty for input that is not a mac`() {
        assertTrue(WifiKeygen.generateCandidates("not-a-mac").isEmpty())
        assertTrue(WifiKeygen.generateCandidates("").isEmpty())
    }

    @Test
    fun `mac candidates carry the eight digit suffix`() {
        val candidates = WifiKeygen.generateCandidates("00:11:22:33:44:55")

        val suffix = candidates.first { it.confidence == WifiKeygen.Confidence.POSSIBLE }
        assertEquals("22334455", suffix.key)
    }

    @Test
    fun `numeric mac tail yields a checksummed wps pin and it ranks first`() {
        val candidates = WifiKeygen.generateCandidates("00:11:22:33:44:55")

        val pin = candidates.first()
        assertEquals(WifiKeygen.Confidence.HIGH, pin.confidence)
        assertEquals("23344556", pin.key)
    }

    @Test
    fun `hex mac tail produces no wps pin because it has no decimal reading`() {
        val candidates = WifiKeygen.generateCandidates("AA:BB:CC:11:22:33")

        assertTrue(candidates.none { it.confidence == WifiKeygen.Confidence.HIGH })
        assertTrue(candidates.none { it.key.contains("null") })
    }

    @Test
    fun `candidates are ordered high then possible then low`() {
        val candidates = WifiKeygen.generateCandidates("00:11:22:33:44:55", "HomeNet")

        assertEquals(
            candidates.sortedBy { it.confidence.ordinal },
            candidates,
        )
    }

    @Test
    fun `essid candidates are only produced when an essid is given`() {
        val withSsid = WifiKeygen.generateCandidates("00:11:22:33:44:55", "HomeNet")
        val withoutSsid = WifiKeygen.generateCandidates("00:11:22:33:44:55")

        assertTrue(withSsid.any { it.key == "HomeNet" })
        assertTrue(withSsid.size > withoutSsid.size)
        assertTrue(withoutSsid.none { it.key == "HomeNet" })
    }

    @Test
    fun `blank essid is treated as absent`() {
        val blank = WifiKeygen.generateCandidates("00:11:22:33:44:55", "   ")
        val absent = WifiKeygen.generateCandidates("00:11:22:33:44:55")

        assertEquals(absent, blank)
    }
}
