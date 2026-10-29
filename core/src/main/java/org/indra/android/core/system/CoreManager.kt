package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.indra.android.core.client.IndraClient
import org.indra.android.core.engine.AlpineManifest
import org.indra.android.core.engine.ChrootEngine
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
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
            const val CORE_VERSION = "1.0.2"

            /** Core archive bundled in assets (produced by `indra/jni/build.sh cores`). */
            private const val CORE_ASSET_PREFIX = "core-"

            /**
             * How long [startDaemon] waits for the `su` handshake before giving up.
             *
             * `indrad` double-forks: the parent exits once the child has bound the socket,
             * so a healthy start returns in well under a second. Without a bound, a `su`
             * that never answers (root prompt denied, hung Magisk) blocked the daemon mutex
             * — and therefore every later Start/Stop — forever.
             */
            private const val DAEMON_START_TIMEOUT_MS = 20_000L

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

            /**
             * Daemon password file. The archive ships an unusable placeholder; the app
             * writes a per-install credential here before the daemon starts (the daemon
             * only reads it at startup).
             */
            private const val USERS_FILE = "users"

            /** Entropy of the per-install daemon secret, in bytes. */
            private const val SECRET_BYTES = 32

            private const val NUCLEI_VERSION = "3.11.1"
            private const val NUCLEI_SHA256_ARM64 = "8044e3d9768ba0a744b2872c1a87e813006f013da97ca9f50f7661a4203bec07"
            private const val NUCLEI_SHA256_ARM = "22940b798dfe11aa1c67c9041c32916cec5ceaca132a6ec426e87c92282c64ae"

            private const val MSF_DIR_NAME = "msf"
            private const val EXPLOITDB_DIR_NAME = "exploitdb"
            private const val BETTERCAP_DIR_NAME = "bettercap"

            /** Rootfs directory name; version/URL/checksums live in `assets/alpine.json`. */
            private const val ALPINE_DIR_NAME = "alpine"
        }

        val coreDir: File get() = File(system.corePath)

        fun nucleiPath(): File = File(coreDir, "tools/nuclei/nuclei")

        fun isNucleiInstalled(): Boolean = nucleiPath().canExecute()

        fun gobusterPath(): File = File(coreDir, "tools/gobuster/gobuster")

        fun isGobusterInstalled(): Boolean = gobusterPath().canExecute()

        fun msfPath(): File = File(coreDir, MSF_DIR_NAME)

        fun isMsfInstalled(): Boolean = File(msfPath(), "msfconsole").canExecute() || File(msfPath(), "msfconsole").exists()

        fun exploitdbPath(): File = File(coreDir, EXPLOITDB_DIR_NAME)

        fun isExploitdbInstalled(): Boolean =
            File(exploitdbPath(), "files_exploits.csv").exists() || exploitdbPath().listFiles()?.isNotEmpty() == true

        fun bettercapPath(): File = File(coreDir, "$BETTERCAP_DIR_NAME/bettercap")

        fun isBettercapInstalled(): Boolean = bettercapPath().canExecute()

        fun alpinePath(): File = File(coreDir, ALPINE_DIR_NAME)

        fun isAlpineInstalled(): Boolean = File(alpinePath(), "bin/busybox").canExecute()

        /** Downloads + extracts the Alpine minirootfs (musl) for this device's ABI,
         *  giving the Terminal and the chroot-hosted tools a real Linux userspace.
         *
         *  The version, URL and per-arch checksums come from `assets/alpine.json`
         *  ([org.indra.android.core.engine.AlpineManifest]), so bumping the rootfs is an
         *  asset edit rather than a Kotlin change. Extraction restores mode bits and
         *  symlinks ([TarExtract]), which is what makes `apk` and `/bin/sh` work without
         *  the old whole-tree `chmod +x` sweep and hand-made symlink.
         *
         *  `resolv.conf` is deliberately **not** written here: the chroot engine writes it at
         *  use time from the active network's DNS servers (one writer, correct per network). */
        suspend fun installAlpine(onProgress: (Float) -> Unit = {}): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val manifest =
                        alpineManifest()
                            ?: error("The bundled Alpine description (assets/alpine.json) is missing or unusable")
                    val arch = alpineArch()
                    val expectedSha =
                        manifest.sha256For(arch)
                            ?: error("assets/alpine.json has no usable sha256 for $arch")
                    manifest.requireAppVersion(appVersionCode())?.let { error(it) }
                    val url = manifest.urlFor(arch)

                    val dir = alpinePath()
                    if (!dir.exists() && !dir.mkdirs()) error("Cannot create alpine directory")
                    // Wipe a partial install so extraction starts clean.
                    dir.listFiles()?.forEach { it.deleteRecursively() }

                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            error("Download failed (HTTP ${response.code}) for $url")
                        }
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        val tmp = File(coreDir, "alpine.tar.gz.tmp")
                        body.byteStream().use { input ->
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
                        }

                        val actual = sha256Of(tmp)
                        if (!actual.equals(expectedSha, ignoreCase = true)) {
                            tmp.delete()
                            error("SHA-256 mismatch for Alpine $arch: $actual != $expectedSha")
                        }

                        val hasContent =
                            tmp.inputStream().use { raw ->
                                GZIPInputStream(raw).use { gz -> TarExtract.extract(gz, dir) }
                            }
                        if (!hasContent) error("Alpine archive is empty or unreadable")
                        tmp.delete()
                    }

                    // Honest post-check instead of a blanket chmod: the extractor restored the
                    // archive's own modes, so a non-executable busybox means extraction lost
                    // them and every later `chroot` would fail with a confusing message.
                    val busybox = File(dir, "bin/busybox")
                    if (!busybox.canExecute()) {
                        error(
                            "Alpine extracted but ${busybox.absolutePath} is not executable — " +
                                "the archive's file modes were lost. Reinstall; if it repeats, the " +
                                "rootfs cannot be written on this device.",
                        )
                    }
                    Logger.i("Alpine ${manifest.version} ($arch) installed to ${dir.absolutePath}")
                }
            }

        /** The bundled rootfs description, or null when the asset is missing/corrupt. */
        fun alpineManifest(): AlpineManifest? =
            runCatching {
                context.assets.open(ChrootEngine.ALPINE_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()?.let { AlpineManifest.parse(it) }

        /** Alpine's own architecture name for the primary ABI. */
        fun alpineArch(): String =
            if (android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" } == "arm64-v8a") {
                "aarch64"
            } else {
                "armv7"
            }

        private fun appVersionCode(): Long =
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            }.getOrDefault(0L)

        private fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** True when the kernel/chipset can put [iface] into monitor mode (needed for
         *  airodump-ng-style capture). Stock Android rarely supports this; users with
         *  a USB WiFi dongle can load its driver via the Terminal and re-check. */
        fun isMonitorModeSupported(iface: String = "wlan0"): Boolean =
            runCatching {
                val root = rootManager.detectRoot()
                if (!root.isRooted || root.suPath == null) return@runCatching false
                // Read-only probe: `iw list` prints the supported interface modes per phy;
                // mutating `iw dev ... set type monitor` would disrupt the live connection.
                val out = ProcessBuilder(root.suPath!!, "-c", "iw list 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                out.contains("monitor", ignoreCase = true)
            }.getOrDefault(false)

        /** True when the running kernel exposes netfilter/iptables (userspace binary
         *  is always present on rooted devices, but containers/kernels may lack it). */
        fun isIptablesSupported(): Boolean =
            runCatching {
                val root = rootManager.detectRoot()
                val cmd =
                    if (root.isRooted && root.suPath != null) {
                        listOf(root.suPath!!, "-c", "iptables -L -n >/dev/null 2>&1")
                    } else {
                        listOf("iptables", "-L", "-n")
                    }
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)

        /**
         * Shared download-and-extract pipeline for the optional tarball
         * artifacts (Bettercap, MSF chroot, ExploitDB): download into a temp
         * file inside [dir], extract `.tar.xz` (or plain tar) with the
         * path-traversal guard, clean up the temp file, optionally mark
         * [executable] runnable. Failure = incomplete/empty archive or bad URL;
         * partial extractions remain but the artifact availability checks
         * (e.g. `isBettercapInstalled`) keep the cards honest.
         */
        private suspend fun installTarballArtifact(
            title: String,
            url: String,
            dir: File,
            tmpName: String,
            onProgress: (Float) -> Unit,
            urlErrorMessage: String = "$title tarball URL is not configured in Settings",
            executable: File? = null,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (url.isBlank()) error(urlErrorMessage)
                    if (!dir.exists() && !dir.mkdirs()) error("Cannot create ${dir.name} directory")
                    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            val tmp = File(dir, tmpName)
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
                                    if (url.endsWith(".xz")) XZInputStream(raw).use { xz -> TarExtract.extract(xz, dir) }
                                    else TarExtract.extract(raw, dir)
                                }
                            if (!hasContent) error("$title archive is empty or unreadable")
                            tmp.delete()
                        }
                    }
                    executable?.setExecutable(true)
                    Logger.i("$title installed to ${dir.absolutePath}")
                }
            }

        suspend fun installBettercap(
            url: String,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            installTarballArtifact(
                title = "Bettercap",
                url = url,
                dir = File(coreDir, BETTERCAP_DIR_NAME),
                tmpName = "bettercap.tar.xz.tmp",
                onProgress = onProgress,
                executable = bettercapPath(),
            )

        suspend fun installMsf(
            url: String,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            installTarballArtifact(
                title = "Metasploit",
                url = url,
                dir = msfPath(),
                tmpName = "msf.tar.xz.tmp",
                onProgress = onProgress,
                urlErrorMessage = "Metasploit chroot URL is not configured in Settings",
            )

        suspend fun installExploitdb(
            url: String,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            installTarballArtifact(
                title = "ExploitDB",
                url = url,
                dir = exploitdbPath(),
                tmpName = "exploitdb.tar.xz.tmp",
                onProgress = onProgress,
            )

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

        suspend fun installGobuster(url: String, onProgress: (Float) -> Unit = {}): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (url.isBlank()) error("Gobuster binary URL is not configured in Settings")
                    val targetDir = File(coreDir, "tools/gobuster")
                    if (!targetDir.exists() && !targetDir.mkdirs()) error("Cannot create gobuster dir")
                    val target = gobusterPath()
                    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            target.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                                }
                            }
                        }
                    }
                    target.setExecutable(true)
                    Logger.i("Gobuster installed to ${target.absolutePath}")
                }
            }

        val socketPath: String get() = File(coreDir, SOCKET_NAME).absolutePath
        val isDaemonRunning: Boolean
            get() = File(socketPath).exists() || (IndraClient.isAvailable() && client.isConnected())

        private val daemonMutex = kotlinx.coroutines.sync.Mutex()

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
                            XZInputStream(raw).use { xz -> TarExtract.extract(xz, dir) }
                        }
                    if (!hasContent) error("Core archive is empty or unreadable")

                    markExecutables(dir)
                    installWordlist(dir)
                    installGobusterBinary(dir)
                    installDirWordlist(dir)
                    // Replace the archive's placeholder credential with this install's.
                    writeUsersFile(dir)
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

        private fun installGobusterBinary(dir: File) {
            val targetDir = File(dir, "tools/gobuster")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Logger.w("Cannot create gobuster directory ${targetDir.absolutePath}")
                return
            }
            val target = File(targetDir, "gobuster")
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "armeabi-v7a"
            val assetName = "gobuster/$abi".replace("arm64-v8a","arm64-v8a").let {
                if (abi == "arm64-v8a") "gobuster/gobuster-arm64-v8a" else "gobuster/gobuster-armeabi-v7a"
            }
            runCatching {
                context.assets.open(assetName).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setExecutable(true, false)
            }.onFailure { Logger.w("Cannot install gobuster binary: ${it.message}") }
        }

        private fun installDirWordlist(dir: File) {
            val target = File(dir, "wordlists/common-dirs.txt")
            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    Logger.w("Cannot create wordlist directory ${parent.absolutePath}")
                    return
                }
            }
            runCatching {
                context.assets.open("wordlists/common-dirs.txt").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Logger.w("Cannot install dir wordlist: ${it.message}") }
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

                        // The daemon reads `users` once, at startup, so the credential has
                        // to be on disk before it is created.
                        writeUsersFile()

                        if (File(socketPath).exists()) {
                            // Daemon may already be running; make sure the client is attached.
                            if (runCatching { connectAndLogin() }.isSuccess) return@runCatching

                            // Attach failed: the running daemon predates a credential change
                            // (or died and left its socket behind). Kill it and start a fresh
                            // one so the file written above is the one that is loaded.
                            Logger.w("Attach to running daemon failed, restarting it")
                            killDaemon()
                            File(socketPath).delete()
                        }

                        // One probe for the whole start. detectRoot() execs `su`, and this
                        // block used to run it up to three times (which is 3× the bound on
                        // a manager that parks the call on a grant dialog).
                        val root = resolveDaemonPrivilege()
                        val rootless = root == null || !root.isRooted
                        val command =
                            buildString {
                                append("cd ").append(shellQuote(coreDir.absolutePath))
                                append(" && LD_LIBRARY_PATH=").append(shellQuote(File(coreDir, "lib").absolutePath))
                                // The daemon only accepts connections from this uid
                                // (SO_PEERCRED); it is normally root while we are not.
                                append(" INDRA_ALLOWED_UID=").append(android.os.Process.myUid())
                                append(" ./").append(DAEMON_NAME)
                            }

                        val process =
                            if (rootless) {
                                // Run the daemon directly as the app user (no root).
                                ProcessBuilder("/system/bin/sh", "-c", command)
                                    .redirectErrorStream(true)
                                    .start()
                            } else {
                                val su = root?.suPath ?: error("Root access is not available")
                                ProcessBuilder(su, "-c", command)
                                    .redirectErrorStream(true)
                                    .start()
                            }
                        // Bounded handshake: read the child's output and wait for it with a
                        // deadline instead of blocking the daemon mutex forever (see
                        // DAEMON_START_TIMEOUT_MS). runInterruptible makes the blocking
                        // stream read/`waitFor` cancellable by the timeout.
                        val captured = StringBuilder()
                        val exitCode =
                            withTimeoutOrNull(DAEMON_START_TIMEOUT_MS) {
                                runInterruptible(Dispatchers.IO) {
                                    process.inputStream.bufferedReader().use { reader ->
                                        reader.forEachLine { captured.appendLine(it) }
                                    }
                                }
                                runInterruptible(Dispatchers.IO) { process.waitFor() }
                            }

                        if (exitCode == null) {
                            process.destroyForcibly()
                            val partial = captured.toString().trim()
                            error(
                                "Daemon did not start within ${DAEMON_START_TIMEOUT_MS}ms" +
                                    (if (partial.isNotEmpty()) ": $partial" else ""),
                            )
                        }
                        val output = captured.toString().trim()

                        if (exitCode != 0 || !File(socketPath).exists()) {
                            error("Daemon failed to start (exit=$exitCode)${if (output.isNotEmpty()) ": $output" else ""}")
                        }

                        connectAndLogin()
                        Logger.i("Native daemon started")
                    }
                }
            }

        /**
         * Root decision for a daemon start: null means "run the daemon as this app user".
         *
         * A refused grant throws instead of silently falling back to rootless: the rootless
         * daemon cannot SYN-scan, capture packets or MITM, so the misconfiguration would
         * surface much later as an unrelated tool error. A device with no root at all keeps
         * the rootless fallback, because that is all it can do.
         */
        private fun resolveDaemonPrivilege(): RootManager.RootStatus? {
            if (settings.rootlessMode) return null
            val root = rootManager.detectRoot()
            if (root.suAccess == RootAccess.SuAccess.DENIED) {
                val fix = rootManager.guidance(root) ?: "Grant Indra root access."
                error("$fix To run without root instead, turn on rootless mode in Settings.")
            }
            return root
        }

        /**
         * Re-attaches the JNI client to a daemon that is already running — it never starts one.
         *
         * `connected`, `authenticated` and `startFailed` are **client-side** flags, and they go
         * stale: the native reader thread exits when the peer drops (or one command is refused),
         * and nothing but an app restart used to clear that. Every tool gate then answered
         * "daemon not running" while `indrad` was perfectly healthy (observed twice in one
         * sweep). The socket-file check keeps this cheap and silent when the daemon is really
         * gone, so a gate never triggers a `su` prompt.
         */
        suspend fun reconnect(): Boolean =
            withContext(Dispatchers.IO) {
                if (!File(socketPath).exists()) return@withContext false
                runCatching { connectAndLogin() }
                    .onFailure { Logger.w("Reconnect failed: ${it.message}") }
                    .isSuccess
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

                    killDaemon()

                    if (File(socketPath).exists()) error("Daemon did not stop")
                }
            }

        /**
         * Sends SIGTERM to every running daemon process and waits for the socket to go
         * away. Shared by [stopDaemon] and by [startDaemon]'s failed-attach path.
         *
         * The JNI client's `connected` flag is deliberately not consulted here: it goes
         * stale when the peer drops, and killing a healthy daemon is harmless.
         */
        private suspend fun killDaemon() {
            val root = if (settings.rootlessMode) null else rootManager.detectRoot()
            val rootless = root == null || !root.isRooted
            val killScript = "for p in \$(pidof $DAEMON_NAME); do kill \$p; done"

            // Root mode needs `su` (the daemon is another uid); rootless runs it as
            // this app user, which may simply kill its own processes.
            val command: List<String> =
                root?.suPath
                    ?.takeIf { !rootless }
                    ?.let { listOf(it, "-c", killScript) }
                    ?: listOf("/system/bin/sh", "-c", killScript)

            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                // A `su` that never answers must not hold the daemon mutex forever; the
                // socket probe below is the authority on whether it stopped.
                process.inputStream.close()
                val finished =
                    withTimeoutOrNull(DAEMON_START_TIMEOUT_MS) {
                        runInterruptible(Dispatchers.IO) { process.waitFor() }
                    }
                if (finished == null) process.destroyForcibly()
            }.onFailure { Logger.w("Could not signal the daemon: ${it.message}") }

            repeat(20) {
                if (!File(socketPath).exists()) return
                Thread.sleep(100)
            }
        }

        /**
         * Per-install daemon secret: 32 random bytes, generated once and kept in
         * app-private preferences. Never shipped in the APK or the core archive.
         */
        private fun daemonSecret(): String {
            settings.daemonSecret?.takeIf { it.length >= SECRET_BYTES * 2 }?.let { return it }

            val bytes = ByteArray(SECRET_BYTES)
            java.security.SecureRandom().nextBytes(bytes)
            val secret = bytes.joinToString("") { "%02x".format(it) }

            settings.daemonSecret = secret
            return secret
        }

        /** What the daemon expects from [org.indra.android.core.client.IndraClient.login]. */
        private fun daemonCredential(): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(daemonSecret().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Writes the daemon's `users` file with the current credential, owner-readable
         * only. The archive ships a placeholder, so this must run before the daemon is
         * started (it only reads the file at startup).
         */
        private fun writeUsersFile(dir: File = coreDir) {
            if (!dir.exists() && !dir.mkdirs()) {
                error("Cannot create core directory: ${dir.absolutePath}")
            }

            val users = File(dir, USERS_FILE)
            val expected = "$DAEMON_USER:${daemonCredential()}\n"

            if (!users.isFile || users.readText() != expected) {
                users.writeText(expected)
            }

            users.setReadable(false, false)
            users.setReadable(true, true)
            users.setWritable(false, false)
            users.setWritable(true, true)
            users.setExecutable(false, false)
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
            if (!client.isAuthenticated() && !client.login(DAEMON_USER, daemonCredential())) {
                error("Daemon authentication failed")
            }
            // Fresh socket + auth: whatever a previous start failure meant, the daemon answers now.
            client.noteStartResult(true)
            if (!client.loadHandlers()) {
                Logger.w("Daemon did not report any handlers")
            }
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
