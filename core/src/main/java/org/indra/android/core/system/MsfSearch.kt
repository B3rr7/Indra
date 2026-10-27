package org.indra.android.core.system

/** One row of `msfconsole search` output. */
data class MsfModule(
    val name: String,
    val disclosureDate: String,
    val rank: String,
    val description: String,
)

/**
 * Parser for `msfconsole -q -x "search …"` output.
 *
 * msf prints a `Matching Modules` heading, a `#  Name  Disclosure Date  Rank  Check  Description`
 * header row, a dashed separator and then one row per module. It is a **fixed-width** table
 * (Rex::Text::Table pads every column to the widest cell), so cells are sliced by the header's
 * column offsets — splitting on runs of spaces merges `Rank` and `Check` whenever a rank value
 * happens to fill its column exactly. The `Check` column only exists in msf6.
 *
 * [parse] returns **null** — never an empty list — when the transcript has no such table (a usage
 * error, a broken chroot, an msf version with a different layout). The screen shows the real
 * transcript in that case instead of claiming "0 exploits found".
 */
object MsfSearch {
    const val MAX_MODULES = 500

    private val MODULE_TYPES = listOf("exploit", "auxiliary", "post", "payload", "encoder", "nop", "evasion")

    /** Column start offsets taken from the header line of one table. */
    private data class Columns(
        val index: Int,
        val name: Int,
        val date: Int,
        val rank: Int,
        val check: Int?,
        val description: Int,
    )

    /** Module rows, or null when the transcript contains no module table at all. */
    fun parse(transcript: String): List<MsfModule>? {
        val lines = transcript.lines()
        val headerIndex = lines.indexOfFirst(::isHeaderRow)
        val columns = if (headerIndex >= 0) columnsOf(lines[headerIndex]) else null
        return when (columns) {
            null -> null
            else ->
                lines
                    .asSequence()
                    .drop(headerIndex + 1)
                    .takeWhile { it.isNotBlank() }
                    .mapNotNull { parseRow(it, columns) }
                    .take(MAX_MODULES)
                    .toList()
        }
    }

    /** The `#  Name … Rank … Description` line, not the `Matching Modules` heading above it. */
    private fun isHeaderRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("#") && trimmed.contains("Name") && trimmed.contains("Rank")
    }

    private fun columnsOf(header: String): Columns? {
        val name = header.indexOf("Name")
        val date = header.indexOf("Disclosure Date")
        val rank = header.indexOf("Rank")
        val description = header.indexOf("Description")
        val ordered = name >= 0 && name < date && date < rank && rank < description
        if (!ordered) return null
        val check = header.indexOf("Check").takeIf { it in rank..description }
        return Columns(index = 0, name = name, date = date, rank = rank, check = check, description = description)
    }

    private fun parseRow(
        line: String,
        columns: Columns,
    ): MsfModule? {
        val name = slice(line, columns.name, columns.date)
        val looksLikeModule =
            slice(line, columns.index, columns.name).toIntOrNull() != null &&
                MODULE_TYPES.any { name.startsWith("$it/") }
        return if (looksLikeModule) {
            MsfModule(
                name = name,
                disclosureDate = slice(line, columns.date, columns.rank),
                rank = slice(line, columns.rank, columns.check ?: columns.description),
                description = slice(line, columns.description, line.length),
            )
        } else {
            null
        }
    }

    private fun slice(
        line: String,
        start: Int,
        end: Int,
    ): String = if (start >= line.length) "" else line.substring(start, minOf(end, line.length)).trim()
}
