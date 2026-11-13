package org.indra.android.core.system

/**
 * One record of apk's installed database (`<rootfs>/lib/apk/db/installed`).
 *
 * This is the only thing that knows what is actually *inside* the Alpine rootfs: the archive that
 * installs it drops files on disk, and `apk add` updates this database. Reading it is how the
 * Settings card can say "9 packages · iw, iproute2 added" instead of guessing from a directory
 * listing, and how a package removed from the Terminal stops being reported.
 *
 * @property name package name (`P:`), e.g. `busybox`, `iproute2`
 * @property version package version (`V:`), e.g. `1.36.1-r29`
 * @property installedBytes bytes the package occupies on disk (`I:`), or 0 when the record has no
 *   size field (a truncated database must not be read as "this package is empty")
 */
data class ChrootPackage(
    val name: String,
    val version: String = "",
    val installedBytes: Long = 0L,
)

/**
 * Parser for apk's `installed` database.
 *
 * The file is a sequence of blank-line-separated records, each a list of `X:value` lines where `X`
 * is a single letter (`P` name, `V` version, `S` package size, `I` installed size, `D` dependencies,
 * …). Values may contain spaces, so nothing here splits on anything but the first `:` — a parser
 * that split on whitespace would truncate `D:` into nonsense and, worse, could mistake a value for a
 * field name.
 *
 * Pure on purpose: the rootfs is a real artifact on a device, but deciding what it contains is
 * testable with a string.
 */
object ChrootPackages {
    private const val FIELD_OFFSET = 2

    /**
     * Parses [text] into records, in file order.
     *
     * Tolerant by design: a malformed line is skipped rather than aborting the read (one corrupt
     * record must not hide the rest of an installed rootfs), a record without a `P:` line is
     * dropped (it cannot be named), and `\r\n` is accepted because a database edited on a host may
     * arrive that way.
     */
    fun parse(text: String): List<ChrootPackage> {
        val packages = ArrayList<ChrootPackage>()
        var fields = LinkedHashMap<Char, String>()

        fun flush() {
            if (fields.isNotEmpty()) {
                toPackage(fields)?.let { packages += it }
                fields = LinkedHashMap()
            }
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> flush()
                // First occurrence wins: a repeated key is corruption, not an update.
                line.length > FIELD_OFFSET && line[1] == ':' -> fields.putIfAbsent(line[0], line.substring(FIELD_OFFSET))
            }
        }
        flush()
        return packages
    }

    private fun toPackage(fields: Map<Char, String>): ChrootPackage? {
        val name = fields['P']?.trim().orEmpty()
        if (name.isEmpty()) return null
        return ChrootPackage(
            name = name,
            version = fields['V']?.trim().orEmpty(),
            installedBytes = fields['I']?.trim()?.toLongOrNull() ?: 0L,
        )
    }

    /** The names of [packages], in file order. */
    fun names(packages: List<ChrootPackage>): List<String> = packages.map { it.name }
}
