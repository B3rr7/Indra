package org.indra.android.core.system

import org.junit.Assert.*
import org.junit.Test

class PluginManagerTest {
    @Test
    fun testPluginCreation() {
        val plugin =
            Plugin(
                id = "test_plugin",
                name = "Test Plugin",
                description = "A test plugin",
                targetTypes = setOf("endpoint", "network"),
            )
        assertEquals("test_plugin", plugin.id)
        assertEquals("Test Plugin", plugin.name)
        assertTrue(plugin.targetTypes.contains("endpoint"))
        assertTrue(plugin.targetTypes.contains("network"))
        assertFalse(plugin.targetTypes.contains("service"))
    }

    @Test
    fun testPluginManagerRegistration() {
        val manager = PluginManager()
        val allPlugins = manager.getAllPlugins()
        assertTrue(allPlugins.isNotEmpty())
        assertTrue(allPlugins.size >= 20) // At least 20 plugins registered (after expansion)
    }

    @Test
    fun testPluginLookup() {
        val manager = PluginManager()
        val plugin = manager.getPlugin("port_scanner")
        assertNotNull(plugin)
        assertEquals("Port Scanner", plugin?.name)
    }

    @Test
    fun testPluginsForTarget() {
        val manager = PluginManager()
        val endpointPlugins = manager.getPluginsForTarget("endpoint")
        assertTrue(endpointPlugins.isNotEmpty())
        assertTrue(endpointPlugins.all { it.targetTypes.contains("endpoint") })
    }

    @Test
    fun testNewPluginsRegistered() {
        val manager = PluginManager()
        for (id in listOf(
            "masscan",
            "reaver",
            "responder",
            "wifite",
            "terminal",
            "mac_changer",
            "iptables",
            "wifi_scanner",
            "handshakes",
        )) {
            assertNotNull("Plugin $id should be registered", manager.getPlugin(id))
        }
    }
}
