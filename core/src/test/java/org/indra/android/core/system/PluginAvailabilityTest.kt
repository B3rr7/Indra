package org.indra.android.core.system

import org.junit.Assert.assertFalse
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
}
