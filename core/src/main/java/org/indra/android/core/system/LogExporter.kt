package org.indra.android.core.system

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports the debug log and the most recent crash logs as one zip, with SSID/BSSID/MAC/password
 * and IP values redacted first.
 *
 * The archive is written under `filesDir/output/logs/`, which is inside the FileProvider root
 * declared in `file_paths.xml`; writing it to the cache root instead would make
 * `getUriForFile` throw, because the cache provider root is deliberately narrowed to `updates/`.
 */
class LogExporter(private val context: Context) {
    private companion object {
        private const val AUTHORITY_SUFFIX = ".fileprovider"
        private const val REDACTED = "[REDACTED]"
        private const val MAX_CRASH_LOGS = 10
        private val SSID_PATTERN = "SSID[:=]\\s*[^\\s,;]+".toRegex()
        private val BSSID_PATTERN = "BSSID[:=]\\s*[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}".toRegex()
        private val MAC_PATTERN = "[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}".toRegex()
        private val PASSWORD_PATTERN = "(password|passwd|pwd|key|secret|token)[:=]\\s*[^\\s,;]+".toRegex()
        private val IP_PATTERN = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".toRegex()
    }

    /** Builds the zip and returns the shareable URI, or the cause of the first failure. */
    suspend fun exportLogs(): ExportResult<Uri> =
        withContext(Dispatchers.IO) {
            try {
                ExportResult.Success(writeArchive())
            } catch (e: IOException) {
                ExportResult.Failure(e)
            } catch (e: IllegalArgumentException) {
                // FileProvider refuses a file outside its configured roots with this.
                ExportResult.Failure(e)
            }
        }

    private fun writeArchive(): Uri {
        val outDir = File(File(context.filesDir, "output"), "logs").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(outDir, "indra-logs-$timestamp.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            writeEntry(zos, "debug.log", File(context.filesDir, "debug.log"))
            crashLogs().forEach { crash -> writeEntry(zos, "crashes/${crash.name}", crash) }
            writeEntry(zos, "manifest.txt", null, manifest())
        }

        return FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", zipFile)
    }

    /** The newest crash logs, or an empty list when the directory does not exist. */
    private fun crashLogs(): List<File> {
        val crashDir = File(context.filesDir, "crashes")
        if (!crashDir.isDirectory) return emptyList()
        return crashDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.take(MAX_CRASH_LOGS)
    }

    /** Adds one redacted file, or [text] when [source] is null or missing. */
    private fun writeEntry(zos: ZipOutputStream, name: String, source: File?, text: String? = null) {
        val body = text ?: source?.takeIf { it.isFile }?.readText() ?: return
        zos.putNextEntry(ZipEntry(name))
        zos.write(redact(body).toByteArray())
        zos.closeEntry()
    }

    private fun manifest(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("Indra Log Export")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App version: ${packageInfo.versionName}")
            appendLine("Android version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Redacted: SSID, BSSID, MAC addresses, passwords, IPs")
        }
    }

    /** Redacts sensitive information from log text. */
    private fun redact(text: String): String =
        text
            .replace(SSID_PATTERN) { "SSID=$REDACTED" }
            .replace(BSSID_PATTERN) { "BSSID=$REDACTED" }
            .replace(MAC_PATTERN) { REDACTED }
            .replace(PASSWORD_PATTERN) { "${it.groupValues[0].split("=")[0]}=$REDACTED" }
            .replace(IP_PATTERN) { REDACTED }
}

/** Outcome of a log export: the shareable [Success.value] URI, or the [Failure.error] cause. */
sealed class ExportResult<out T> {
    data class Success<T>(val value: T) : ExportResult<T>()
    data class Failure(val error: Exception) : ExportResult<Nothing>()

    /**
     * Runs [onSuccess] with the exported URI, or [onFailure] with the cause.
     *
     * Exists so call sites do not need a `when` over both branches; mirrors the helper on
     * `OtaModels.OtaResult`.
     */
    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (Exception) -> R): R =
        when (this) {
            is Success -> onSuccess(value)
            is Failure -> onFailure(error)
        }
}
