package org.indra.android.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.indra.android.core.data.bundle.BundleInstaller
import org.indra.android.core.data.bundle.BundleManifest
import org.indra.android.core.data.bundle.BundleSpec
import org.indra.android.core.data.bundle.BundleVersions
import org.indra.android.core.system.Logger
import org.json.JSONException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Manages the native bundles (tools + msf) and their paths.
 * The tools bundle ships in the APK as the offline baseline and can be updated at runtime;
 * MSF is download-only. Downloads are handled by [BundleUpdateManager].
 */
@Suppress("TooManyFunctions")
@Singleton
class ToolManager @Inject constructor(
    @ApplicationContext private val context: Context,
    toolsExtractor: ToolsExtractor,
    rubyExtractor: RubyExtractor,
    val installer: BundleInstaller,
) {
    private val _state = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val state: StateFlow<ExtractionState> = _state

    val toolsSpec: BundleSpec = toolsExtractor.spec
    val msfSpec: BundleSpec = rubyExtractor.spec

    /** Serializes every stage/swap of the tools bundle (APK install vs downloaded update). */
    val toolsLock = Mutex()

    val toolsPath: String get() = context.filesDir.absolutePath + "/tools/"
    val rubyPath: String get() = context.filesDir.absolutePath + "/ruby/"
    val msfPath: String get() = context.filesDir.absolutePath + "/msf/"
    /** HOME of msfrpcd (holds ~/.msf4); outside ruby/ so bundle swaps keep user data. */
    val msfHomePath: String get() = context.filesDir.absolutePath + "/msf-home/"
    val corePath: String get() = context.filesDir.absolutePath

    fun isToolsInstalled(): Boolean = installer.sentinelsPresent(toolsSpec)

    fun isMsfInstalled(): Boolean = installer.sentinelsPresent(msfSpec)

    /** Installed version, [BundleVersions.LEGACY] for pre-manifest installs, null if not usable. */
    fun installedVersion(spec: BundleSpec): String? {
        if (!installer.sentinelsPresent(spec)) return null
        return installer.readInstalled(spec)?.version ?: BundleVersions.LEGACY
    }

    fun installedMsfVersion(): String? = installedVersion(msfSpec)

    fun installedToolsVersion(): String? = installedVersion(toolsSpec)

    /** Installed manifest if the bundle is usable; null when missing or legacy (no manifest). */
    fun installedManifest(spec: BundleSpec): BundleManifest? =
        if (installer.sentinelsPresent(spec)) installer.readInstalled(spec) else null

    private val apkToolsJson: String by lazy {
        context.assets.open(ToolsExtractor.ASSET_MANIFEST).bufferedReader().use { it.readText() }
    }

    /** Manifest of the tools bundle shipped in this APK. */
    fun apkToolsManifest(): BundleManifest = BundleManifest.parse(apkToolsJson)

    /** "APK" when the installed tools are this APK's copy, "downloaded" otherwise. */
    fun toolsSource(): String? {
        val installed = installedManifest(toolsSpec) ?: return installedToolsVersion()?.let { "APK" }
        val apk = runCatching { apkToolsManifest() }.getOrNull()
        return if (installed.sameContentAs(apk)) "APK" else "downloaded"
    }

    /**
     * Install the APK's tools bundle when missing, legacy, or older than the
     * APK copy (see [BundleVersions.shouldInstallApkCopy]: a downloaded bundle
     * newer than the APK is kept). [force] reinstalls the APK copy anyway.
     * Callers must make sure no tool is running.
     */
    suspend fun extractTools(force: Boolean = false) = withContext(Dispatchers.IO) {
        toolsLock.withLock {
            installer.cleanupLeftovers()
            _state.value = ExtractionState.Checking
            try {
                val json = apkToolsJson
                val manifest = BundleManifest.parse(json)
                val installed = installer.readInstalled(toolsSpec)
                if (!force && !BundleVersions.shouldInstallApkCopy(manifest, installed, isToolsInstalled())) {
                    _state.value = ExtractionState.AlreadyInstalled
                    return@withLock
                }
                Logger.i("ToolManager: installing tools ${manifest.version} (installed: ${installed?.version ?: installedToolsVersion()})")
                _state.value = ExtractionState.Extracting(0, "")
                context.assets.open(ToolsExtractor.ASSET_BUNDLE).use { input ->
                    installer.stage(toolsSpec, input, manifest, json) { _, files ->
                        if (files >= 0) _state.value = ExtractionState.Extracting(files, "")
                    }
                }
                _state.value = ExtractionState.PatchingConfig
                installer.swap(toolsSpec)
                _state.value = ExtractionState.Complete
                Logger.i("ToolManager: tools ${manifest.version} installed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failInstall(e)
            } catch (e: JSONException) {
                failInstall(e)
            } catch (e: IllegalArgumentException) {
                failInstall(e)
            }
        }
    }

    private fun failInstall(e: Exception) {
        Logger.e("ToolManager: tools install failed: ${e.message}")
        _state.value = ExtractionState.Error("Tools extraction failed: ${e.message}")
    }

    /** True when the Metasploit chroot is usable, else the fix. */
    suspend fun msfProblem(): String? {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.filesDir, "msf")
                if (!root.exists()) return@withContext "Metasploit chroot not found at $root"
                // Check if msfconsole exists
                val msfconsole = File(root, "msfconsole")
                if (!msfconsole.exists()) return@withContext "msfconsole not found in $root"
                // Check if ruby exists
                val ruby = File(root, "ruby/bin/ruby")
                if (!ruby.exists()) return@withContext "ruby not found in $root"
                null
            }.getOrElse { it.message }
        }
        return result
    }
}

sealed class ExtractionState {
    data object Idle : ExtractionState()
    data object Checking : ExtractionState()
    data class Extracting(val files: Int, val currentFile: String) : ExtractionState()
    data object PatchingConfig : ExtractionState()
    data object Complete : ExtractionState()
    data object AlreadyInstalled : ExtractionState()
    data class Error(val message: String) : ExtractionState()
}
