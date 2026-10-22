package org.indra.android.core.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
        private const val ACTION_REQUEST_ROOT = "com.topjohnwu.magisk.action.REQUEST_ROOT"
        private const val SUPERSU_PACKAGE = "eu.chainfire.supersu"
        private const val SUPERUSER_APK = "com.koushikdutta.superuser"
    }

    enum class RootType {
        MAGISK,
        SUPERSU,
        KERNELSU,
        UNKNOWN,
        NONE
    }

    data class RootStatus(
        val isRooted: Boolean,
        val rootType: RootType,
        val suPath: String?,
        val magiskVersion: String?,
        val kernelSuVersion: String?
    )

    fun detectRoot(): RootStatus {
        val suPath = findSuBinary()
        val isRooted = suPath != null && canExecuteSu(suPath)

        val rootType = when {
            isMagiskInstalled() -> RootType.MAGISK
            isKernelSUInstalled() -> RootType.KERNELSU
            isSuperSUInstalled() -> RootType.SUPERSU
            isRooted -> RootType.UNKNOWN
            else -> RootType.NONE
        }

        return RootStatus(
            isRooted = isRooted,
            rootType = rootType,
            suPath = suPath,
            magiskVersion = getMagiskVersion(),
            kernelSuVersion = getKernelSUVersion()
        )
    }

    fun requestRootAccess(): Boolean {
        return try {
            val intent = Intent(ACTION_REQUEST_ROOT)
                .setPackage(MAGISK_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun executeSu(command: String): ProcessResult {
        val suPath = findSuBinary() ?: return ProcessResult(exitCode = -1, stderr = "su binary not found")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            process.destroy()
            ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
        } catch (e: IOException) {
            ProcessResult(exitCode = -1, stderr = e.message ?: "IOException")
        } catch (e: InterruptedException) {
            ProcessResult(exitCode = -1, stderr = e.message ?: "InterruptedException")
        }
    }

    fun isMagiskInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MAGISK_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isKernelSUInstalled(): Boolean {
        // KernelSU detection: check for /data/adb/ksu directory or KernelSU manager app
        return File("/data/adb/ksu").exists() ||
                try {
                    context.packageManager.getPackageInfo("me.weishu.kernelsu", 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
    }

    fun isSuperSUInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SUPERSU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                context.packageManager.getPackageInfo(SUPERUSER_APK, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun getMagiskVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(MAGISK_PACKAGE, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun getKernelSUVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo("me.weishu.kernelsu", 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun findSuBinary(): String? {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/magisk/busybox",
            "/data/adb/ksu/bin/su"
        )

        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        // Try PATH
        return try {
            val process = Runtime.getRuntime().exec("which su")
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            process.destroy()
            if (output.isNotEmpty() && File(output).exists()) output else null
        } catch (e: Exception) {
            null
        }
    }

    private fun canExecuteSu(suPath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", "echo test"))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun getRootTypeDisplayName(): String {
        return when (detectRoot().rootType) {
            RootType.MAGISK -> "Magisk"
            RootType.KERNELSU -> "KernelSU"
            RootType.SUPERSU -> "SuperSU"
            RootType.UNKNOWN -> "Unknown root"
            RootType.NONE -> "No root"
        }
    }
}

data class ProcessResult(
    val exitCode: Int = -1,
    val pid: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val error: String = "",
    val output: String = ""
)
