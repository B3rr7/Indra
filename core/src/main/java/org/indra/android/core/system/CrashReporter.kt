package org.indra.android.core.system

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashReporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val dateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getDefault()
            }

        fun report(throwable: Throwable) {
            try {
                val logDir = File(context.filesDir, "crashes")
                logDir.mkdirs()

                val timestamp = dateFormat.format(Date())
                val file = File(logDir, "crash-${System.currentTimeMillis()}.log")

                FileWriter(file).use { writer ->
                    writer.write("=== Indra Crash Report ===\n")
                    writer.write("Time: $timestamp\n")
                    writer.write("App Version: ${getAppVersion()}\n")
                    writer.write("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    writer.write("ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n")
                    writer.write("\n--- Stack Trace ---\n")

                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    writer.write(sw.toString())
                }
            } catch (e: Exception) {
                Logger.e("Failed to write crash report", e)
            }
        }

        private fun getAppVersion(): String =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
    }
