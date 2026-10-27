package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = "\u001B"
private const val RESET = "$ESC[0m"
private const val RED = "$ESC[31m"
private const val GREEN_BG = "$ESC[42m"
private const val BRIGHT_RED = "$ESC[91m"
private const val BRIGHT_BLUE_BG = "$ESC[104m"
private const val BOLD = "$ESC[1m"
private const val DIM_OFF = "$ESC[22m"
private const val UNDERLINE = "$ESC[4m"
private const val INVERSE = "$ESC[7m"
private const val ITALIC = "$ESC[3m"
private const val DEFAULT_FG = "$ESC[39m"
private const val DEFAULT_BG = "$ESC[49m"
private const val ERASE_LINE = "$ESC[2K"
private const val CURSOR_UP = "$ESC[1A"
private const val OSC_TITLE = "$ESC]0;nmap scan\u0007"

private const val SLOT_RED = 1
private const val SLOT_GREEN = 2
private const val SLOT_BRIGHT_RED = 9
private const val SLOT_BRIGHT_BLUE = 12
private const val INDEXED_SLOT = 196
private const val TRUECOLOUR_R = 10
private const val TRUECOLOUR_G = 20
private const val TRUECOLOUR_B = 30

class AnsiTest {
    @Test
    fun `plain text is one unstyled span`() {
        val spans = Ansi.parse("nmap 7.94 ready")

        assertEquals(1, spans.size)
        assertEquals("nmap 7.94 ready", spans[0].text)
        assertEquals(AnsiStyle.DEFAULT, spans[0].style)
    }

    @Test
    fun `empty input has no spans`() {
        assertTrue(Ansi.parse("").isEmpty())
    }

    @Test
    fun `colour applies until reset`() {
        val spans = Ansi.parse("${RED}error$RESET ok")

        assertEquals(2, spans.size)
        assertEquals("error", spans[0].text)
        assertEquals(AnsiColor.Basic(SLOT_RED), spans[0].style.foreground)
        assertEquals(" ok", spans[1].text)
        assertNull(spans[1].style.foreground)
    }

    @Test
    fun `bright foreground and background slots`() {
        val spans = Ansi.parse("${BRIGHT_RED}hot$RESET${BRIGHT_BLUE_BG}selected$RESET")

        assertEquals(AnsiColor.Basic(SLOT_BRIGHT_RED), spans[0].style.foreground)
        assertEquals("selected", spans[1].text)
        assertEquals(AnsiColor.Basic(SLOT_BRIGHT_BLUE), spans[1].style.background)
    }

    @Test
    fun `attributes can be turned on and off`() {
        val spans = Ansi.parse("$BOLD$UNDERLINE$ITALIC${INVERSE}styled$DIM_OFF still styled$RESET plain")

        val styled = spans[0].style
        assertTrue(styled.bold)
        assertTrue(styled.underline)
        assertTrue(styled.italic)
        assertTrue(styled.inverse)
        assertEquals("styled", spans[0].text)
        assertFalse(spans[1].style.bold)
        assertTrue(spans[1].style.underline)
        assertFalse(spans.last().style.underline)
    }

    @Test
    fun `background and default colour resets`() {
        val spans = Ansi.parse("${GREEN_BG}x$DEFAULT_BG y$RED z$DEFAULT_FG w")

        assertEquals(AnsiColor.Basic(SLOT_GREEN), spans[0].style.background)
        assertNull(spans[1].style.background)
        assertEquals(AnsiColor.Basic(SLOT_RED), spans[2].style.foreground)
        assertNull(spans[3].style.foreground)
    }

    @Test
    fun `256 colour and truecolour`() {
        val spans =
            Ansi.parse(
                "${ESC}[38;5;${INDEXED_SLOT}mindexed$RESET${ESC}[38;2;$TRUECOLOUR_R;$TRUECOLOUR_G;${TRUECOLOUR_B}mrgb$RESET",
            )

        assertEquals(AnsiColor.Indexed(INDEXED_SLOT), spans[0].style.foreground)
        assertEquals(
            AnsiColor.Rgb(TRUECOLOUR_R, TRUECOLOUR_G, TRUECOLOUR_B),
            spans[1].style.foreground,
        )
    }

    @Test
    fun `cursor and erase sequences are dropped, text survives`() {
        val spans = Ansi.parse("progress$ERASE_LINE$CURSOR_UP 75%$OSC_TITLE")

        assertEquals(1, spans.size)
        assertEquals("progress 75%", spans[0].text)
    }

    @Test
    fun `carriage return rewrites the line`() {
        // A progress bar prints "file 10%\rfile 20%\rfile 100%" on one line.
        val spans = Ansi.parse("file 10%\rfile 20%\r${RED}file 100%")

        assertEquals(1, spans.size)
        assertEquals("file 100%", spans[0].text)
        assertEquals(AnsiColor.Basic(SLOT_RED), spans[0].style.foreground)
    }

    @Test
    fun `an unterminated escape is dropped, not printed`() {
        val spans = Ansi.parse("download$ESC[38;5")

        assertEquals(1, spans.size)
        assertEquals("download", spans[0].text)
    }

    @Test
    fun `identical styles collapse into one span`() {
        // A reset in the middle does not start a new visual run, so rendering stays cheap.
        val spans = Ansi.parse("${RED}a$RESET${RED}b$RESET")

        assertEquals(1, spans.size)
        assertEquals("ab", spans[0].text)
    }

    @Test
    fun `strip removes escapes and keeps the text`() {
        val raw = "${BOLD}ready$RESET in ${RED}2s$RESET"

        assertEquals("ready in 2s", Ansi.strip(raw))
        assertFalse(Ansi.strip(raw).contains(ESC))
        assertEquals(spansText(raw), Ansi.strip(raw))
    }

    private fun spansText(raw: String): String = Ansi.parse(raw).joinToString("") { it.text }
}
