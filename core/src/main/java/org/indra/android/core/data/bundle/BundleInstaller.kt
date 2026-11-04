package org.indra.android.core.data.bundle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

private const val BYTES_PER_MB = 1_048_576L

class InsufficientSpaceException(val needed: Long, val available: Long) :
    IOException("Not enough free space: need ${needed / BYTES_PER_MB} MB, have ${available / BYTES_PER_MB} MB")

/**
 * Generic bundle installer. Two phases:
 *  - [stage]: verify + extract into `filesDir/.staging-<id>`, run the bundle's
 *    patches, write `.bundle-version`. Live files are untouched.
 *  - [swap]: live dirs → `.old-<id>`, staging → live, delete `.old-<id>`.
 * [cleanupLeftovers] repairs a swap interrupted by a crash.
 */
@Suppress("TooManyFunctions")
class BundleInstaller(
    private val context: Context,
    private val fs: RootFs = RootFs(),
) {

    companion object {
        const val VERSION_FILE = ".bundle-version"
        const val SPACE_FACTOR = 1.15
        const val STAGING_PREFIX = ".staging-"
        private const val DRAIN_BUFFER_BYTES = 65_536
    }

    // Lazy: constructing the installer must not touch filesDir (StrictMode flags that on Main).
    private val swapper by lazy { BundleSwapper(context.filesDir, fs) }
    private var cleaned = false

    fun stagingDir(spec: BundleSpec): File = File(context.filesDir, STAGING_PREFIX + spec.id)

    fun readInstalledJson(spec: BundleSpec): String? =
        File(context.filesDir, "${spec.primaryDir}/$VERSION_FILE").takeIf { it.isFile }?.readText()

    fun readInstalled(spec: BundleSpec): BundleManifest? = BundleManifest.parseOrNull(readInstalledJson(spec))

    fun sentinelsPresent(spec: BundleSpec): Boolean = spec.sentinels.all { File(context.filesDir, it).exists() }

    fun readStagedVersion(spec: BundleSpec): String? = BundleManifest.parseOrNull(
        File(stagingDir(spec), "${spec.primaryDir}/$VERSION_FILE").takeIf { it.isFile }?.readText(),
    )?.version

    fun hasStaged(spec: BundleSpec): Boolean = File(stagingDir(spec), "${spec.primaryDir}/$VERSION_FILE").isFile

    /** Bytes needed on filesDir to stage [m] (old + new coexist until the swap). */
    fun requiredSpace(m: BundleManifest): Long = (m.unpacked * SPACE_FACTOR).toLong()

    fun checkFreeSpace(needed: Long) {
        val available = context.filesDir.usableSpace
        if (available < needed) throw InsufficientSpaceException(needed, available)
    }

    /**
     * Once per process: finish or roll back an interrupted swap and drop
     * half-extracted staging dirs. If a live dir is missing but its `.old-`
     * copy survived, the old copy is restored.
     */
    @Synchronized
    fun cleanupLeftovers() {
        if (cleaned) return
        cleaned = true
        swapper.repairLeftovers()
    }

    /**
     * Extract [gzStream] (the bundle archive) into the staging dir, verifying
     * its size and sha256 against [manifest] while streaming. Returns the staging dir.
     * [onProgress] gets (compressed bytes read, files extracted) and may throw
     * CancellationException to abort; staging is deleted on any failure.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun stage(
        spec: BundleSpec,
        gzStream: InputStream,
        manifest: BundleManifest,
        manifestJson: String,
        onProgress: (Long, Int) -> Unit = { _, _ -> },
    ): File {
        require(manifest.id == spec.id) { "manifest id ${manifest.id} != ${spec.id}" }
        checkFreeSpace(requiredSpace(manifest))
        val staging = stagingDir(spec)
        if (!fs.deleteTree(staging)) throw IOException("cannot clear $staging")
        staging.mkdirs()
        try {
            val hashed = HashingStream(gzStream)
            SafeTarExtractor.extract(
                gzStream = hashed,
                root = staging,
                mapPath = { name ->
                    spec.mapPath(name)?.also { mapped ->
                        require(spec.liveDirs.any { mapped == it || mapped.startsWith("$it/") }) {
                            "entry $name maps outside the bundle dirs"
                        }
                    }
                },
                onEntry = { count, _ -> if (count % 100 == 0) onProgress(hashed.count, count) },
            )
            // Hash must cover the whole file, including what tar/gzip left unread.
            val sink = ByteArray(DRAIN_BUFFER_BYTES)
            while (hashed.read(sink) >= 0) { /* drain */ }
            if (hashed.count != manifest.size) throw IOException("size mismatch: ${hashed.count} != ${manifest.size}")
            val actual = hashed.hexDigest()
            if (actual != manifest.sha256) throw IOException("sha256 mismatch: $actual")
            spec.liveDirs.forEach { if (!File(staging, it).isDirectory) throw IOException("bundle has no $it/") }
            spec.postInstall(staging)
            File(staging, "${spec.primaryDir}/$VERSION_FILE").writeText(manifestJson)
            onProgress(hashed.count, -1)
            return staging
        } catch (t: Throwable) {
            fs.deleteTree(staging)
            throw t
        }
    }

    /** Replace the live dirs with the staged ones. Rolls back on failure. */
    fun swap(spec: BundleSpec) {
        val staging = stagingDir(spec)
        if (!hasStaged(spec)) throw IOException("nothing staged for ${spec.id}")
        swapper.swap(spec, staging)
    }

    fun discardStaged(spec: BundleSpec) {
        fs.deleteTree(stagingDir(spec))
    }
}
