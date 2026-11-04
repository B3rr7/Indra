package org.indra.android.core.data.bundle

import java.io.File
import java.io.IOException

/** Atomically replaces the live bundle directories with the staged ones. */
class BundleSwapper(
    private val filesDir: File,
    private val fs: RootFs,
) {
    companion object {
        const val OLD_PREFIX = ".old-"
    }

    /** Restore a live dir from an interrupted swap and delete stale staging/old dirs. */
    fun repairLeftovers() {
        filesDir.listFiles()?.forEach { file ->
            when {
                file.name.startsWith(BundleInstaller.STAGING_PREFIX) -> fs.deleteTree(file)
                file.name.startsWith(OLD_PREFIX) -> {
                    val liveDir = File(filesDir, file.name.removePrefix(OLD_PREFIX))
                    if (liveDir.exists()) fs.deleteTree(file) else fs.atomicMove(file, liveDir)
                }
                else -> Unit
            }
        }
    }

    /** Replace the live dirs with the staged ones. Rolls back on failure. */
    @Suppress("TooGenericExceptionCaught")
    fun swap(spec: BundleSpec, staging: File) {
        val oldDirs = mutableListOf<File>()
        try {
            spec.liveDirs.forEach { liveName ->
                replaceLiveDir(staging, liveName)?.let { oldDirs += it }
            }
            oldDirs.forEach { fs.deleteTree(it) } // success: drop the backups
        } catch (e: Exception) {
            rollback(oldDirs)
            throw IOException("swap failed, rolled back: ${e.message}", e)
        }
    }

    /** Move the live dir aside and the staged dir in. Returns the backup dir, or null if none. */
    private fun replaceLiveDir(staging: File, liveName: String): File? {
        val liveDir = File(filesDir, liveName)
        val oldDir = File(filesDir, OLD_PREFIX + liveName)
        if (liveDir.exists()) {
            if (oldDir.exists()) fs.deleteTree(oldDir)
            fs.atomicMove(liveDir, oldDir)
        } else {
            fs.atomicMove(File(staging, liveName), liveDir)
            return null
        }
        fs.atomicMove(File(staging, liveName), liveDir)
        return oldDir
    }

    private fun rollback(oldDirs: List<File>) {
        oldDirs.forEach { oldDir ->
            val liveDir = File(filesDir, oldDir.name.removePrefix(OLD_PREFIX))
            if (liveDir.exists()) fs.deleteTree(liveDir)
            fs.atomicMove(oldDir, liveDir)
        }
    }
}
