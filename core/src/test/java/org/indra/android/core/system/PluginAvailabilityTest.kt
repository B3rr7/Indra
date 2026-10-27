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
            "topology",
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
    fun onlyTheDaemonDownStateStaysOpenable() {
        // Every other blocker is a dead end, so the card stays disabled; a stopped daemon
        // is fixable from the screen's own gate message.
        val openable = PluginAvailability.PluginStatus.entries.filter { it.openable }
        assertEquals(listOf(PluginAvailability.PluginStatus.AVAILABLE, PluginAvailability.PluginStatus.DAEMON_DOWN), openable)
    }
}
