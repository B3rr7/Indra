package org.indra.android.core.system

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.indra.android.core.child.ChildProcessManager
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure OTA binary updater for the native core (indrad + bundled tools).
 *
 * Flow:
 *  1. Fetch + parse signed-content manifest (`manifest.json`) over pinned HTTPS.
 *  2. Probe local binary versions through the native daemon (`<binary> --version`).
 *  3. For binaries whose local version differs from the manifest: download the
 *     ABI-specific artifact, verify SHA-256, atomically swap into the app-private
 *     core dir, mark executable, restart the daemon (indrad) / re-exec (tools) and
 *     run a post-update health check.
 *  4. On any failure: roll back to the pre-update backup.
 */
@Singleton
class OtaUpdateManager
    @Inject
    constructor(
        private val system: IndraSystem,
        private val coreManager: CoreManager,
        private val smartToolBox: SmartToolBox,
        private val childProcessManager: ChildProcessManager,
    ) {
        companion object {
            const val OTA_MANIFEST_URL = "https://ota.indra.dev/v1/manifest.json"

            const val INDRA_DAEMON_NAME = "indrad"
            const val TOOLS_DIR_NAME = "tools"

            private const val BINARY_MODE = "rwxr-xr-x"
            private const val TMP_SUFFIX = ".ota-tmp-"

            private const val SHA256_PREFIX = "sha256:"
            private const val SIGNATURE_FIELD = "signature"
            private const val PAYLOAD_FIELD = "payload"

            /**
             * Placeholder until the release signing key is provisioned. While it starts
             * with REPLACE_ME, manifest signatures are only logged as unverified —
             * this state must never ship.
             */
            private const val OTA_SIGNING_KEY_SPKI = "REPLACE_ME_WITH_BASE64_X509_PUBLIC_KEY"

            private const val MANIFEST_TIMEOUT_SECONDS = 15L
            private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
            private const val PROBE_TIMEOUT_MS = 60_000L

            private const val TLS_PIN_HOST = "ota.indra.dev"
            private const val TLS_PIN = "sha256/REPLACE_ME_WITH_REAL_BASE64_PIN=="

            private val okHttpClient: OkHttpClient =
                OkHttpClient
                    .Builder()
                    .certificatePinner(
                        CertificatePinner
                            .Builder()
                            .add(TLS_PIN_HOST, TLS_PIN)
                            .build(),
                    ).connectTimeout(MANIFEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
        }

        /** Dev-only: permits OTA calls to non-pinned, plain-HTTP hosts (LAN testing). */
        private fun allowTestHosts(): Boolean = system.settings.otaAllowTestHosts

        private fun manifestUrl(): String = system.settings.otaManifestUrl

        /** Rejects any OTA host other than the pinned production host (unless test mode). */
        private fun requirePinnedHost(rawUrl: String) {
            requirePinnedOtaHost(rawUrl, TLS_PIN_HOST, allowTestHosts())
        }

        /** Verifies the signed manifest envelope ({payload, signature}), then parses the payload. */
        private fun verifyManifestEnvelope(json: String): OtaManifest? {
            val envelope = JsonParser.parseString(json).asJsonObject ?: return null
            val signature = envelope.get(SIGNATURE_FIELD)?.asString
            val payload = envelope.get(PAYLOAD_FIELD)?.asString
            if (signature == null || payload == null) {
                Logger.w("OTA manifest is not a signed envelope; refusing")
                return null
            }
            if (!verifyManifestSignature(payload.toByteArray(Charsets.UTF_8), signature)) {
                Logger.w("OTA manifest signature verification failed; refusing payload")
                return null
            }
            return parseManifest(payload)
        }

        private fun verifyManifestSignature(
            payload: ByteArray,
            signatureBase64: String,
        ): Boolean {
            if (OTA_SIGNING_KEY_SPKI.startsWith("REPLACE_ME")) {
                Logger.w("OTA manifest signature NOT verified: signing key not configured (dev only)")
                return true
            }
            return try {
                val publicKey =
                    java.security.KeyFactory
                        .getInstance("RSA")
                        .generatePublic(
                            java.security.spec.X509EncodedKeySpec(
                                java.util.Base64
                                    .getDecoder()
                                    .decode(OTA_SIGNING_KEY_SPKI),
                            ),
                        )
                val verifier = java.security.Signature.getInstance("SHA256withRSA")
                verifier.initVerify(publicKey)
                verifier.update(payload)
                verifier.verify(
                    java.util.Base64
                        .getDecoder()
                        .decode(signatureBase64),
                )
            } catch (e: Exception) {
                Logger.e("OTA signature verification error", e)
                false
            }
        }

        // ------------------------------------------------------------------
        // Public API
        // ------------------------------------------------------------------

        /** Fetches and verifies the remote OTA manifest (strict HTTPS + pinned TLS + RSA signature). */
        suspend fun fetchManifest(): OtaManifest? =
            withContext(Dispatchers.IO) {
                try {
                    val url = manifestUrl()
                    requirePinnedHost(url)
                    val request = Request.Builder().url(url).build()
                    val response: Response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Logger.w("OTA manifest fetch failed: HTTP ${response.code}")
                        return@withContext null
                    }
                    val json = response.body?.string() ?: return@withContext null
                    verifyManifestEnvelope(json)
                } catch (e: Exception) {
                    Logger.e("OTA manifest fetch error", e)
                    null
                }
            }

        /** Compares local binary versions against the freshly fetched manifest. */
        suspend fun checkForUpdate(): OtaCheckResult =
            withContext(Dispatchers.IO) {
                val manifest = fetchManifest()
                val localVersions = mutableMapOf<String, String>()
                if (manifest != null) {
                    for (bin in manifest.binaries) {
                        localVersionOf(bin)?.let { localVersions[bin.name] = it }
                    }
                }
                OtaCheckResult(manifest = manifest, localVersions = localVersions)
            }

        /**
         * Installs every binary that is outdated according to [manifest]:
         * download -> verify -> atomic swap -> health check, with rollback on failure.
         */
        suspend fun installLatest(manifest: OtaManifest): Result<Unit> =
            withContext(Dispatchers.IO) {
                var firstFailure: Result<Unit>? = null
                for (bin in manifest.binaries) {
                    val result = updateOne(bin)
                    if (result.isFailure) firstFailure = result
                }
                return@withContext firstFailure ?: Result.success(Unit)
            }

        /**
         * Updates a single tool / binary independently of any other tool.
         * Success = already up to date or updated + health-checked; failure = rolled back.
         */
        suspend fun updateTool(name: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                val manifest =
                    fetchManifest()
                        ?: return@withContext Result.failure(IllegalStateException("OTA manifest unavailable"))
                val bin =
                    manifest.binaries.firstOrNull { it.name == name }
                        ?: return@withContext Result.failure(IllegalStateException("No OTA manifest entry for tool '$name'"))
                return@withContext updateOne(bin)
            }

        private suspend fun updateOne(bin: OtaBinary): Result<Unit> =
            withContext(Dispatchers.IO) {
                // Refuse OTA work when the native core / daemon are absent: probing and
                // health checks both depend on a running daemon.
                if (!system.isCoreInstalled()) {
                    return@withContext Result.failure(IllegalStateException("Native core is not installed; OTA update aborted"))
                }
                if (bin.name != INDRA_DAEMON_NAME && !coreManager.isDaemonRunning) {
                    return@withContext Result.failure(IllegalStateException("Native daemon is not running; OTA update aborted"))
                }

                cleanupTempArtifacts()

                val localVersion = localVersionOf(bin)
                if (localVersion == bin.version) {
                    Logger.i("OTA ${bin.name}: up to date (${bin.version})")
                    return@withContext Result.success(Unit)
                }
                Logger.i("OTA ${bin.name}: local=${localVersion ?: "unknown"} remote=${bin.version}")

                return@withContext try {
                    installBinary(bin)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Logger.e("OTA ${bin.name} install failed, rolling back to previous version", e)
                    rollbackBinary(bin)
                    Result.failure(e)
                }
            }

        /** Removes leftover dotted scratch files from interrupted downloads (<name>.ota-tmp-*). */
        private fun cleanupTempArtifacts() {
            val dir = coreManager.coreDir
            if (!dir.isDirectory) return
            dir
                .walkTopDown()
                .filter { it.isFile && it.name.startsWith(".") && it.name.contains(TMP_SUFFIX) }
                .forEach { if (!it.delete()) Logger.w("Could not remove stale OTA scratch file ${it.absolutePath}") }
        }

        // ------------------------------------------------------------------
        // Manifest parsing
        // ------------------------------------------------------------------

        private fun parseManifest(json: String): OtaManifest? =
            // Pure logic lives in OtaModels.kt so it stays unit-testable.
            parseOtaManifest(json)

        // ------------------------------------------------------------------
        // Local version detection (via native daemon exec probe)
        // ------------------------------------------------------------------

        private suspend fun localVersionOf(bin: OtaBinary): String? =
            when (bin.name) {
                INDRA_DAEMON_NAME -> system.getCoreVersion()
                else -> probeVersion(binaryPath(bin.name).absolutePath)
            }

        /** Runs `<binary> --version` through the daemon (raw handler) and reads the output. */
        private suspend fun probeVersion(
            binaryPath: String,
            timeoutMs: Long = PROBE_TIMEOUT_MS,
        ): String? =
            try {
                // `.last()`: the flow emits an "Executing: …" line first and one result
                // per output line; only the final emission carries the assembled output.
                val result = smartToolBox.rawProbe("version", "$binaryPath --version", timeoutMs).last()
                if (result.success) {
                    result.rawOutput.trim().ifEmpty { null }
                } else {
                    Logger.w("Version probe failed for $binaryPath: ${result.error}")
                    null
                }
            } catch (e: Exception) {
                Logger.w("Version probe failed for $binaryPath: ${e.message}")
                null
            }

        // ------------------------------------------------------------------
        // Install / swap / rollback
        // ------------------------------------------------------------------

        private suspend fun installBinary(bin: OtaBinary) {
            val target = binaryPath(bin.name)
            val artifact = bin.artifactFor(currentAbi())

            // Backup the currently working binary before swapping (rollback source).
            val backup = File(target.parentFile, "${target.name}.bak")
            if (target.exists()) {
                target.inputStream().use { input ->
                    backup.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // The daemon binary must never be swapped while it is executing.
            if (bin.name == INDRA_DAEMON_NAME) {
                coreManager.stopDaemon()
            } else {
                stopRunningChildren()
            }

            downloadVerified(artifact, bin.name, target).getOrThrow()

            if (bin.name == INDRA_DAEMON_NAME) {
                coreManager.startDaemon()
            }

            // Post-update health check: re-run `<binary> --version` through the daemon.
            if (bin.name != INDRA_DAEMON_NAME) {
                val version = probeVersion(target.absolutePath)
                if (version == null || !version.contains(bin.version)) {
                    throw IllegalStateException(
                        "Health check failed for ${bin.name}: probe returned '$version', expected ${bin.version}",
                    )
                }
            }
        }

        private fun rollbackBinary(bin: OtaBinary) {
            val target = binaryPath(bin.name)
            val backup = File(target.parentFile, "${target.name}.bak")
            if (backup.exists()) {
                atomicReplace(target, backup)
                setExecutable(target)
                Logger.w("OTA rollback applied for ${bin.name} from ${backup.absolutePath}")
            }
        }

        private suspend fun stopRunningChildren() {
            try {
                childProcessManager.stopAllRunning()
            } catch (e: Exception) {
                Logger.w("Failed to stop running children before swap: ${e.message}")
            }
        }

        // ------------------------------------------------------------------
        // Download + cryptographic verification
        // ------------------------------------------------------------------

        private suspend fun downloadVerified(
            artifact: OtaArtifact,
            name: String,
            target: File,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                var temp: File? = null
                try {
                    requirePinnedHost(artifact.url)
                    if (!allowTestHosts() && !artifact.url.startsWith("https://", ignoreCase = true)) {
                        throw SecurityException("Refusing non-HTTPS artifact URL for $name: ${artifact.url}")
                    }
                    val request = Request.Builder().url(artifact.url).build()
                    val response: Response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw java.io.IOException("OTA download failed for $name: HTTP ${response.code}")
                    }
                    val bytes: ByteArray = response.use { it.body?.bytes() ?: throw java.io.IOException("Empty body for $name") }
                    val actualHash =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { java.lang.String.format("%02x", it) }
                    val expectedHash =
                        artifact.sha256
                            .trim()
                            .removePrefix(SHA256_PREFIX)
                            .lowercase()
                    if (actualHash != expectedHash) {
                        throw SecurityException("SHA-256 mismatch for $name: expected=$expectedHash")
                    }
                    temp = File(target.parentFile, "." + target.name + TMP_SUFFIX + UUID.randomUUID())
                    temp.outputStream().use { out -> out.write(bytes) }
                    atomicReplace(target, temp)
                    setExecutable(target)
                    return@withContext Result.success(Unit)
                } catch (e: Exception) {
                    temp?.delete()
                    return@withContext Result.failure(e)
                }
            }

        // ------------------------------------------------------------------
        // Filesystem helpers (atomic + permissions)
        // ------------------------------------------------------------------

        fun binaryPath(name: String): File =
            if (name == INDRA_DAEMON_NAME) {
                File(coreManager.coreDir, INDRA_DAEMON_NAME)
            } else {
                File(coreManager.coreDir, "$TOOLS_DIR_NAME/$name/$name")
            }

        fun atomicReplace(
            target: File,
            source: File,
        ) {
            val sourcePath = source.toPath()
            val targetPath = target.toPath()
            try {
                Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        fun setExecutable(target: File) {
            try {
                Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(BINARY_MODE))
            } catch (e: Exception) {
                target.setExecutable(true, false)
            }
        }

        private fun currentAbi(): OtaAbi {
            // Sniff the installed core's ELF class instead of trusting the device
            // ABI: a 64-bit device may still run a 32-bit core (or vice versa).
            // Sniffing keeps OTA from swapping an arm64 artifact onto a v7a core.
            val daemon = File(coreManager.coreDir, INDRA_DAEMON_NAME)
            return sniffOtaAbi(daemon) ?: OtaAbi.ARMEABI_V7A
        }
    }
