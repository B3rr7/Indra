package org.indra.android.core.system.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject
import javax.inject.Singleton

/** One open terminal tab: its label and the session behind it. */
data class TerminalTab(
    val id: Int,
    val session: TerminalSession,
)

/** The strip as the UI sees it — every tab, plus which one is on screen. Always non-empty. */
data class TerminalTabs(
    val tabs: List<TerminalTab>,
    val activeId: Int,
) {
    val activeTab: TerminalTab get() = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
}

/**
 * The app's terminal tabs.
 *
 * A singleton with an application lifetime, because that is what a tab is: leaving the Terminal
 * screen (or the app, with the bubble on) must not end the shell a tab is running. Each tab owns a
 * [TerminalSession] with its own scope and job, so switching to another tab never pauses or kills
 * the command running in the first — the same reason the single session outlived the screen before
 * tabs existed.
 *
 * App-wide preferences (font size, the floating bubble and its size, the input mode, the symbol row)
 * deliberately stay in [SettingsRepository] rather than in one tab: a zoom applies to the whole
 * terminal, not to whichever tab happened to be visible. A change is written once and every tab
 * re-reads it through [TerminalSession.refresh], which keeps the two surfaces of a tab (screen and
 * bubble) in agreement no matter which one made the change.
 */
@Singleton
class TerminalSessions
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
        private val settings: SettingsRepository,
    ) {
        private val _state = MutableStateFlow(TerminalTabs(listOf(newTab(FIRST_TAB)), FIRST_TAB))
        val state: StateFlow<TerminalTabs> = _state.asStateFlow()

        /** The session every surface acts on right now — the screen, the bubble and the overlay. */
        val active: TerminalSession get() = _state.value.activeTab.session

        /**
         * Opens a gap-numbered tab and selects it.
         *
         * Refused at the cap rather than silently dropped: the strip's `+` is disabled at
         * [TerminalTabRules.MAX_TABS], so a call arriving here at the cap is already a bug.
         */
        fun open() {
            val current = _state.value
            if (!TerminalTabRules.canOpen(current.tabs.size)) return
            val id = TerminalTabRules.nextId(current.tabs.map { it.id })
            _state.update { it.copy(tabs = it.tabs + newTab(id), activeId = id) }
        }

        fun select(id: Int) {
            _state.update { tabs ->
                if (tabs.tabs.any { it.id == id }) tabs.copy(activeId = id) else tabs
            }
        }

        /**
         * Closes a tab and selects its neighbour (right, then left).
         *
         * The last tab is not closable: a terminal with no tab has no surface at all, so the
         * strip's `×` is hidden while one tab is open and this refuses the same case.
         */
        fun close(id: Int) {
            val current = _state.value
            if (current.tabs.size <= 1) return
            val closing = current.tabs.firstOrNull { it.id == id } ?: return
            val remaining = current.tabs.filterNot { it.id == id }
            val nextActive =
                if (current.activeId == id) {
                    TerminalTabRules.neighbour(current.tabs.map { it.id }, id)
                } else {
                    current.activeId
                }
            _state.update { TerminalTabs(remaining, nextActive) }
            closing.session.dispose()
        }

        /** Re-reads the environment/preferences for every tab (resume, or after a setting change). */
        fun refresh() {
            _state.value.tabs.forEach { it.session.refresh() }
        }

        /**
         * Persists a terminal preference and applies it to every tab.
         *
         * The write goes to [SettingsRepository] and each tab re-reads it, rather than patching
         * every tab's in-memory copy with the same six fields: one owner of the value means the
         * two surfaces of a tab (screen and bubble) cannot drift apart. A zoom therefore lands on
         * the next frame instead of this one, which is imperceptible for a setting.
         */
        fun setPreferences(
            mode: TerminalInputMode? = null,
            symbolKeys: Boolean? = null,
            fontSize: Int? = null,
            bubbleVisible: Boolean? = null,
            panelWidth: Float? = null,
            panelHeight: Float? = null,
        ) {
            mode?.let { settings.terminalInputMode = it.name }
            symbolKeys?.let { settings.terminalSymbolKeys = it }
            fontSize?.let { settings.terminalFontSize = TerminalType.normalize(it) }
            bubbleVisible?.let { settings.terminalBubbleEnabled = it }
            panelWidth?.let { settings.terminalPanelWidth = it }
            panelHeight?.let { settings.terminalPanelHeight = it }
            refresh()
        }

        private fun newTab(id: Int): TerminalTab = TerminalTab(id, TerminalSession(id, toolBox, coreManager, settings))

        private companion object {
            /** Tab labels are the small numbers the strip shows; the first one is 1. */
            const val FIRST_TAB = 1
        }
    }
