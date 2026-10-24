package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the SmartToolBox tool registry. The registry used to contain duplicate
 * names (a ConcurrentHashMap keeps the LAST registration), which bound shipped
 * tools to dead handlers and advertised binaries that do not exist.
 */
class ToolRegistryTest {

    @Test
    fun `registry has no duplicate tool names`() {
        val names = SmartToolBox.TOOL_SPECS.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every registry entry binds to a real daemon handler`() {
        val offenders =
            SmartToolBox.TOOL_SPECS.filter { it.handler !in SmartToolBox.DAEMON_HANDLERS }
        assertTrue(
            " Handlers not in the daemon's loadHandlers() set: " +
                offenders.joinToString { "${it.name}->${it.handler}" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `adaemon handler set matches the native core build`() {
        // Update this test together with the native handlers (jni/src/.../handlers)
        // whenever a handler is added or removed from the core package.
        assertEquals(
            setOf(
                "nmap", "hydra", "ettercap", "tcpdump", "arpspoof",
                "fusemounts", "network-radar", "msfrpcd", "raw", "blind",
            ),
            SmartToolBox.DAEMON_HANDLERS,
        )
    }

    @Test
    fun `every plugin requirement resolves to a registered tool`() {
        // Keys used by PluginManager.requires and direct getTool(...) lookups
        // across ViewModels. If a plugin starts requiring a new tool, add it here.
        val gatedKeys =
            setOf(
                "masscan", "nmap", "arpspoof", "network-radar", "tcpdump",
                "shell", "hydra", "hping3", "aircrack-ng", "nuclei",
                "reaver", "bettercap", "raw",
            )
        val registered = SmartToolBox.TOOL_SPECS.map { it.name }.toSet()
        val missing = gatedKeys - registered
        assertTrue("Required tool keys missing from registry: $missing", missing.isEmpty())
    }

    @Test
    fun `shellQuote is POSIX-safe`() {
        assertEquals("'hello'", SmartToolBox.shellQuote("hello"))
        // O'Brien -> 'O'\''Brien' (close quote, escaped quote, reopen)
        assertEquals("'O'\\''Brien'", SmartToolBox.shellQuote("O'Brien"))
        // Shell metacharacters stay inside the quoted string
        val payload = "target; rm -rf /"
        val quoted = SmartToolBox.shellQuote(payload)
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"))
        assertEquals(payload, quoted.substring(1, quoted.length - 1))
    }
}
