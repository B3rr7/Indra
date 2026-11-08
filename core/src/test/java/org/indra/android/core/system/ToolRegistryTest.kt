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
    fun `every registry entry binds to a real daemon handler or a direct handler`() {
        val offenders =
            SmartToolBox.TOOL_SPECS.filter { it.handler !in SmartToolBox.DAEMON_HANDLERS && it.handler !in SmartToolBox.DIRECT_HANDLERS }
        assertTrue(
            " Handlers not in the daemon's loadHandlers() set or direct handlers: " +
                offenders.joinToString { "${it.name}->${it.handler}" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `daemon handler set matches the native core build`() {
        // Update this test together with the native handlers (jni/src/.../handlers)
        // whenever a handler is added or removed from the core package.
        // hydra moved to direct su in Phase 3.
        assertEquals(
            setOf(
                "nmap", "ettercap", "tcpdump", "arpspoof",
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
                "reaver", "bettercap", "gobuster", "raw",
            )
        val registered = SmartToolBox.TOOL_SPECS.map { it.name }.toSet()
        val missing = gatedKeys - registered
        assertTrue("Required tool keys missing from registry: $missing", missing.isEmpty())
    }

    @Test
    fun `every direct gate names a registry entry`() {
        // The screens that run a tool through `su` gate on a *registry* name. Traceroute gated
        // on "traceroute", which is not a shipped binary (it is `nmap --traceroute`) and showed
        // "'traceroute' is not available — install or update the native core" on device.
        val gates = listOf("hydra", "nmap", "masscan", "hping3", "aircrack-ng")
        val registered = SmartToolBox.TOOL_SPECS.map { it.name }.toSet()
        val unknown = gates - registered
        assertTrue("Direct gates with no registry entry: $unknown", unknown.isEmpty())
    }

    @Test
    fun `direct tools resolve through the bundled tools path`() {
        // A direct `su` run starts in `/` with the system PATH, so a bare binary name is only
        // found when its own `tools/<name>` directory is on PATH. On device, Phase 3 handed
        // `hydra`/`hping3`/`masscan`/`nmap`/`aircrack-ng` to `su -c` by bare name and got
        // "/system/bin/sh: hydra: inaccessible or not found" with the binary in the core.
        val dirs = SmartToolBox.directToolDirs(java.io.File("/core"))
        listOf("hydra", "nmap", "aircrack-ng", "hping3", "masscan").forEach { binary ->
            assertTrue("$binary missing from the direct PATH: $dirs", "/core/tools/$binary" in dirs)
        }
        assertEquals("direct PATH must not repeat a directory: $dirs", dirs.size, dirs.toSet().size)
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
