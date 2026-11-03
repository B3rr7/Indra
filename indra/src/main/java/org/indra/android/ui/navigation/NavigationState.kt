package org.indra.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Indra's navigation state: **one** back stack.
 *
 * The app deliberately does not use Navigation 3's per-tab back stacks. Tabs here are flat
 * (Home / Tools / Settings) and today's behavior is "switching tabs drops the nested screen",
 * which a single stack reproduces exactly. It also avoids the unsupported case of a destination
 * reachable from more than one tab — every plugin screen is opened from both Home and Tools.
 *
 * [backStack] is a [NavBackStack] (a snapshot list), so [NavDisplay] and the bottom bar observe it
 * and persist it across configuration changes and process death.
 */
class NavigationState(
    val startRoute: Screen,
    val backStack: NavBackStack<NavKey>,
) {
    /** The destination currently on screen. Never empty: the stack always keeps [startRoute]. */
    val currentRoute: NavKey
        get() = backStack.lastOrNull() ?: startRoute
}

/**
 * Turns navigation events into state changes (unidirectional data flow).
 *
 * Instead of a `NavController`, screens call [navigate] / [goBack], and tab taps go through
 * [navigateToTab]. [NavDisplay] just renders whatever [NavigationState] holds.
 */
class Navigator(
    private val state: NavigationState,
) {
    /** Pushes [route]. Re-selecting the destination already on screen is a no-op. */
    fun navigate(route: NavKey) {
        if (state.currentRoute == route) return
        state.backStack.add(route)
    }

    /**
     * Pops one destination.
     *
     * Returns `false` when the stack is already at its root; [NavDisplay] only asks for a back
     * navigation while it shows more than one entry, so `false` is the "let the activity exit"
     * answer (the app leaves through Home).
     */
    fun goBack(): Boolean {
        if (state.backStack.size <= 1) return false
        state.backStack.removeLast()
        return true
    }

    /**
     * Bottom-bar semantics, matching the old `NavController` tab handling:
     *
     * - re-selecting the current tab is a no-op (it must not recreate the screen),
     * - a tab that is already in the stack pops back to it (a tool sits above Home, Logs above
     *   Settings),
     * - any other tab replaces everything above Home and is pushed there.
     */
    fun navigateToTab(route: NavKey) {
        if (state.currentRoute == route) return
        val index = state.backStack.indexOf(route)
        if (index >= 0) {
            while (state.backStack.size > index + 1) state.backStack.removeLast()
            return
        }
        while (state.backStack.size > 1) state.backStack.removeLast()
        state.backStack.add(route)
    }
}

/** Creates the navigation state and keeps it across recompositions. */
@Composable
fun rememberNavigationState(startRoute: Screen = Screen.Home): NavigationState {
    val backStack = rememberNavBackStack(startRoute)
    return remember(backStack) { NavigationState(startRoute, backStack) }
}
