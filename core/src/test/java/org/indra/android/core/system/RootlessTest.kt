package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessTest {
    @Test
    fun `the daemon-free implementations are supported`() {
        assertTrue(Rootless.supports("port_scanner"))
        assertTrue(Rootless.supports("wifi_scanner"))
        assertTrue(Rootless.supports("whisperpair"))
        assertTrue(Rootless.supports("password_auditor"))
        // The Terminal runs `/system/bin/sh` as the app uid (LocalShell). The Alpine chroot
        // inside it stays root-only — that is a transport question, not a plugin one.
        assertTrue(Rootless.supports("terminal"))
    }

    @Test
    fun `everything that needs a bundled binary is not`() {
        // Each of these runs a core-dir binary through the daemon, which needs root.
        assertFalse(Rootless.supports("masscan"))
        assertFalse(Rootless.supports("login_cracker"))
        assertFalse(Rootless.supports("mitm"))
        assertFalse(Rootless.supports("packet_capture"))
        assertFalse(Rootless.supports(""))
        assertFalse(Rootless.supports("unknown"))
    }

    @Test
    fun `every supported id is a real registered plugin`() {
        // A renamed or dropped plugin would silently leave the rootless subset: the card would
        // say "needs root" for something that works, with no compile error to catch it.
        val registered = PluginManager().allPluginIds()
        Rootless.SUPPORTED_PLUGINS.forEach { id ->
            assertTrue("Rootless lists '$id', which is not a registered plugin", id in registered)
        }
    }

    @Test
    fun `the subset is small and deliberate`() {
        // Rootless is a promise about what works without root. Growing it silently is how it
        // became a broken promise once already, so a new entry has to be a conscious edit here.
        assertEquals(
            setOf("port_scanner", "wifi_scanner", "whisperpair", "password_auditor", "terminal"),
            Rootless.SUPPORTED_PLUGINS,
        )
    }
}
