package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val system: IndraSystem,
    private val logger: Logger
) {
    companion object {
        private const val CORE_DOWNLOAD_URL = "https://github.com/indra/android/releases/latest/download/core-{platform}.zip"
    }

    data class CoreInfo(
        val version: String,
        val isInstalled: Boolean,
        val isInitialized: Boolean
    )

    fun getCoreInfo(): CoreInfo {
        val version = system.getCoreVersion()
        return CoreInfo(
            version = version ?: "Not installed",
            isInstalled = version != null,
            isInitialized = false // TODO: Check daemon status
        )
    }

    suspend fun downloadCore(platform: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL(CORE_DOWNLOAD_URL.replace("{platform}", platform))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val coreDir = File(system.corePath)
                coreDir.mkdirs()

                val zipFile = File(context.cacheDir, "core-$platform.zip")
                connection.inputStream.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // TODO: Extract zip to corePath
                logger.i("Core downloaded successfully")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Download failed: HTTP ${connection.responseCode}"))
            }.also {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logger.e("Core download failed", e)
            Result.failure(e)
        }
    }

    fun initializeCore(): Result<Unit> {
        return try {
            if (!system.isCoreInstalled()) {
                return Result.failure(Exception("Core not installed"))
            }

            // TODO: Start daemon and connect
            logger.i("Core initialized successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Core initialization failed", e)
            Result.failure(e)
        }
    }

    fun shutdownCore() {
        // TODO: Shutdown daemon
        logger.i("Core shutdown")
    }
}
