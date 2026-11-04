package org.indra.android.core.data.bundle

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Downloads a bundle archive to disk, resuming a partial `.part` file when one exists. */
class BundleDownloader(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val COPY_BUFFER_BYTES = 65_536
        const val HTTP_PARTIAL = 206
        const val SUCCESS_MIN = 200
        const val SUCCESS_MAX = 299
    }

    /**
     * Download [url] to [dest], verifying the final size. [onProgress] receives 0..1.
     * A partial download is resumed with a `Range` header.
     */
    @Throws(IOException::class)
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Float) -> Unit) {
        var downloaded = if (dest.exists()) dest.length() else 0L
        if (downloaded >= expectedSize) {
            onProgress(1f)
            return
        }
        val partFile = File(dest.parentFile, dest.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode !in SUCCESS_MIN..SUCCESS_MAX) {
                throw IOException("HTTP $responseCode: ${conn.responseMessage}")
            }
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: expectedSize
            val total = if (responseCode == HTTP_PARTIAL) downloaded + contentLength else contentLength
            conn.inputStream.use { input ->
                FileOutputStream(partFile, downloaded > 0).use { output ->
                    downloaded = copy(input, output, downloaded, total, onProgress)
                }
            }
            if (expectedSize > 0 && downloaded != expectedSize) {
                throw IOException("size mismatch: downloaded $downloaded, expected $expectedSize")
            }
            Files.move(partFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        output: FileOutputStream,
        startBytes: Long,
        total: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        var downloaded = startBytes
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(downloaded / total.toFloat())
            read = input.read(buffer)
        }
        return downloaded
    }
}
