package org.indra.android.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The prompt's editing rules, which are the ones a phone keyboard makes easy to get wrong: the
 * symbol row edits at the caret rather than appending, and Return while a command runs must keep
 * the line instead of dropping it (the field stays editable during a run, so submission is what
 * waits).
 */
class PromptEditorTest {
    private val editor = PromptEditor()

    @Test
    fun `a symbol lands at the caret, not at the end of the line`() {
        editor.type("ls -la /data", cursor = 2)
        editor.insert("/")
        assertEquals("ls/ -la /data", editor.text)
        assertEquals(3, editor.cursor)
    }

    @Test
    fun `backspace deletes before the caret, not the last character`() {
        editor.type("abc", cursor = 2)
        editor.backspace()
        assertEquals("ac", editor.text)
        assertEquals(1, editor.cursor)
    }

    @Test
    fun `backspace at the start of the line changes nothing`() {
        editor.type("abc", cursor = 0)
        editor.backspace()
        assertEquals("abc", editor.text)
    }

    @Test
    fun `history walks back to the newest entry and then clears the line`() {
        val history = listOf("id", "ls -l")
        editor.recall(history, -1)
        assertEquals("ls -l", editor.text)
        editor.recall(history, -1)
        assertEquals("id", editor.text)
        editor.recall(history, 1)
        assertEquals("ls -l", editor.text)
        editor.recall(history, 1)
        assertEquals("", editor.text)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun `a recalled command puts the caret at the end of it`() {
        editor.recall(listOf("ip addr"), -1)
        assertEquals(7, editor.cursor)
    }

    @Test
    fun `submit hands over the trimmed command and clears the prompt`() {
        var ran: String? = null
        editor.type("  id  ", cursor = 6)
        editor.submit(running = false) { ran = it }
        assertEquals("id", ran)
        assertEquals("", editor.text)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun `submit is refused while a command runs and the line is kept`() {
        var ran: String? = null
        editor.type("nmap -sS 10.0.0.1", cursor = 18)
        editor.submit(running = true) { ran = it }
        assertEquals(null, ran)
        assertEquals("nmap -sS 10.0.0.1", editor.text)
    }

    @Test
    fun `an empty line is not run and is not swallowed`() {
        var ran: String? = null
        editor.type("   ", cursor = 3)
        editor.submit(running = false) { ran = it }
        assertEquals(null, ran)
        assertEquals("   ", editor.text)
    }

    @Test
    fun `picking a snippet replaces the line and puts the caret after it`() {
        editor.type("ls", cursor = 2)
        editor.pick("ip neigh")
        assertEquals("ip neigh", editor.text)
        assertEquals(8, editor.cursor)
    }
}
