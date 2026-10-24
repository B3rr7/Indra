package org.indra.android.core.system

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val TAG = "INDRA"
    private const val LOG_FILE_NAME = "indra.log"
    private const val MAX_LOG_BYTES = 512 * 1024 // 512 KB cap, then truncate

    @Volatile
    private var debugEnabled = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var fileLoggingEnabled = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun setFileLogging(enabled: Boolean) {
        fileLoggingEnabled = enabled
        if (enabled) {
            // Ensure the log file exists so the viewer has something to show.
            getLogFile()?.let { f ->
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                }
            }
        }
    }

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, message)
        writeToFile("D", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        writeToFile("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        writeToFile("W", message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
        writeToFile("E", message)
    }

    fun e(
        message: String,
        throwable: Throwable,
    ) {
        Log.e(TAG, message, throwable)
        writeToFile("E", "$message\n${throwable.stackTraceToString()}")
    }

    /** Returns the log file if file logging is (or was) enabled, else null. */
    fun getLogFile(): File? = appContext?.let { File(it.filesDir, "logs/$LOG_FILE_NAME") }

    /** Clears the on-disk log. */
    fun clearLog() {
        getLogFile()?.let { f ->
            runCatching { if (f.exists()) f.writeText("") }
        }
    }

    private fun writeToFile(level: String, message: String) {
        if (!fileLoggingEnabled) return
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, "logs/$LOG_FILE_NAME")
        try {
            file.parentFile?.mkdirs()
            // Truncate if too large to avoid unbounded growth.
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.writeText("")
            }
            FileWriter(file, true).use { w ->
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                w.append("[$ts] $level: $message\n")
            }
        } catch (e: Exception) {
            // Never let logging break the app.
        }
    }
}