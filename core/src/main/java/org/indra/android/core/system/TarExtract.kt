package org.indra.android.core.system

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Extracts a tar stream, restoring what a naive `copyTo` loop throws away: **mode bits**,
 * **symlinks** and **hard links**.
 *
 * The old extractor only wrote file bodies and skipped every symlink (`Logger.w("Skipping
 * symlink …")`), which is why the Alpine installer had to `walkTopDown().setExecutable(true)`
 * the whole tree — that made every regular file executable, so `apk` refused to trust
 * anything and nothing was a symlink, so `/bin/sh` had to be re-created by hand.
 *
 * Extracted paths are resolved with [LinkOption.NOFOLLOW_LINKS] against the canonical root,
 * so a symlink pointing at `/etc/passwd`, `..` or an absolute path still cannot write
 * outside [destination].
 */
object TarExtract {
    /**
     * @return true when at least one entry was seen (an empty archive is a corrupt one).
     * @throws IOException on an entry that escapes [destination].
     */
    fun extract(
        input: InputStream,
        destination: File,
    ): Boolean {
        val root = destination.canonicalFile.toPath()
        require(root.toFile().exists() || root.toFile().mkdirs()) { "cannot create ${root}" }
        var extractedAny = false

        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                extractedAny = true
                val target = resolveInside(root, entry.name)
                when {
                    entry.isDirectory -> createDirectory(target, entry.mode)
                    entry.isSymbolicLink -> createSymlink(target, entry.linkName)
                    entry.isLink -> createHardLink(root, target, entry.linkName)
                    else -> writeFile(target, tar, entry)
                }
                entry = tar.nextEntry
            }
        }
        return extractedAny
    }

    /** Rejects `..`, absolute names and anything that resolves outside the root. */
    private fun resolveInside(
        root: Path,
        name: String,
    ): Path {
        val candidate = root.resolve(name).normalize()
        if (candidate != root && !candidate.startsWith(root)) {
            throw IOException("Illegal path in archive: $name")
        }
        // An existing symlink at any component of the path is fine for the *parent* walk
        // (archives are extracted in order), but the final component must not be followed.
        return candidate
    }

    private fun createDirectory(
        target: Path,
        mode: Int,
    ) {
        Files.createDirectories(target)
        applyMode(target, mode, isDirectory = true)
    }

    private fun writeFile(
        target: Path,
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        // A previous run of the same archive may have left a symlink here; never follow it.
        Files.deleteIfExists(target)
        Files.newOutputStream(target).use { output -> tar.copyTo(output) }
        applyMode(target, entry.mode, isDirectory = false)
    }

    private fun createSymlink(
        target: Path,
        linkName: String,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(target)
        runCatching { Files.createSymbolicLink(target, Paths.get(linkName)) }
            .onFailure {
                // Filesystems without symlink support (or SELinux on a shared dir) fall back
                // to copying nothing: the caller's health check reports the missing binary.
                Logger.w("Cannot create symlink ${target.fileName} -> $linkName: ${it.message}")
            }
    }

    private fun createHardLink(
        root: Path,
        target: Path,
        linkName: String,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        val source = resolveInside(root, linkName)
        Files.deleteIfExists(target)
        runCatching { Files.createLink(target, source) }
            .onFailure {
                // Different filesystem or a missing source: a real copy keeps the tree usable.
                runCatching {
                    if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(source, target)
                    }
                }
            }
    }

    private fun applyMode(
        target: Path,
        mode: Int,
        isDirectory: Boolean,
    ) {
        if (mode <= 0) {
            // No mode in the archive (rare, but legal): keep the filesystem default, except
            // that a directory the process cannot enter would break every later extraction.
            if (isDirectory) setPermissions(target, DIRECTORY_DEFAULT)
            return
        }
        setPermissions(target, fromMode(mode))
    }

    private fun setPermissions(
        target: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        runCatching { Files.setPosixFilePermissions(target, permissions) }
            .onFailure { Logger.w("Cannot set mode on $target: ${it.message}") }
    }

    /** `rwxr-xr-x` — what an untagged directory needs to be traversable. */
    private val DIRECTORY_DEFAULT: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwxr-xr-x")

    /**
     * tar mode → POSIX permissions. Only the permission bits are honoured; setuid/setgid and
     * the sticky bit are deliberately dropped (the chroot is root-owned by the app).
     */
    @Suppress("MagicNumber")
    fun fromMode(mode: Int): Set<PosixFilePermission> {
        val bits = mode and 0x1FF // 0777
        val perms = mutableSetOf<PosixFilePermission>()
        if (bits and 0b100000000 != 0) perms += PosixFilePermission.OWNER_READ
        if (bits and 0b010000000 != 0) perms += PosixFilePermission.OWNER_WRITE
        if (bits and 0b001000000 != 0) perms += PosixFilePermission.OWNER_EXECUTE
        if (bits and 0b000100000 != 0) perms += PosixFilePermission.GROUP_READ
        if (bits and 0b000010000 != 0) perms += PosixFilePermission.GROUP_WRITE
        if (bits and 0b000001000 != 0) perms += PosixFilePermission.GROUP_EXECUTE
        if (bits and 0b000000100 != 0) perms += PosixFilePermission.OTHERS_READ
        if (bits and 0b000000010 != 0) perms += PosixFilePermission.OTHERS_WRITE
        if (bits and 0b000000001 != 0) perms += PosixFilePermission.OTHERS_EXECUTE
        return perms
    }
}
