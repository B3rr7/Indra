package org.indra.android.core.system

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App log: Logcat always, the on-disk file when file logging is enabled.
 *
 * **The file write is asynchronous.** `ChildProcessManager` (and therefore every tool's
 * output line) calls [d] from the JNI callback thread; it used to open a `FileWriter` and
 * build a `SimpleDateFormat` per line *on that thread*, so a `tcpdump`/radar capture
 * slowed event delivery down to the disk. Lines are now queued into a bounded channel and
 * written in batches by one background coroutine. A burst that overruns the queue drops
 * the **oldest** lines rather than blocking the caller — a lost debug line beats a stalled
 * tool. [e] still writes immediately: the lines that matter most are the ones written just
 * before a crash.
 */
object Logger {
    private const val TAG = "INDRA"
    private const val LOG_FILE_NAME = "indra.log"
    private const val MAX_LOG_BYTES = 512 * 1024 // 512 KB cap, then truncate
    private const val QUEUED_LINES = 512

    @Volatile
    private var debugEnabled = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var fileLoggingEnabled = false

    /** Serialises the writer coroutine against [clearLog]; also guards [formatter]. */
    private val fileLock = Any()

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private class Entry(
        val level: String,
        val message: String,
        val atMillis: Long,
    )

    private val queue = Channel<Entry>(QUEUED_LINES, BufferOverflow.DROP_OLDEST)

    init {
        // One writer for the process; drains the queue in batches so a burst is a single
        // file open instead of one per line.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (first in queue) {
                val batch = mutableListOf(first)
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch += next
                }
                appendToFile(batch)
            }
        }
    }

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
        val file = getLogFile() ?: return
        synchronized(fileLock) {
            runCatching { if (file.exists()) file.writeText("") }
        }
    }

    private fun writeToFile(level: String, message: String) {
        if (!fileLoggingEnabled || appContext == null) return
        val entry = Entry(level, message, System.currentTimeMillis())
        if (entry.level == "E") {
            // Errors are rare and the ones worth keeping: write them before the crash.
            appendToFile(listOf(entry))
        } else {
            queue.trySend(entry)
        }
    }

    private fun appendToFile(entries: List<Entry>) {
        val context = appContext ?: return
        val file = File(context.filesDir, "logs/$LOG_FILE_NAME")
        synchronized(fileLock) {
            try {
                file.parentFile?.mkdirs()
                // Truncate if too large to avoid unbounded growth.
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    file.writeText("")
                }
                FileWriter(file, true).use { writer ->
                    for (entry in entries) {
                        val ts = formatter.format(Date(entry.atMillis))
                        writer.append("[$ts] ${entry.level}: ${entry.message}\n")
                    }
                }
            } catch (e: Exception) {
                // Never let logging break the app.
            }
        }
    }
}
