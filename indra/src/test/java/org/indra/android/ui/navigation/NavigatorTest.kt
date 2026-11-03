package org.indra.android.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tab/back semantics that used to live in `NavController` calls.
 *
 * Navigation 3 has no controller with `popUpTo`/`launchSingleTop`, so the behavior is hand-written
 * in [Navigator] — these tests are what keeps a refactor from silently changing what a tab tap
 * does (the old `navigateToTab` dropped nested screens; returning to a tab must not resurrect a
 * tool that was open on it).
 */
class NavigatorTest {
    private fun state(vararg routes: Screen): NavigationState =
        NavigationState(Screen.Home, NavBackStack<NavKey>(*routes))

    private fun NavigationState.routes(): List<NavKey> = backStack.toList()

    @Test
    fun `navigate pushes a destination`() {
        val state = state(Screen.Home)
        Navigator(state).navigate(Screen.PortScanner)
        assertEquals(listOf(Screen.Home, Screen.PortScanner), state.routes())
    }

    @Test
    fun `navigate to the current destination is a no-op`() {
        val state = state(Screen.Home, Screen.PortScanner)
        Navigator(state).navigate(Screen.PortScanner)
        assertEquals(listOf(Screen.Home, Screen.PortScanner), state.routes())
    }

    @Test
    fun `goBack pops the top destination`() {
        val state = state(Screen.Home, Screen.PortScanner)
        val navigator = Navigator(state)

        assertTrue(navigator.goBack())
        assertEquals(listOf<NavKey>(Screen.Home), state.routes())
    }

    @Test
    fun `goBack at the root asks the activity to exit`() {
        val state = state(Screen.Home)

        assertFalse(Navigator(state).goBack())
        assertEquals(listOf<NavKey>(Screen.Home), state.routes())
    }

    @Test
    fun `tab re-selection keeps the screen`() {
        val state = state(Screen.Home, Screen.Tools, Screen.PortScanner)
        Navigator(state).navigateToTab(Screen.Tools)
        assertEquals(listOf(Screen.Home, Screen.Tools), state.routes())
    }

    @Test
    fun `tab re-selection of the current tab is a no-op`() {
        val state = state(Screen.Home, Screen.Tools)
        Navigator(state).navigateToTab(Screen.Tools)
        assertEquals(listOf(Screen.Home, Screen.Tools), state.routes())
    }

    @Test
    fun `a new tab replaces everything above home`() {
        val state = state(Screen.Home, Screen.Tools, Screen.PortScanner)
        Navigator(state).navigateToTab(Screen.Settings)
        assertEquals(listOf(Screen.Home, Screen.Settings), state.routes())
    }

    @Test
    fun `tapping home from a nested screen returns to home only`() {
        val state = state(Screen.Home, Screen.Settings, Screen.Logs)
        Navigator(state).navigateToTab(Screen.Home)
        assertEquals(listOf<NavKey>(Screen.Home), state.routes())
    }

    @Test
    fun `plugin ids resolve to their destinations`() {
        assertEquals(Screen.GadgetProfiles, pluginDestination("usb_arsenal"))
        assertEquals(Screen.PasswordAuditor, pluginDestination("password_auditor"))
        assertEquals(null, pluginDestination("not_a_plugin"))
    }
}
