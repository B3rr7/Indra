package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.indra.android.core.client.IndraClient
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoreManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val system: IndraSystem,
        private val client: IndraClient,
        private val rootManager: RootManager,
        private val settings: SettingsRepository,
    ) {
        companion object {
            /** Version written to the core's VERSION file after a successful install. */
            const val CORE_VERSION = "1.0.1"

            /** Core archive bundled in assets (produced by `indra/jni/build.sh cores`). */
            private const val CORE_ASSET_PREFIX = "core-"

            /** Picks the bundled core archive matching this device's primary ABI. */
            private fun coreAssetName(): String {
                val abi =
                    android.os.Build.SUPPORTED_ABIS.firstOrNull { ab ->
                        ab == "arm64-v8a" || ab == "armeabi-v7a"
                    } ?: "armeabi-v7a"
                return "${CORE_ASSET_PREFIX}$abi.tar.xz"
            }

            /** Small password list bundled for hydra (the core ships none). */
            private const val WORDLIST_ASSET = "wordlists/common-passwords.txt"
            private const val WORDLIST_TARGET = "wordlists/common-passwords.txt"

            private const val SOCKET_NAME = "cSploitd.sock"
            private const val DAEMON_NAME = "indrad"

            /** Credentials shipped inside the core package's `users` file. */
            private const val DAEMON_USER = "android"
            private const val DAEMON_PASSWORD = "DEADBEEF"

            private const val NUCLEI_VERSION = "3.11.1"
            private const val NUCLEI_SHA256_ARM64 = "8044e3d9768ba0a744b2872c1a87e813006f013da97ca9f50f7661a4203bec07"
            private const val NUCLEI_SHA256_ARM = "22940b798dfe11aa1c67c9041c32916cec5ceaca132a6ec426e87c92282c64ae"

            private const val MSF_DIR_NAME = "msf"
            private const val EXPLOITDB_DIR_NAME = "exploitdb"
            private const val BETTERCAP_DIR_NAME = "bettercap"
        }

        data class CoreInfo(
            val version: String,
            val isInstalled: Boolean,
            val isInitialized: Boolean,
        )

        val coreDir: File get() = File(system.corePath)

        fun nucleiPath(): File = File(coreDir, "tools/nuclei/nuclei")

        fun isNucleiInstalled(): Boolean = nucleiPath().canExecute()

        fun msfPath(): File = File(coreDir, MSF_DIR_NAME)

        fun isMsfInstalled(): Boolean = File(msfPath(), "msfconsole").canExecute() || File(msfPath(), "msfconsole").exists()

        fun exploitdbPath(): File = File(coreDir, EXPLOITDB_DIR_NAME)

        fun isExploitdbInstalled(): Boolean =
            File(exploitdbPath(), "files_exploits.csv").exists() || exploitdbPath().listFiles()?.isNotEmpty() == true

        fun bettercapPath(): File = File(coreDir, "$BETTERCAP_DIR_NAME/bettercap")

        fun isBettercapInstalled(): Boolean = bettercapPath().canExecute()

        suspend fun installBettercap(url: String, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                if (url.isBlank()) error("Bettercap tarball URL is not configured in Settings")
                val targetDir = File(coreDir, BETTERCAP_DIR_NAME)
                if (!targetDir.exists() && !targetDir.mkdirs()) error("Cannot create bettercap dir")
                val target = bettercapPath()
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: ${response.code}")
                    val body = response.body ?: error("Empty response")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        val tmp = File(targetDir, "bettercap.tar.xz.tmp")
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var read: Int
                            var downloaded = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                            }
                        }
                        val hasContent = tmp.inputStream().use { raw ->
                            if (url.endsWith(".xz")) XZInputStream(raw).use { xz -> extractTar(xz, targetDir) }
                            else extractTar(raw, targetDir)
                        }
                        if (!hasContent) error("Bettercap archive is empty or unreadable")
                        tmp.delete()
                    }
                }
                target.setExecutable(true)
                Logger.i("Bettercap installed to ${target.absolutePath}")
            }
        }

        suspend fun installMsf(
            url: String,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (url.isBlank()) error("Metasploit chroot URL is not configured in Settings")
                    val target = msfPath()
                    if (!target.exists() && !target.mkdirs()) error("Cannot create msf directory")
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            val tmp = File(target, "msf.tar.xz.tmp")
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                                }
                            }
                            // Extract (supports .tar.xz)
                            val hasContent =
                                tmp.inputStream().use { raw ->
                                    if (url.endsWith(".xz")) {
                                        XZInputStream(raw).use { xz -> extractTar(xz, target) }
                                    } else {
                                        extractTar(raw, target)
                                    }
                                }
                            if (!hasContent) error("Metasploit archive is empty or unreadable")
                            tmp.delete()
                        }
                    }
                    Logger.i("Metasploit installed to ${target.absolutePath}")
                }
            }

        suspend fun installExploitdb(
            url: String,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (url.isBlank()) error("ExploitDB tarball URL is not configured in Settings")
                    val target = exploitdbPath()
                    if (!target.exists() && !target.mkdirs()) error("Cannot create exploitdb dir")
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            val tmp = File(target, "exploitdb.tar.xz.tmp")
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                                }
                            }
                            val hasContent =
                                tmp.inputStream().use { raw ->
                                    if (url.endsWith(".xz")) {
                                        XZInputStream(raw).use { xz -> extractTar(xz, target) }
                                    } else {
                                        extractTar(raw, target)
                                    }
                                }
                            if (!hasContent) error("ExploitDB archive is empty or unreadable")
                            tmp.delete()
                        }
                    }
                    Logger.i("ExploitDB installed to ${target.absolutePath}")
                }
            }

        suspend fun installNuclei(onProgress: (Float) -> Unit = {}): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val abi =
                        android.os.Build.SUPPORTED_ABIS
                            .firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "armeabi-v7a"
                    val isArm64 = abi == "arm64-v8a"
                    val url = "https://github.com/projectdiscovery/nuclei/releases/download/v$NUCLEI_VERSION/nuclei_${NUCLEI_VERSION}_linux_${if (isArm64) "arm64" else "arm"}.zip"
                    val expectedSha = if (isArm64) NUCLEI_SHA256_ARM64 else NUCLEI_SHA256_ARM

                    val targetDir = File(coreDir, "tools/nuclei")
                    if (!targetDir.exists() && !targetDir.mkdirs()) error("Cannot create nuclei dir")
                    val target = nucleiPath()

                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            val tmp = File(targetDir, "nuclei.zip.tmp")
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                                }
                            }
                            // Verify SHA-256
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            tmp.inputStream().use { s ->
                                val b = ByteArray(8192)
                                var r: Int
                                while (s.read(b).also { r = it } != -1) digest.update(b, 0, r)
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            if (!actual.equals(expectedSha, ignoreCase = true)) {
                                tmp.delete()
                                error("SHA-256 mismatch: $actual != $expectedSha")
                            }
                            // Extract the binary from the zip
                            java.util.zip.ZipInputStream(tmp.inputStream()).use { zis ->
                                var entry = zis.nextEntry
                                var found = false
                                while (entry != null) {
                                    if (entry.name == "nuclei" || entry.name.endsWith("/nuclei")) {
                                        target.outputStream().use { out -> zis.copyTo(out) }
                                        found = true
                                        break
                                    }
                                    entry = zis.nextEntry
                                }
                                if (!found) error("nuclei binary not found in archive")
                            }
                            tmp.delete()
                        }
                    }
                    target.setExecutable(true)
                    Logger.i("Nuclei $NUCLEI_VERSION installed to ${target.absolutePath}")
                }
            }

        val socketPath: String get() = File(coreDir, SOCKET_NAME).absolutePath
        val isDaemonRunning: Boolean
            get() = File(socketPath).exists() || (IndraClient.isAvailable() && client.isConnected())

        private val daemonMutex = kotlinx.coroutines.sync.Mutex()

        fun getCoreInfo(): CoreInfo {
            val version = system.getCoreVersion()
            return CoreInfo(
                version = version ?: "Not installed",
                isInstalled = version != null,
                isInitialized = client.isAuthenticated(),
            )
        }

        /**
         * Extracts the bundled core archive into [coreDir] and writes the VERSION file.
         */
        suspend fun installCore(): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = coreDir
                    if (!dir.exists() && !dir.mkdirs()) {
                        error("Cannot create core directory: ${dir.absolutePath}")
                    }

                    // The daemon keeps handler .so files mapped: overwriting them while it
                    // runs corrupts its in-memory handler table, so stop it first.
                    val wasRunning = isDaemonRunning
                    if (wasRunning) {
                        stopDaemon().onFailure {
                            Logger.w("Could not stop daemon before install: ${it.message}")
                        }
                    }

                    val hasContent =
                        context.assets.open(coreAssetName()).use { raw ->
                            XZInputStream(raw).use { xz -> extractTar(xz, dir) }
                        }
                    if (!hasContent) error("Core archive is empty or unreadable")

                    markExecutables(dir)
                    installWordlist(dir)
                    File(dir, "VERSION").writeText(CORE_VERSION)
                    Logger.i("Core $CORE_VERSION installed to ${dir.absolutePath}")

                    if (wasRunning) {
                        startDaemon().onFailure {
                            Logger.w("Daemon restart after install failed: ${it.message}")
                        }
                    }
                }
            }

        /**
         * Copies the bundled password list into the core directory so hydra has a
         * stable path to read (its default wordlist does not exist on Android).
         */
        private fun installWordlist(dir: File) {
            val target = File(dir, WORDLIST_TARGET)
            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    Logger.w("Cannot create wordlist directory ${parent.absolutePath}")
                    return
                }
            }
            runCatching {
                context.assets.open(WORDLIST_ASSET).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Logger.w("Cannot install wordlist: ${it.message}") }
        }

        /**
         * Starts the native daemon as root and authenticates the JNI client against it.
         */
        suspend fun startDaemon(): Result<Unit> =
            withContext(Dispatchers.IO) {
                daemonMutex.withLock {
                    runCatching {
                        if (!system.isCoreInstalled()) error("Core is not installed")
                        if (!File(coreDir, DAEMON_NAME).canExecute()) {
                            error("$DAEMON_NAME is missing or not executable")
                        }

                        if (File(socketPath).exists()) {
                            // Daemon may already be running; make sure the client is attached.
                            if (runCatching { connectAndLogin() }.isSuccess) return@runCatching

                            // Stale socket left behind by a dead daemon: remove it.
                            if (settings.rootlessMode || !rootManager.detectRoot().isRooted) {
                                File(socketPath).delete()
                            } else {
                                rootManager.detectRoot().suPath?.let { su ->
                                    ProcessBuilder(su, "-c", "rm -f ${shellQuote(socketPath)}")
                                        .redirectErrorStream(true)
                                        .start()
                                        .waitFor()
                                }
                            }
                        }

                        val rootless = settings.rootlessMode || !rootManager.detectRoot().isRooted
                        val command =
                            buildString {
                                append("cd ").append(shellQuote(coreDir.absolutePath))
                                append(" && LD_LIBRARY_PATH=").append(shellQuote(File(coreDir, "lib").absolutePath))
                                append(" ./").append(DAEMON_NAME)
                            }

                        val process =
                            if (rootless) {
                                // Run the daemon directly as the app user (no root).
                                ProcessBuilder("/system/bin/sh", "-c", command)
                                    .redirectErrorStream(true)
                                    .start()
                            } else {
                                val su = rootManager.detectRoot().suPath ?: error("Root access is not available")
                                ProcessBuilder(su, "-c", command)
                                    .redirectErrorStream(true)
                                    .start()
                            }
                        val output =
                            process.inputStream
                                .bufferedReader()
                                .use { it.readText() }
                                .trim()
                        val exitCode = process.waitFor()

                        if (exitCode != 0 || !File(socketPath).exists()) {
                            error("Daemon failed to start (exit=$exitCode)${if (output.isNotEmpty()) ": $output" else ""}")
                        }

                        connectAndLogin()
                        Logger.i("Native daemon started")
                    }
                }
            }

        /**
         * Attaches to an already-running daemon, starting it when the core is installed
         * but the socket is missing. Safe to call on app start.
         */
        suspend fun ensureDaemon(): Boolean =
            withContext(Dispatchers.IO) {
                if (!system.isCoreInstalled()) return@withContext false
                if (File(socketPath).exists()) {
                    val connected =
                        runCatching { connectAndLogin() }
                            .onFailure {
                                Logger.w("Attach to running daemon failed: ${it.message}")
                            }.isSuccess
                    if (connected) return@withContext true
                }
                startDaemon()
                    .onFailure { Logger.w("Daemon start failed: ${it.message}") }
                    .isSuccess
            }

        /**
         * Stops the daemon and detaches the client.
         */
        suspend fun stopDaemon(): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (client.isConnected()) {
                        try {
                            client.shutdown()
                        } catch (e: Throwable) {
                            Logger.w("Daemon shutdown request failed: ${e.message}")
                        }
                    }
                    client.disconnect()

                    rootManager.detectRoot().suPath?.let { su ->
                        val process =
                            ProcessBuilder(
                                su,
                                "-c",
                                "for p in \$(pidof $DAEMON_NAME); do kill \$p; done",
                            ).redirectErrorStream(true).start()
                        process.waitFor()
                    }

                    repeat(20) {
                        if (!File(socketPath).exists()) return@runCatching
                        Thread.sleep(100)
                    }
                    if (File(socketPath).exists()) error("Daemon did not stop")
                }
            }

        fun initializeCore(): Result<Unit> =
            runCatching {
                if (!system.isCoreInstalled()) error("Core not installed")
                Logger.i("Core initialized")
            }

        fun shutdownCore() {
            client.disconnect()
            Logger.i("Core shutdown")
        }

        /**
         * Connects the JNI client to the daemon socket and authenticates.
         * Must be called from a background thread.
         */
        private fun connectAndLogin() {
            if (!IndraClient.isAvailable()) error("Native client libraries are not loaded")
            if (!client.isConnected() && !client.connect(socketPath)) {
                error("Cannot connect to daemon socket at $socketPath")
            }
            if (!client.isAuthenticated() && !client.login(DAEMON_USER, DAEMON_PASSWORD)) {
                error("Daemon authentication failed")
            }
            if (!client.loadHandlers()) {
                Logger.w("Daemon did not report any handlers")
            }
        }

        private fun extractTar(
            input: InputStream,
            destination: File,
        ): Boolean {
            val root = destination.canonicalFile
            var extractedAny = false

            TarArchiveInputStream(input).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val target = File(root, entry.name).canonicalFile
                    if (target != root && !target.path.startsWith(root.path + File.separator)) {
                        error("Illegal path in core archive: ${entry.name}")
                    }

                    extractedAny = true

                    when {
                        entry.isDirectory -> {
                            if (!target.exists() && !target.mkdirs()) error("Cannot create ${target.path}")
                        }
                        entry.isSymbolicLink -> Logger.w("Skipping symlink in core archive: ${entry.name}")
                        else -> {
                            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Cannot create ${it.path}") }
                            target.outputStream().use { output -> tar.copyTo(output) }
                        }
                    }

                    entry = tar.nextEntry
                }
            }
            return extractedAny
        }

        private fun markExecutables(dir: File) {
            File(dir, DAEMON_NAME).setExecutable(true, false)
            File(dir, "known-issues").setExecutable(true, false)
            File(dir, "start_daemon.sh").setExecutable(true, false)
            File(dir, "tools")
                .walkTopDown()
                .filter { it.isFile }
                .forEach { it.setExecutable(true, false) }
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
