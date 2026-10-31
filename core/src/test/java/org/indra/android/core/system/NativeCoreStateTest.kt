package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `each state offers a different action`() {
        assertEquals(3, allStates.map { it.action }.toSet().size)
    }

    @Test
    fun `each state carries its own button and status copy`() {
        assertEquals(3, allStates.map { it.buttonLabel }.toSet().size)
        assertEquals(3, allStates.map { it.statusLabel }.toSet().size)
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
