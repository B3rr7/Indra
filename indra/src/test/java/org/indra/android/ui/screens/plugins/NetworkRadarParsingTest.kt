package org.indra.android.ui.screens.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRadarParsingTest {

    @Test
    fun parsesHostAddWithMacAndName() {
        val parsed = parseHostLine("HOST_ADD  { mac: 98:48:27:E8:28:60, ip: 10.86.31.119, name:  }")

        assertEquals("HOST_ADD", parsed?.first)
        assertEquals("98:48:27:E8:28:60", parsed?.second?.get("mac"))
        assertEquals("10.86.31.119", parsed?.second?.get("ip"))
    }

    @Test
    fun parsesIpOnlyVariant() {
        val parsed = parseHostLine("HOST_ADD  { ip: 192.168.1.1 }")

        assertEquals("HOST_ADD", parsed?.first)
        assertEquals("192.168.1.1", parsed?.second?.get("ip"))
    }

    @Test
    fun parsesDeleteAction() {
        val parsed = parseHostLine("HOST_DEL { ip: 10.0.0.7 }")

        assertEquals("HOST_DEL", parsed?.first)
        assertEquals("10.0.0.7", parsed?.second?.get("ip"))
    }

    @Test
    fun ignoresUnrelatedOutput() {
        assertNull(parseHostLine("listening on wlan0"))
        assertNull(parseHostLine(""))
        assertNull(parseHostLine("HOST_ADD without braces"))
    }
}
