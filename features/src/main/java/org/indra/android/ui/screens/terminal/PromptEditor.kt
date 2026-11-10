package org.indra.android.ui.screens.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.indra.android.core.system.terminal.TerminalKeys

/** No history entry is selected: the prompt is editing a fresh line. */
internal const val NO_HISTORY = -1

/**
 * The prompt's editing state: the text, the caret, and the history walk it drives.
 *
 * A holder rather than three `remember`s in [TerminalConsole] because every way of editing the
 * line — the IME, the symbol row, the history buttons, a snippet chip — has to leave the caret
 * authoritative, and a BasicTextField's own selection is not readable from outside the field.
 */
internal class PromptEditor {
    var text by mutableStateOf("")
        private set
    var cursor by mutableIntStateOf(0)
        private set
    private var historyIndex by mutableIntStateOf(NO_HISTORY)

    /** A keystroke in the field: the text and the caret the field now holds. */
    fun type(
        text: String,
        cursor: Int,
    ) {
        this.text = text
        this.cursor = cursor
        historyIndex = NO_HISTORY
    }

    /** ▲ ▼: step through the history, clearing the line when stepping past the newest entry. */
    fun recall(
        history: List<String>,
        step: Int,
    ) {
        val (index, line) = recallFrom(history, historyIndex, step)
        historyIndex = index
        text = line
        cursor = line.length
    }

    /** Replaces the whole line — picking a snippet chip. */
    fun pick(command: String) {
        text = command
        cursor = command.length
        historyIndex = NO_HISTORY
    }

    /** A symbol key: inserted at the caret, which ends up after the insert. */
    fun insert(symbol: String) {
        applyEdit(TerminalKeys.insert(text, cursor, cursor, symbol))
    }

    fun moveCursor(delta: Int) {
        cursor = TerminalKeys.moveCursor(text, cursor, delta)
    }

    /** Deletes the character *before* the caret — with a caret mid-line that is not the last one. */
    fun backspace() {
        if (cursor > 0) applyEdit(TerminalKeys.insert(text, cursor - 1, cursor, ""))
    }

    /**
     * Hands the line to [onRun] and clears the prompt. Refused — text kept for later — while a
     * command is still running: the field deliberately stays editable then, so Return has to be
     * the thing that waits rather than the field that closes.
     */
    fun submit(
        running: Boolean,
        onRun: (String) -> Unit,
    ) {
        val command = text.trim()
        if (command.isEmpty() || running) return
        onRun(command)
        text = ""
        cursor = 0
        historyIndex = NO_HISTORY
    }

    private fun applyEdit(edit: TerminalKeys.Edit) {
        text = edit.text
        cursor = edit.cursor
        historyIndex = NO_HISTORY
    }
}

/**
 * Walks the history: `step = -1` is older, `1` is newer. Returns the new cursor index (or
 * [NO_HISTORY]) and the text to put in the field — stepping past the newest entry clears it.
 */
private fun recallFrom(
    history: List<String>,
    from: Int,
    step: Int,
): Pair<Int, String> =
    when {
        history.isEmpty() -> NO_HISTORY to ""
        step > 0 && from < 0 -> NO_HISTORY to ""
        step > 0 -> (from + 1).takeIf { it <= history.lastIndex }?.let { it to history[it] } ?: (NO_HISTORY to "")
        from < 0 -> history.lastIndex to history.last()
        else -> (from - 1).coerceAtLeast(0).let { it to history[it] }
    }
