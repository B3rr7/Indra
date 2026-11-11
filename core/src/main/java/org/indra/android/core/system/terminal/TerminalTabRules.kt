package org.indra.android.core.system.terminal

/**
 * The arithmetic behind the terminal's tabs, kept pure so the awkward cases — a full strip, a
 * closed tab that was the middle one, an id made reusable — are unit-tested instead of found on
 * a device.
 *
 * Tabs are numbered, not named: the id is also the pill's label, so the rules have to keep the
 * numbers small and gap-free. Closing tab 2 of three therefore leaves 1 and 3, and the next new
 * tab reuses 2 rather than becoming 4 — which is why [nextId] takes the ids still in use.
 */
object TerminalTabRules {
    /**
     * The strip is a single scrolling row under the top bar; past six pills it stops being a strip
     * and becomes a hunt. Six also keeps the launched sessions (each with its own coroutine scope)
     * comfortably bounded.
     */
    const val MAX_TABS = 6

    fun canOpen(openTabs: Int): Boolean = openTabs < MAX_TABS

    /**
     * The smallest positive number not currently used. Filling the gap rather than counting up
     * forever is what keeps the labels at `1..6` however many times tabs are opened and closed.
     */
    fun nextId(used: Collection<Int>): Int = (1..MAX_TABS).firstOrNull { it !in used } ?: (used.maxOrNull() ?: 0) + 1

    /**
     * Which tab to show after [closing] disappears: the one to its right, otherwise the one to its
     * left (a browser closes left-to-right the same way). A lone tab answers itself — the caller
     * refuses to close it, but returning a valid id keeps this total.
     */
    fun neighbour(
        openIds: List<Int>,
        closing: Int,
    ): Int {
        val index = openIds.indexOf(closing)
        if (index < 0) return openIds.firstOrNull() ?: closing
        return openIds.getOrNull(index + 1) ?: openIds.getOrNull(index - 1) ?: closing
    }
}
