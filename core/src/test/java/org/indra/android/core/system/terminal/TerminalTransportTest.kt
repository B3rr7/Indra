package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which shell a typed terminal command is handed to.
 *
 * The bug being pinned: the Terminal gated on the daemon's `shell` handler unconditionally, so
 * rootless mode had no terminal at all — even though an app may start `/system/bin/sh` for itself.
 * The decision is pure, so every configuration is checkable without a device.
 */
class TerminalTransportTest {
    @Test
    fun `a rooted device uses the daemon`() {
        assertEquals(TerminalTransport.DAEMON, TerminalTransport.of(rootless = false, chroot = false))
    }

    @Test
    fun `a rooted device with chroot on enters the guest`() {
        assertEquals(TerminalTransport.CHROOT, TerminalTransport.of(rootless = false, chroot = true))
    }

    @Test
    fun `rootless mode runs the app's own shell`() {
        assertEquals(TerminalTransport.LOCAL, TerminalTransport.of(rootless = true, chroot = false))
    }

    @Test
    fun `rootless plus chroot is impossible, not silently downgraded`() {
        // Entering Alpine needs CAP_SYS_ADMIN. Falling back to LOCAL here would run the command
        // outside the guest the user asked for, against a different filesystem and different
        // binaries — a wrong answer that looks like a working one.
        assertNull(TerminalTransport.of(rootless = true, chroot = true))
    }

    @Test
    fun `only the local transport is daemon-free`() {
        // Every other transport must still gate on the daemon, or the gate would let a command
        // through to a handler that cannot run it.
        assertEquals(TerminalTransport.LOCAL, TerminalTransport.of(rootless = true, chroot = false))
        listOf(
            TerminalTransport.of(rootless = false, chroot = false),
            TerminalTransport.of(rootless = false, chroot = true),
        ).forEach { transport ->
            assertEquals(false, transport == TerminalTransport.LOCAL)
        }
    }
}
