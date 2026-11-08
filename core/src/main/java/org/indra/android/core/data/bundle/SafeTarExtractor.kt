package org.indra.android.core.data.bundle

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Extracts a gzipped tar stream under a single root, hardening it against
 * archive attacks:
 *  - every entry name must map to a path inside [root] (no `..`, no absolute);
 *  - symlinks are allowed but their target must resolve inside [root] too, so an
 *    archive cannot point outside the root through a link;
 *  - fifos and device nodes are skipped;
 *  - the executable bit is restored on regular files.
 *
 * Symlinks matter: an Alpine rootfs is mostly links (`/bin/sh -> busybox`,
 * `libssl.so -> libssl.so.3`), and dropping them yields a chroot whose shell cannot
 * start. Absolute link targets are interpreted *inside* the root, which is exactly how
 * they behave once the directory is used as a `chroot`.
 *
 * The caller owns the sha256/size check; [onEntry] is called per extracted entry
 * with the running count so a large extraction can report progress.
 */
object SafeTarExtractor {

    /** `mode & EXEC_BITS != 0` means an owner/group/other execute bit is set. */
    private const val EXEC_BITS = 0b001_001_001
    private const val COPY_BUFFER_BYTES = 65_536

    fun extract(
        gzStream: InputStream,
        root: File,
        mapPath: (String) -> String?,
        onEntry: (Int, TarArchiveEntry) -> Unit = { _, _ -> },
    ) {
        val tarIn = TarArchiveInputStream(GZIPInputStream(gzStream))
        val rootCanon = root.canonicalFile
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var count = 0
        var entry: TarArchiveEntry? = tarIn.nextEntry
        while (entry != null) {
            val mapped = mapPath(normalize(entry.name))
            if (mapped != null) {
                count++
                val target = File(root, mapped).canonicalFile
                require(insideRoot(target, rootCanon)) { "entry ${entry.name} escapes extraction root" }
                writeEntry(entry, target, rootCanon, tarIn, buffer)
                onEntry(count, entry)
            }
            entry = tarIn.nextEntry
        }
        tarIn.close()
    }

    /**
     * Alpine (and GNU tar) write `./bin/busybox`; the leading `./` is not part of the
     * path and would make every mapper miss.
     */
    private fun normalize(name: String): String = name.removePrefix("./")

    private fun insideRoot(target: File, rootCanon: File): Boolean =
        target == rootCanon || target.path.startsWith(rootCanon.path + File.separator)

    /**
     * Lexical containment for a symlink target.
     *
     * `.`/`..` are resolved textually, but the path is deliberately **not** canonicalised:
     * following the link would be wrong here, because an absolute link is only absolute
     * *inside the chroot*. An Alpine certificate directory makes the difference visible —
     * `0a77.0 -> ca-cert-GTS_Root_R3.pem` and `ca-cert-GTS_Root_R3.pem -> /usr/share/…`;
     * canonicalising the first link follows the second to the host's `/usr/share` and looks
     * like an escape. Lexically it is `<root>/etc/ssl/certs/ca-cert-GTS_Root_R3.pem`, inside.
     *
     * The write path still canonicalises ([insideRoot]) so a symlink can never be used to
     * write outside the root; only the link *target* check is lexical.
     */
    private fun insideRootLexically(candidate: File, rootCanon: File): Boolean {
        val normalized = candidate.toPath().normalize()
        val rootPath = rootCanon.toPath()
        return normalized == rootPath || normalized.startsWith(rootPath)
    }

    private fun writeEntry(
        entry: TarArchiveEntry,
        target: File,
        rootCanon: File,
        tarIn: TarArchiveInputStream,
        buffer: ByteArray,
    ) {
        when {
            entry.isDirectory -> target.mkdirs()
            // `isFile()` is true for symlinks too in commons-compress, so links come first.
            entry.isSymbolicLink -> writeSymlink(entry, target, rootCanon)
            entry.isLink -> writeHardLink(entry, target, rootCanon)
            entry.isFile -> writeFile(entry, target, tarIn, buffer)
            // Fifos and device nodes are deliberately skipped.
        }
    }

    private fun writeFile(
        entry: TarArchiveEntry,
        target: File,
        tarIn: TarArchiveInputStream,
        buffer: ByteArray,
    ) {
        target.parentFile?.mkdirs()
        copy(tarIn, target, buffer)
        if (entry.mode and EXEC_BITS != 0) target.setExecutable(true, false)
    }

    private fun writeSymlink(entry: TarArchiveEntry, target: File, rootCanon: File) {
        val link = entry.linkName
        require(link.isNotEmpty()) { "symlink ${entry.name} has no target" }
        val resolved =
            if (link.startsWith("/")) {
                File(rootCanon, link.trimStart('/'))
            } else {
                File(target.parentFile ?: rootCanon, link)
            }
        require(insideRootLexically(resolved, rootCanon)) {
            "symlink ${entry.name} -> $link escapes extraction root"
        }
        target.parentFile?.mkdirs()
        deleteIfPresent(target)
        Files.createSymbolicLink(target.toPath(), Paths.get(link))
    }

    /**
     * A hard link is another name for an already-extracted file. Fall back to a copy when
     * the filesystem refuses the link (some Android volumes do).
     */
    private fun writeHardLink(entry: TarArchiveEntry, target: File, rootCanon: File) {
        val source = File(rootCanon, normalize(entry.linkName)).canonicalFile
        if (!insideRoot(source, rootCanon) || !source.isFile) return
        target.parentFile?.mkdirs()
        deleteIfPresent(target)
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (_: IOException) {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun deleteIfPresent(target: File) {
        if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
    }

    private fun copy(tarIn: TarArchiveInputStream, target: File, buffer: ByteArray) {
        FileOutputStream(target).use { out ->
            var read = tarIn.read(buffer)
            while (read >= 0) {
                out.write(buffer, 0, read)
                read = tarIn.read(buffer)
            }
        }
    }
}
