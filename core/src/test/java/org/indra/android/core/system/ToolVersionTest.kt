package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolVersionTest {
    private val esc = '\u001B'

    /**
     * The exact line the Tools card showed, escape bytes included: nuclei colours only the tag
     * text, so the brackets around it are literal and the escape sits between them.
     */
    private val nucleiBanner = "[$esc[34mINF$esc[0m] Nuclei Engine Version: v3.11.1"

    @Test
    fun `colour escapes are stripped before the line reaches the card`() {
        assertEquals(
            "[INF] Nuclei Engine Version: v3.11.1",
            ToolVersion.parse(nucleiBanner),
        )
    }

    @Test
    fun `the command echo does not win when its path looks like a version`() {
        val transcript =
            """
            Executing: ./tools/nuclei/nuclei --version
            $nucleiBanner
            """.trimIndent()
        assertEquals("[INF] Nuclei Engine Version: v3.11.1", ToolVersion.parse(transcript))
    }

    @Test
    fun `a transcript with no version line yields null`() {
        assertNull(ToolVersion.parse(""))
        assertNull(ToolVersion.parse("usage: tool [-h]"))
        assertNull(ToolVersion.parse("Executing: ./tools/x/x --help"))
    }

    @Test
    fun `an uncoloured line is returned trimmed`() {
        assertEquals("nmap version 6.46", ToolVersion.parse("  nmap version 6.46  \n"))
    }
}
