package org.indra.android.core.system

/** How many host chips are shown before the rest move behind a "+N more" menu. */
const val MAX_VISIBLE_HOST_SUGGESTIONS = 8

/**
 * Splits the discovered hosts into the chips to render and the ones behind the overflow menu.
 *
 * Fourteen screens used to draw every host as an `AssistChip` in a non-scrollable `Row`, so with
 * more than a few hosts the chips ran off the right edge and were unreachable. Pure so the split
 * can be unit-tested without a device (`HostSuggestionLayoutTest`).
 *
 * The overflow chip itself takes one slot, so [MAX_VISIBLE_HOST_SUGGESTIONS] hosts render as
 * `maxVisible - 1` chips plus a "+1 more" menu rather than 8 chips and a ninth hidden.
 */
data class HostSuggestionLayout(
    val visible: List<String>,
    val overflow: List<String>,
) {
    companion object {
        fun of(
            hosts: List<String>,
            maxVisible: Int = MAX_VISIBLE_HOST_SUGGESTIONS,
        ): HostSuggestionLayout {
            if (hosts.size <= maxVisible) return HostSuggestionLayout(hosts, emptyList())
            val visible = hosts.take(maxVisible - 1)
            return HostSuggestionLayout(visible, hosts.drop(visible.size))
        }
    }
}
