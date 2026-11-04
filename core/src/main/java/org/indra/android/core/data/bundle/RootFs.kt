package org.indra.android.core.data.bundle

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RootFs {
    fun deleteTree(dir: File): Boolean {
        if (!dir.exists()) return true
        // Bottom-up: a parent delete fails while it still has children.
        dir.walkBottomUp().forEach { it.delete() }
        return !dir.exists()
    }

    @Suppress("SwallowedException")
    fun atomicMove(src: File, dest: File) {
        try {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Cross-device fallback: copy + delete
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            deleteTree(src)
        } catch (e: IOException) {
            throw IOException("atomic move $src -> $dest failed: ${e.message}", e)
        }
    }
}
