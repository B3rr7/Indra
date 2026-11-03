package org.indra.android.core.system.terminal

/**
 * The extra keys a phone keyboard makes painful to reach, and the edit they perform.
 *
 * A shell command is mostly punctuation — `|`, `>`, `&`, `$`, `/` — and every one of those is two
 * or three taps deep on a phone IME. Termux solves this with a dedicated key row above the
 * keyboard; this is the same idea, minus the keys that cannot work here.
 *
 * **What is deliberately absent: CTRL, ALT, ESC and TAB.** They are the first four keys of
 * Termux's row and they would be lies in this app. A modifier key exists to send a control byte to
 * a *running* process, and there is no process to send it to: the daemon `execvp`s argv with no
 * pty and no stdin, and [LocalShell] starts a child the same way. `CTRL+C` could not interrupt a
 * running command — the Stop button does that by killing the pid — and `ESC`/`TAB` would insert
 * bytes that nothing reads. A key that looks like a terminal key and does nothing is worse than a
 * key that is not there.
 *
 * Pure by design (no Android, no Compose) so the insertion rule is pinned by `TerminalKeysTest`.
 */
object TerminalKeys {
    /** One tappable key: [label] is what the user sees, [text] is what lands in the command. */
    data class Symbol(
        val label: String,
        val text: String,
    )

    /**
     * The symbols, ordered by how often a shell command needs them.
     *
     * The order is the whole point of the row: the first six are visible without scrolling on a
     * phone, and they cover redirection, pipes and paths.
     */
    val SYMBOLS: List<Symbol> =
        listOf(
            Symbol("/", "/"),
            Symbol("-", "-"),
            Symbol("|", "|"),
            Symbol("~", "~"),
            Symbol("$", "$"),
            Symbol("&", "&"),
            Symbol("*", "*"),
            Symbol(">", ">"),
            Symbol("<", "<"),
            Symbol(";", ";"),
            Symbol("\"", "\""),
            Symbol("'", "'"),
            Symbol("\\", "\\"),
            Symbol("(", "("),
            Symbol(")", ")"),
            Symbol("[", "["),
            Symbol("]", "]"),
            Symbol("{", "{"),
            Symbol("}", "}"),
            Symbol("=", "="),
            Symbol("+", "+"),
            Symbol("!", "!"),
            Symbol("?", "?"),
            Symbol("@", "@"),
            Symbol("#", "#"),
            Symbol("%", "%"),
            Symbol("^", "^"),
            Symbol(",", ","),
            Symbol(".", "."),
            Symbol(":", ":"),
            Symbol("_", "_"),
        )

    /** The command text after an edit, and where the cursor belongs in it. */
    data class Edit(
        val text: String,
        val cursor: Int,
    )

    /**
     * Inserts [insert] at the cursor, replacing the selection when there is one.
     *
     * Inserting *at the cursor* rather than appending is what makes the key row usable: a user who
     * typed `ls -la /data` and wants `ls -la /data/` taps `/` where they are, not at the end of a
     * line they would then have to re-navigate. Both offsets are clamped, because a stale
     * selection from before an edit can point past the end of the text.
     */
    fun insert(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        insert: String,
    ): Edit {
        val from = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val to = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        return Edit(
            text = text.substring(0, from) + insert + text.substring(to),
            cursor = from + insert.length,
        )
    }

    /**
     * Moves the cursor by [delta], clamped to the text.
     *
     * A phone IME usually has no arrow keys, so a command that needs one character changed means
     * tapping the right spot in a 12sp monospace line. This is the arrow key.
     */
    fun moveCursor(
        text: String,
        cursor: Int,
        delta: Int,
    ): Int = (cursor + delta).coerceIn(0, text.length)
}

/**
 * How the command field behaves when the IME comes up.
 *
 * The old field was a single-line `BasicTextField` with `ImeAction.Send`. That is the right shape
 * for a chat box and the wrong one for a shell: the IME shows a Send key where a terminal wants
 * Return, autocorrect rewrites `-la` into a word, and capitalisation turns `ls` into `Ls`. A shell
 * is case-sensitive and full of tokens no dictionary knows, so the default has to be the mode that
 * stops the keyboard from "helping".
 *
 * - [SHELL] — no autocorrect, no suggestions, no capitalisation, monospace, Return submits.
 * - [PLAIN] — the same, but the IME's own Return key inserts a newline instead of submitting, for
 *   a user who wants to type a multi-line script.
 * - [SYSTEM] — the platform default, for a user who wants their IME's word suggestions back.
 *
 * Pure by design so the mapping to `KeyboardOptions` is testable without Compose.
 */
enum class TerminalInputMode(
    val label: String,
    val description: String,
) {
    SHELL(
        label = "Shell",
        description = "No autocorrect or suggestions — Return runs the command",
    ),
    PLAIN(
        label = "Plain",
        description = "No autocorrect — Return adds a newline, the ▶ button runs it",
    ),
    SYSTEM(
        label = "System",
        description = "Your keyboard's own behaviour, with suggestions",
    ),
    ;

    /** Whether the IME should offer autocorrect, suggestions and capitalisation. */
    val autoCorrect: Boolean get() = this == SYSTEM

    /** Whether the IME's Return key submits the command. */
    val returnSubmits: Boolean get() = this == SHELL

    companion object {
        /** The mode a fresh install starts in: a shell, so the keyboard keeps its hands off. */
        val DEFAULT: TerminalInputMode = SHELL

        /** Parses a stored preference, falling back to [DEFAULT] for anything unknown. */
        fun fromStored(value: String?): TerminalInputMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
