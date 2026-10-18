package org.indra.android.core.system

import org.junit.Assert.*
import org.junit.Test

class NetworkHelperTest {

    @Test
    fun testNetworkStatusDefaults() {
        val status = NetworkStatus()
        assertFalse(status.isConnected)
        assertEquals("", status.networkName)
    }

    @Test
    fun testTargetInfoCreation() {
        val target = TargetInfo(
            address = "192.168.1.1",
            name = "Gateway",
            type = TargetType.ENDPOINT
        )
        assertEquals("192.168.1.1", target.address)
        assertEquals("Gateway", target.name)
    }

    @Test
    fun testPluginCreation() {
        val plugin = Plugin(
            id = "port_scanner",
            name = "Port Scanner",
            description = "Scan open ports",
            targetTypes = setOf("endpoint")
        )
        assertEquals("port_scanner", plugin.id)
        assertTrue(plugin.targetTypes.contains("endpoint"))
    }
}
