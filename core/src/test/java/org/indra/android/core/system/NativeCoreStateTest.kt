package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-button Native Core lifecycle (Settings → Native Core).
 *
 * The bug being pinned: the card drew Install + Start/Stop from two independent booleans, so it
 * could offer "Start" with no core on disk and "Install" beside a running daemon. The derivation
 * is pure, so every combination can be checked without a device.
 */
class NativeCoreStateTest {
    private val allStates =
        listOf(
            NativeCoreState.NotInstalled,
            NativeCoreState.InstalledStopped,
            NativeCoreState.Running,
            NativeCoreState.Rootless,
        )

    @Test
    fun `nothing extracted can only be installed`() {
        val state = NativeCoreState.of(installed = false, running = false)
        assertEquals(NativeCoreState.NotInstalled, state)
        assertEquals(NativeCoreAction.INSTALL, state.action)
        assertEquals("Install Core", state.buttonLabel)
    }

    @Test
    fun `an installed core that is not running starts`() {
        val state = NativeCoreState.of(installed = true, running = false)
        assertEquals(NativeCoreState.InstalledStopped, state)
        assertEquals(NativeCoreAction.START, state.action)
        assertEquals("Start Daemon", state.buttonLabel)
    }

    @Test
    fun `a listening core stops`() {
        val state = NativeCoreState.of(installed = true, running = true)
        assertEquals(NativeCoreState.Running, state)
        assertEquals(NativeCoreAction.STOP, state.action)
        assertEquals("Stop Daemon", state.buttonLabel)
    }

    @Test
    fun `a socket without a core is still not installed`() {
        // A hand-pushed or half-extracted daemon: re-installing is the only step that puts the
        // app and the daemon on the same build, so "Stop" must not be offered here.
        assertEquals(NativeCoreState.NotInstalled, NativeCoreState.of(installed = false, running = true))
    }

    @Test
    fun `rootless mode never offers a daemon start`() {
        // The bug being pinned: with rootless mode on, the card still offered "Start Daemon"
        // for an installed core, and every tap ran an exec that SELinux kills with exit 126.
        // Rootless outranks both probes — there is no daemon in that mode, running or stopped.
        listOf(
            NativeCoreState.of(installed = true, running = false, rootless = true),
            NativeCoreState.of(installed = true, running = true, rootless = true),
            NativeCoreState.of(installed = false, running = false, rootless = true),
        ).forEach { state ->
            assertEquals(NativeCoreState.Rootless, state)
            assertNull(state.action)
        }
    }

    @Test
    fun `rootless offers no step at all, because the subset reads no core files`() {
        // The four rootless plugins read app assets and platform APIs, never the extracted
        // core, so "Install Core" would be a button that changes nothing. No action, no button.
        val state = NativeCoreState.of(installed = false, running = false, rootless = true)
        assertNull(state.action)
        assertEquals("", state.buttonLabel)
        assertEquals("Rootless", state.statusLabel)
    }

    @Test
    fun `rootless is not the default`() {
        // The new parameter must not change the rooted derivation.
        assertEquals(NativeCoreState.InstalledStopped, NativeCoreState.of(installed = true, running = false))
        assertEquals(NativeCoreState.Running, NativeCoreState.of(installed = true, running = true))
    }

    @Test
    fun `every state that offers an action offers a different one`() {
        // Rootless carries no action, so it is excluded from the distinctness check.
        val actionable = allStates.mapNotNull { it.action }
        assertEquals(3, actionable.toSet().size)
        assertEquals(3, actionable.size)
    }

    @Test
    fun `each state carries its own button and status copy`() {
        // Rootless has no button label (no button), so only the status copy is compared across
        // all four; the three actionable labels must still be distinct from each other.
        assertEquals(4, allStates.map { it.statusLabel }.toSet().size)
        val labels = allStates.filter { it.action != null }.map { it.buttonLabel }
        assertEquals(3, labels.toSet().size)
        labels.forEach { assertTrue("a button label must not be blank", it.isNotBlank()) }
    }

    @Test
    fun `busy copy names the step and never reads like a state`() {
        val busy = NativeCoreAction.entries.map { it.busyLabel }
        assertEquals(3, busy.toSet().size)
        busy.forEach { assertTrue(it.isNotBlank()) }
        NativeCoreAction.entries.zip(allStates).forEach { (action, state) ->
            assertNotEquals("busy copy must differ from the button label", state.buttonLabel, action.busyLabel)
        }
    }
}
