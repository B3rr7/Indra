package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Termux-style key row and the IME mode.
 *
 * The bug being pinned: the command field was a single-line `BasicTextField` with
 * `ImeAction.Send` — a chat box. A phone IME then autocorrected `-la` into a dictionary word and
 * capitalised `ls`, and every symbol a shell needs (`|`, `>`, `$`) was two or three taps deep.
 */
class TerminalKeysTest {
    @Test
    fun `a symbol lands at the cursor, not at the end`() {
        // Typed `ls -la data`, caret between `data` and the end: tapping `/` must give
        // `ls -la data/` with the caret after the slash, not `ls -la data/` appended blindly.
        val edit = TerminalKeys.insert(text = "ls -la data", selectionStart = 11, selectionEnd = 11, insert = "/")
        assertEquals("ls -la data/", edit.text)
        assertEquals(12, edit.cursor)
    }

    @Test
    fun `a symbol in the middle splits the text`() {
        val edit = TerminalKeys.insert(text = "ls data", selectionStart = 3, selectionEnd = 3, insert = "-")
        assertEquals("ls -data", edit.text)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun `a selection is replaced, not appended to`() {
        // `cat file` with `file` selected, tapping `>` should not leave the old text behind.
        val edit = TerminalKeys.insert(text = "cat file", selectionStart = 4, selectionEnd = 8, insert = ">")
        assertEquals("cat >", edit.text)
        assertEquals(5, edit.cursor)
    }

    @Test
    fun `a backwards selection is handled`() {
        // Selecting right-to-left gives start > end; both must be normalised.
        val edit = TerminalKeys.insert(text = "abcdef", selectionStart = 4, selectionEnd = 1, insert = "X")
        assertEquals("aXef", edit.text)
        assertEquals(2, edit.cursor)
    }

    @Test
    fun `a stale selection past the end is clamped`() {
        // The caret can outlive the text it pointed into (history recall, a clear).
        val edit = TerminalKeys.insert(text = "ls", selectionStart = 99, selectionEnd = 99, insert = "-l")
        assertEquals("ls-l", edit.text)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun `an empty insert deletes the selection`() {
        // This is what the backspace key does when there is a selection.
        val edit = TerminalKeys.insert(text = "ls -la", selectionStart = 2, selectionEnd = 6, insert = "")
        assertEquals("ls", edit.text)
        assertEquals(2, edit.cursor)
    }

    @Test
    fun `backspace deletes before the cursor, not the last character`() {
        // `ls -la` with the caret after `-l` (index 5). This mirrors what the key row does:
        // insert("", cursor - 1, cursor). It must give `ls -a`, not `ls -l`.
        val edit = TerminalKeys.insert(text = "ls -la", selectionStart = 4, selectionEnd = 5, insert = "")
        assertEquals("ls -a", edit.text)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun `backspace at the start of the line does nothing`() {
        // The key row guards this, but the pure function must not corrupt the text either way.
        val edit = TerminalKeys.insert(text = "ls", selectionStart = 0, selectionEnd = 0, insert = "")
        assertEquals("ls", edit.text)
        assertEquals(0, edit.cursor)
    }

    @Test
    fun `the cursor cannot leave the text`() {
        assertEquals(0, TerminalKeys.moveCursor("abc", cursor = 0, delta = -1))
        assertEquals(3, TerminalKeys.moveCursor("abc", cursor = 3, delta = 1))
        assertEquals(2, TerminalKeys.moveCursor("abc", cursor = 1, delta = 1))
        assertEquals(0, TerminalKeys.moveCursor("", cursor = 0, delta = -1))
    }

    @Test
    fun `the symbols cover what a shell actually needs`() {
        val labels = TerminalKeys.SYMBOLS.map { it.label }.toSet()
        // Redirection, pipes, substitution, paths and quoting: without these the row is decoration.
        listOf("/", "-", "|", ">", "<", "&", "$", "~", "*", ";", "\"", "'", "\\", "=").forEach {
            assertTrue("the key row is missing '$it'", it in labels)
        }
    }

    @Test
    fun `no symbol is listed twice`() {
        val labels = TerminalKeys.SYMBOLS.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `every symbol inserts exactly its label`() {
        // A key whose label and insertion disagree is the worst kind: it looks right and types
        // something else.
        TerminalKeys.SYMBOLS.forEach { symbol ->
            assertEquals(symbol.label, symbol.text)
            assertTrue("'${symbol.label}' must not be blank", symbol.text.isNotBlank())
        }
    }

    @Test
    fun `the shell mode stops the keyboard helping`() {
        val shell = TerminalInputMode.SHELL
        assertFalse("a shell must not autocorrect", shell.autoCorrect)
        assertTrue("Return must run the command", shell.returnSubmits)
    }

    @Test
    fun `the system mode hands control back to the IME`() {
        assertTrue(TerminalInputMode.SYSTEM.autoCorrect)
        assertFalse(TerminalInputMode.SYSTEM.returnSubmits)
    }

    @Test
    fun `plain keeps the keyboard quiet but Return does not submit`() {
        assertFalse(TerminalInputMode.PLAIN.autoCorrect)
        assertFalse(TerminalInputMode.PLAIN.returnSubmits)
    }

    @Test
    fun `a fresh install starts in shell mode`() {
        assertEquals(TerminalInputMode.SHELL, TerminalInputMode.DEFAULT)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        // The preference is a string key, so a downgrade or a hand-edited prefs file can hold
        // anything. It must not crash the terminal.
        assertEquals(TerminalInputMode.DEFAULT, TerminalInputMode.fromStored(null))
        assertEquals(TerminalInputMode.DEFAULT, TerminalInputMode.fromStored(""))
        assertEquals(TerminalInputMode.DEFAULT, TerminalInputMode.fromStored("NONSENSE"))
        assertEquals(TerminalInputMode.PLAIN, TerminalInputMode.fromStored("plain"))
        assertEquals(TerminalInputMode.SYSTEM, TerminalInputMode.fromStored("SYSTEM"))
    }
}
