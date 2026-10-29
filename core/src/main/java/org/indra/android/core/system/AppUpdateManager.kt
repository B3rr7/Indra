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
import org.indra.android.core.BuildConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app APK updater. Fetches the app manifest over HTTPS, compares it to the installed
 * version, downloads the APK into the cache dir, verifies its SHA-256, and hands it to the
 * system installer.
 *
 * The manifest goes through the **same** [SignedEnvelope] trust model as the native-binary
 * OTA ([OtaUpdateManager]): `{payload, signature}` verified SHA256withRSA against the key
 * provisioned at build time. A bare (unsigned) manifest is refused unless this build is
 * explicitly in test-host mode — before this, the APK path trusted HTTPS alone while the
 * binary path demanded a signature, so the weaker door was the one that ships an APK.
 * The SHA-256 of the APK remains mandatory, and the system installer still checks the APK
 * signature before replacing the app.
 *
 * This is deliberately separate from [OtaUpdateManager], which only swaps native
 * binaries; the APK itself must go through the system installer.
 */
@Singleton
class AppUpdateManager
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
    ) {
        companion object {
            // Override via Settings if needed; production default.
            const val DEFAULT_MANIFEST_URL = "https://ota.indra.dev/app/version.json"

            /** Length of a hex-encoded SHA-256 digest. */
            private const val SHA256_HEX_LENGTH = 64
            private const val HEX_DIGITS = "0123456789abcdef"

            /** Same build-time key as the binary OTA, so one signature model, one secret. */
            private val SIGNING_KEY_SPKI: String = BuildConfig.OTA_SIGNING_KEY_SPKI
        }

        data class UpdateInfo(
            val version: String,
            val url: String,
            val sha256: String,
        )

        /**
         * Outcome of a manifest fetch. [Refused] carries why the manifest was not trusted
         * (bad signature, unsigned build, missing hash) so Settings can say it — "check
         * failed" for all three hides the one case the user can fix.
         */
        sealed interface Check {
            data class Found(val info: UpdateInfo) : Check

            data class Refused(val reason: String) : Check

            data object Unreachable : Check
        }

        fun currentVersion(): String =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

        /** Fetches, authenticates and parses the remote manifest. */
        suspend fun checkForUpdate(manifestUrl: String = DEFAULT_MANIFEST_URL): Check =
            withContext(Dispatchers.IO) {
                val body =
                    try {
                        val client = OkHttpClient()
                        val request = Request.Builder().url(manifestUrl).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Logger.w("App update manifest fetch failed: HTTP ${response.code}")
                                return@withContext Check.Unreachable
                            }
                            response.body?.string()
                        }
                    } catch (e: IOException) {
                        Logger.w("App update manifest fetch failed: ${e.message}")
                        null
                    }
                if (body == null) return@withContext Check.Unreachable

                val payload = authenticate(body) ?: return@withContext Check.Unreachable
                parseManifest(payload)
            }

        /**
         * Returns the trusted manifest JSON, or null with a logged reason.
         *
         * An envelope is verified with [SignedEnvelope]; a bare body is only accepted in
         * test-host mode, because a plain manifest has nothing to authenticate it but TLS.
         */
        @Suppress("ReturnCount")
        private fun authenticate(body: String): String? {
            val envelope = SignedEnvelope.parse(body)
            if (envelope == null) {
                if (!settings.otaAllowTestHosts) {
                    Logger.e("App update manifest is not a signed envelope; refusing")
                    return null
                }
                Logger.w("App update manifest is unsigned (test-host mode): no signature check")
                return body
            }
            val verdict = SignedEnvelope.verify(envelope.payloadBytes(), envelope.signatureBase64, SIGNING_KEY_SPKI)
            if (!SignedEnvelope.accepts(verdict, settings.otaAllowTestHosts)) {
                Logger.e("App update manifest refused: signature verdict $verdict")
                return null
            }
            if (verdict == SignedEnvelope.Verdict.UNVERIFIED_DEV) {
                Logger.w("App update manifest signature NOT verified: no signing key in this build (test-host mode)")
            }
            return envelope.payload
        }

        private fun parseManifest(json: String): Check =
            try {
                val obj = JSONObject(json)
                val sha = obj.getString("sha256").trim().lowercase()
                if (sha.length != SHA256_HEX_LENGTH || sha.any { it !in HEX_DIGITS }) {
                    // Refused here rather than at download time: a manifest we cannot
                    // verify is not "an update available", it is a broken manifest.
                    Check.Refused("Update manifest has no valid sha256; refusing an unverifiable download")
                } else {
                    Check.Found(
                        UpdateInfo(
                            version = obj.getString("version"),
                            url = obj.getString("url"),
                            sha256 = sha,
                        ),
                    )
                }
            } catch (e: JSONException) {
                Logger.w("App update manifest is missing fields: ${e.message}")
                Check.Unreachable
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
                    require(info.url.startsWith("https://", ignoreCase = true)) {
                        "Update URL must be HTTPS; refusing ${info.url}"
                    }
                    val expectedSha256 = info.sha256.trim().lowercase()
                    require(expectedSha256.length == SHA256_HEX_LENGTH && expectedSha256.all { it in HEX_DIGITS }) {
                        "Manifest sha256 is missing or malformed; refusing an unverifiable update"
                    }

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
                    // Always verify: the hash is the only integrity check on the APK bytes.
                    val digest = MessageDigest.getInstance("SHA-256")
                    apkFile.inputStream().use { s ->
                        val b = ByteArray(8192)
                        var r: Int
                        while (s.read(b).also { r = it } != -1) digest.update(b, 0, r)
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expectedSha256) {
                        apkFile.delete()
                        error("SHA-256 mismatch: $actual != $expectedSha256")
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
