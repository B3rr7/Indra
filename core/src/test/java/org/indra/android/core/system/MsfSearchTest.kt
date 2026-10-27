package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsfSearchTest {
    @Test
    fun `parses the msf6 module table`() {
        val table =
            table(
                withCheck = true,
                rows =
                    listOf(
                        listOf(
                            "0",
                            "exploit/windows/smb/ms17_010_eternalblue",
                            "2017-03-14",
                            "average",
                            "No",
                            "MS17-010 EternalBlue SMB Remote Windows Kernel Pool Corruption",
                        ),
                        listOf(
                            "1",
                            "exploit/windows/smb/ms17_010_psexec",
                            "2017-03-14",
                            "normal",
                            "No",
                            "MS17-010 EternalRomance SMB Remote Windows Code Execution",
                        ),
                    ),
            )
        val modules = MsfSearch.parse("msf6 > search eternalblue\n\n$table\n\nmsf6 > ")
        assertEquals(2, modules?.size)
        val first = modules!![0]
        assertEquals("exploit/windows/smb/ms17_010_eternalblue", first.name)
        assertEquals("2017-03-14", first.disclosureDate)
        assertEquals("average", first.rank)
        assertTrue(first.description.startsWith("MS17-010 EternalBlue"))
        assertEquals("normal", modules[1].rank)
    }

    @Test
    fun `rank and check do not merge when the rank fills its column`() {
        val table =
            table(
                withCheck = true,
                rows = listOf(listOf("0", "exploit/linux/local/foo", "2020-01-01", "excellent", "Yes", "A real module")),
            )
        val modules = MsfSearch.parse(table)
        assertEquals("excellent", modules?.get(0)?.rank)
        assertEquals("A real module", modules?.get(0)?.description)
    }

    @Test
    fun `parses the msf5 table without the check column`() {
        val table =
            table(
                withCheck = false,
                rows = listOf(listOf("0", "exploit/windows/smb/ms17_010_eternalblue", "2017-03-14", "average", "MS17-010 EternalBlue")),
            )
        val modules = MsfSearch.parse(table)
        assertEquals(1, modules?.size)
        assertEquals("average", modules!![0].rank)
        assertEquals("MS17-010 EternalBlue", modules[0].description)
    }

    @Test
    fun `table with no rows is an empty list not a null`() {
        assertEquals(emptyList<MsfModule>(), MsfSearch.parse(table(withCheck = true, rows = emptyList())))
    }

    @Test
    fun `unknown output is null so the screen shows the real transcript`() {
        assertNull(MsfSearch.parse(""))
        assertNull(MsfSearch.parse("[-] No results from search"))
        assertNull(MsfSearch.parse("msfconsole: command not found"))
    }

    @Test
    fun `non module rows are ignored`() {
        val table = table(withCheck = true, rows = listOf(listOf("#", "Name", "Disclosure Date", "Rank", "Check", "Description")))
        val noisy = "$table\n   [-] something else entirely\n   0  not/a/module  2020-01-01  low  No  nope"
        assertEquals(emptyList<MsfModule>(), MsfSearch.parse(noisy))
    }

    /**
     * Builds a fixed-width table the way Rex::Text::Table renders it: every column except the last
     * is padded to its widest cell plus two spaces.
     */
    private fun table(
        withCheck: Boolean,
        rows: List<List<String>>,
    ): String {
        val headers =
            buildList {
                addAll(listOf("#", "Name", "Disclosure Date", "Rank"))
                if (withCheck) add("Check")
                add("Description")
            }
        val all = listOf(headers) + rows
        val widths = headers.indices.map { col -> all.maxOf { it.getOrElse(col) { "" }.length } + PADDING }
        fun render(cells: List<String>): String =
            cells
                .mapIndexed { col, cell ->
                    if (col == headers.lastIndex) cell else cell.padEnd(widths[col])
                }.joinToString("")
        return buildString {
            appendLine("Matching Modules")
            appendLine("================")
            appendLine()
            appendLine(render(headers))
            appendLine(render(headers.map { "-".repeat(it.length) }))
            rows.forEach { appendLine(render(it)) }
        }
    }

    private companion object {
        const val PADDING = 2
    }
}
