package org.indra.android.core.data.bundle

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Extracts a gzipped tar stream under a single root, hardening it against
 * archive attacks:
 *  - every entry name must map to a path inside [root] (no `..`, no absolute);
 *  - only regular files and directories are written — symlinks, hardlinks,
 *    fifos and devices are skipped, so an archive cannot point outside the root;
 *  - the executable bit is restored on regular files.
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
            val mapped = mapPath(entry.name)
            if (mapped != null) {
                count++
                val target = File(root, mapped).canonicalFile
                require(
                    target == rootCanon || target.path.startsWith(rootCanon.path + File.separator),
                ) { "entry ${entry.name} escapes extraction root" }
                writeEntry(entry, target, tarIn, buffer)
                onEntry(count, entry)
            }
            entry = tarIn.nextEntry
        }
        tarIn.close()
    }

    private fun writeEntry(
        entry: TarArchiveEntry,
        target: File,
        tarIn: TarArchiveInputStream,
        buffer: ByteArray,
    ) {
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isFile -> {
                target.parentFile?.mkdirs()
                copy(tarIn, target, buffer)
                if (entry.mode and EXEC_BITS != 0) target.setExecutable(true, false)
            }
            // Symlinks, hardlinks, fifos and devices are deliberately skipped.
        }
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
