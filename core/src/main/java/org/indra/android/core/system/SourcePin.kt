package org.indra.android.core.system

import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * A pin on one vendored source tree: the tree's path in the repo and the digest it must have.
 *
 * Why this exists: `indra/jni` vendors forty-odd upstream projects, and Indra patches a dozen of
 * them (see the patch table in `AGENTS.md`). Nothing verified that the tree in the repo is the tree
 * the patches were written against, so an edit to vendored code — deliberate or by a submodule
 * update — was invisible until a device build behaved differently.
 *
 * The digest is over **content**, not history: a tree hash would pin the submodule's commit, which
 * says nothing about a working-copy edit, and that is the case this guards.
 */
data class TreePin(
    val name: String,
    val path: String,
    val sha256: String,
)

/**
 * A pin on a single vendored file, for the trees too large to hash whole (`nmap` is 44 MB, `icu4c`
 * 118 MB) but which Indra still patches. Pinning the patched file is what matters there: a modified
 * `nmap/Android.mk` or `icu4c/i18n/uspoof.cpp` is exactly the change a whole-tree pin would catch
 * and a version string would not.
 */
data class FilePin(
    val name: String,
    val path: String,
    val sha256: String,
)

/** The pin file: what it was generated from, one entry per vendored tree, and per-file pins. */
data class SourcePins(
    val note: String,
    val trees: List<TreePin>,
    val files: List<FilePin> = emptyList(),
) {
    fun pinFor(name: String): TreePin? = trees.firstOrNull { it.name == name }

    fun fileFor(name: String): FilePin? = files.firstOrNull { it.name == name }
}

/** The result of checking one tree against its pin. */
sealed interface PinStatus {
    val name: String

    /** The tree's digest is the pinned one. */
    data class Verified(override val name: String) : PinStatus

    /** The tree exists and differs from its pin: vendored code changed without the pin moving. */
    data class Drifted(
        override val name: String,
        val expected: String,
        val actual: String,
    ) : PinStatus

    /** The pin names a tree that is not on disk (submodule not initialised, or a moved path). */
    data class Missing(
        override val name: String,
        val path: String,
    ) : PinStatus
}

/** The digest of one file: the unit a [FilePin] compares. */
fun fileDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** One file of a tree, as the digest sees it. */
data class TreeFile(
    /** Path relative to the tree root, with `/` separators on every platform. */
    val path: String,
    val bytes: ByteArray,
) {
    // ByteArray's generated equals/hashCode are identity-based; a data class over it would lie.
    override fun equals(other: Any?): Boolean =
        other is TreeFile && path == other.path && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/**
 * The digest of a whole tree.
 *
 * Sorted by path and length-prefixed, so the result does not depend on the filesystem's iteration
 * order and cannot be collided by moving bytes between two files (`a`+`bc` must not equal `ab`+`c`,
 * which a plain concatenation would allow).
 *
 * Paths are normalised to `/` and the tree root itself is not part of the digest — the same tree
 * pinned at two paths hashes the same, which is what makes a pin survive a directory move.
 */
fun treeDigest(files: List<TreeFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files
        .map { it.copy(path = it.path.replace('\\', '/')) }
        .sortedBy { it.path }
        .forEach { file ->
            digest.update(file.path.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(file.bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(file.bytes)
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Reads `pins/sources.json`; null when the file is not a pin file at all.
 *
 * Strict on purpose: a pin file that silently parses to zero trees would verify every tree by
 * omission, which is worse than failing.
 */
fun parseSourcePins(json: String): SourcePins? {
    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
    val trees = root?.getAsJsonArray("trees")?.mapNotNull { entryOf(it) }.orEmpty()
    val files = root?.getAsJsonArray("files")?.mapNotNull { fileEntryOf(it) }.orEmpty()
    val pinned = trees.isNotEmpty() || files.isNotEmpty()
    // Nothing pinned is "not a pin file", not "everything matches": that would verify by omission.
    return if (pinned) SourcePins(root?.note().orEmpty(), trees, files) else null
}

private fun entryOf(element: com.google.gson.JsonElement): TreePin? {
    val entry = element.takeIf { it.isJsonObject }?.asJsonObject
    val name = entry?.text("name").orEmpty()
    val path = entry?.text("path").orEmpty()
    val sha256 = entry?.text("sha256").orEmpty()
    val complete = name.isNotBlank() && path.isNotBlank() && sha256.isNotBlank()
    // Digests are normalised to lower case so a comparison never depends on how they were written.
    return if (complete) TreePin(name, path, sha256.lowercase()) else null
}

private fun fileEntryOf(element: com.google.gson.JsonElement): FilePin? {
    val entry = element.takeIf { it.isJsonObject }?.asJsonObject
    val name = entry?.text("name").orEmpty()
    val path = entry?.text("path").orEmpty()
    val sha256 = entry?.text("sha256").orEmpty()
    val complete = name.isNotBlank() && path.isNotBlank() && sha256.isNotBlank()
    return if (complete) FilePin(name, path, sha256.lowercase()) else null
}

private fun com.google.gson.JsonObject.text(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

private fun com.google.gson.JsonObject.note(): String = text("note")

/**
 * Checks each pin against the digest actually computed for it.
 *
 * [actual] is keyed by pin name; a tree the caller could not read at all is simply absent, which
 * reports [PinStatus.Missing] rather than a drift (a missing submodule must not read as "someone
 * edited the code").
 */
fun verifyPins(
    pins: SourcePins,
    actual: Map<String, String>,
): List<PinStatus> =
    pins.trees.map { pin ->
        val digest = actual[pin.name]
        when {
            digest == null -> PinStatus.Missing(pin.name, pin.path)
            digest.equals(pin.sha256, ignoreCase = true) -> PinStatus.Verified(pin.name)
            else -> PinStatus.Drifted(pin.name, pin.sha256, digest)
        }
    }

/** Checks the per-file pins; same verdicts, reported by file name. */
fun verifyFilePins(
    pins: SourcePins,
    actual: Map<String, String>,
): List<PinStatus> = verifyPins(SourcePins(pins.note, pins.files.map { TreePin(it.name, it.path, it.sha256) }), actual)

/** True when every pin verified; the shape a build gate wants. */
fun List<PinStatus>.allVerified(): Boolean = all { it is PinStatus.Verified }

/** The human-readable failure list, one line per problem. */
fun List<PinStatus>.problems(): List<String> =
    mapNotNull { status ->
        when (status) {
            is PinStatus.Verified -> null
            is PinStatus.Drifted ->
                "${status.name}: vendored tree changed (pinned ${status.expected.take(SHORT_DIGEST)}…, " +
                    "found ${status.actual.take(SHORT_DIGEST)}…) — re-apply the patches and update pins/sources.json"
            is PinStatus.Missing -> "${status.name}: ${status.path} is not present (submodule not initialised?)"
        }
    }

/** How much of a digest a problem line shows; enough to tell two apart, short enough to read. */
private const val SHORT_DIGEST = 12

/** Field separator inside the digest's input; a byte that cannot appear in a path. */
private val SEPARATOR = byteArrayOf(0)
