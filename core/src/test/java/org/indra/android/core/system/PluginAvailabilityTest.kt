package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure part of the shared plugin-availability rules (the platform-blocker
 * policy). The handler/artifact/rootless branches need a device context and are
 * covered by device verification on the Home and Tools screens.
 */
class PluginAvailabilityTest {
    @Test
    fun responderIsInfeasibleOnAndroid() {
        // Responder is CPython-based and the core ships no interpreter — every card
        // must dim it (Home and Tools used to disagree on this).
        assertFalse(PluginAvailability.isFeasibleOnAndroid("responder"))
    }

    @Test
    fun coreDrivenPluginsAreFeasible() {
        // Wifite deliberately stays feasible: its screen drives the bundled
        // ip/iw/tcpdump/aircrack-ng binaries, not wifite.py.
        listOf(
            "port_scanner",
            "inspector",
            "traceroute",
            "mitm",
            "packet_capture",
            "network_radar",
            "wifite",
            "terminal",
            "hid",
            "bettercap",
        ).forEach { id -> assertTrue("Plugin $id should be feasible", PluginAvailability.isFeasibleOnAndroid(id)) }
    }

    @Test
    fun everyUnavailableStatusExplainsItself() {
        // A dimmed card must never be dimmed silently, and the labels must not collide:
        // "not in native core" for a stopped daemon sent users re-installing a core
        // that was already installed.
        val labels =
            PluginAvailability.PluginStatus.entries
                .filter { it.label != null }
                .map { it.label }
        assertEquals("each blocker needs its own label", labels.size, labels.distinct().size)
        PluginAvailability.PluginStatus.entries
            .filter { it.label != null }
            .forEach { assertTrue("${it.name} needs a non-blank label", !it.label.isNullOrBlank()) }
        assertNull(PluginAvailability.PluginStatus.AVAILABLE.label)
    }

    @Test
    fun openableStatesAreTheFixableOnes() {
        // A card is disabled only for a blocker the user cannot act on from the app. A stopped
        // daemon is fixed from the screen's gate, and a missing optional component is installed
        // by the plugin screen itself — dimming those two made their install/fix paths
        // unreachable.
        val openable = PluginAvailability.PluginStatus.entries.filter { it.openable }
        assertEquals(
            listOf(
                PluginAvailability.PluginStatus.AVAILABLE,
                PluginAvailability.PluginStatus.DAEMON_DOWN,
                PluginAvailability.PluginStatus.ARTIFACT_MISSING,
            ),
            openable,
        )
    }

    @Test
    fun directToolsAreGatedOnRootNotOnADaemonHandler() {
        // Phase 3 moved hydra/hping3/masscan/aircrack-ng off the daemon onto direct `su`.
        // Their registry handler is the "direct" pseudo-handler, which the daemon never
        // reports, so a handler probe could only ever dim them: a device run caught Masscan,
        // Login Cracker, Packet Forger, Credential Tester and WiFi Audit all saying "not in
        // native core" — and disabled, so the reason could not even be read — on a device
        // with the binary in the core and root granted.
        fun status(bundled: Boolean, rooted: Boolean, artifact: Boolean) =
            PluginAvailability.directToolStatus(
                binaryBundled = bundled,
                rooted = rooted,
                artifactInstalled = artifact,
            )
        assertEquals(PluginAvailability.PluginStatus.AVAILABLE, status(true, true, true))
        assertEquals(PluginAvailability.PluginStatus.NEEDS_ROOT, status(true, false, true))
        assertEquals(PluginAvailability.PluginStatus.TOOL_MISSING, status(false, true, true))
        assertEquals(PluginAvailability.PluginStatus.ARTIFACT_MISSING, status(true, true, false))
    }

    @Test
    fun onlyThePhaseThreeToolsAreDirect() {
        // A plugin whose `requires` key is a direct-handler spec is gated on root by
        // directStatus; every other plugin must still go through the daemon probe.
        listOf("masscan", "hydra", "hping3", "aircrack-ng", "reaver", "arpspoof", "tcpdump", "nuclei", "bettercap").forEach {
            assertTrue("$it should be a direct tool", SmartToolBox.isDirectTool(it))
        }
        listOf("nmap", "raw", "shell", "ettercap", "gobuster", "network-radar", "msfrpcd", "fusemounts").forEach {
            assertFalse("$it should not be a direct tool", SmartToolBox.isDirectTool(it))
        }
    }
}
