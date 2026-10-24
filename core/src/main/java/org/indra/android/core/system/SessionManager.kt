package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val SESSION_MAGIC = "IndraSession"
        }

        private val storageDir: File
            get() = context.getExternalFilesDir(null) ?: context.filesDir

        fun getAvailableSessions(): List<String> =
            storageDir
                .listFiles()
                ?.filter { it.extension == "dss" }
                ?.map { it.name }
                ?: emptyList()

        fun saveSession(
            name: String,
            data: String,
        ): File {
            val fileName = if (name.endsWith(".dss")) name else "$name.dss"
            val file = File(storageDir, fileName)
            FileOutputStream(file).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    gzip.write(data.toByteArray())
                }
            }
            return file
        }

        fun loadSession(name: String): String? {
            val fileName = if (name.endsWith(".dss")) name else "$name.dss"
            val file = File(storageDir, fileName)
            if (!file.exists()) return null

            return FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gzip ->
                    gzip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }

        fun deleteSession(name: String): Boolean {
            val fileName = if (name.endsWith(".dss")) name else "$name.dss"
            return File(storageDir, fileName).delete()
        }
    }
