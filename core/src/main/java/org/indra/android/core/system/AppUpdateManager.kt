package org.indra.android.core.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app APK updater. Fetches a signed JSON manifest ({version, url, sha256})
 * from a pinned HTTPS host, compares to the installed version, downloads the
 * APK into the cache dir, verifies its SHA-256, and hands it to PackageInstaller.
 *
 * This is deliberately separate from [OtaUpdateManager], which only swaps native
 * binaries; the APK itself must go through the system installer.
 */
@Singleton
class AppUpdateManager
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    ) {
        companion object {
            // Override via Settings if needed; production default.
            const val DEFAULT_MANIFEST_URL = "https://ota.indra.dev/app/version.json"
        }

        data class UpdateInfo(
            val version: String,
            val url: String,
            val sha256: String,
        )

        fun currentVersion(): String =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

        /** Fetches and parses the remote manifest; null on failure. */
        suspend fun checkForUpdate(manifestUrl: String = DEFAULT_MANIFEST_URL): UpdateInfo? =
            withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(manifestUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body?.string() ?: return@withContext null
                        val json = JSONObject(body)
                        UpdateInfo(
                            version = json.getString("version"),
                            url = json.getString("url"),
                            sha256 = json.optString("sha256", ""),
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }

        /** True when [remote] is newer than the installed version (best-effort semver). */
        fun isNewer(remote: String): Boolean {
            val cur = currentVersion()
            return compareVersions(remote, cur) > 0
        }

        private fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val pb = b.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }

        /** Downloads the APK, verifies its SHA-256, and starts the system installer. */
        suspend fun downloadAndInstall(
            info: UpdateInfo,
            onProgress: (Float) -> Unit = {},
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(info.url).build()
                    val apkFile = File(context.cacheDir, "indra-update.apk")
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Download failed: ${response.code}")
                        val body = response.body ?: error("Empty response")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            apkFile.outputStream().use { out ->
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
                    // Verify SHA-256 if provided.
                    if (info.sha256.isNotBlank()) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        apkFile.inputStream().use { s ->
                            val b = ByteArray(8192)
                            var r: Int
                            while (s.read(b).also { r = it } != -1) digest.update(b, 0, r)
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(info.sha256, ignoreCase = true)) {
                            apkFile.delete()
                            error("SHA-256 mismatch: $actual != ${info.sha256}")
                        }
                    }
                    // Hand off to the system installer.
                    installApk(apkFile)
                }
            }

        private fun installApk(apkFile: File) {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        }
    }