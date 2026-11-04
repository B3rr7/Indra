package org.indra.android.core.data.bundle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.data.AppSettings
import org.indra.android.core.data.MsfDaemon
import org.indra.android.core.data.ToolManager
import org.indra.android.core.system.Logger
import org.indra.android.core.system.ScanRegistry
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime updates of both native bundles from GitHub releases. One releases
 * API call (60/h unauthenticated) serves the check of every channel.
 */
@Singleton
class BundleUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolManager: ToolManager,
    private val msfDaemon: MsfDaemon,
    private val scanRegistry: ScanRegistry,
    private val appSettings: AppSettings,
    private val downloader: BundleDownloader,
) {
    companion object {
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val env: BundleEnv = AndroidBundleEnv(context)

    private fun channel(id: String, label: String, prefix: String, spec: BundleSpec, hooks: BundleHooks) =
        BundleChannel(
            id, label, prefix, spec, toolManager.installer, downloader, appSettings, env,
            File(context.filesDir, "downloads"), scope, hooks,
        )

    val msf: BundleChannel = channel(
        "msf", "Metasploit", GitHubReleases.MSF_TAG_PREFIX, toolManager.msfSpec,
        BundleHooks.msfHooks(toolManager, msfDaemon, scanRegistry),
    )

    val tools: BundleChannel = channel(
        "tools", "Tools", GitHubReleases.TOOLS_TAG_PREFIX, toolManager.toolsSpec,
        BundleHooks.toolsHooks(toolManager, scanRegistry),
    )

    val channels: List<BundleChannel> = listOf(tools, msf)

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private var checkJob: Job? = null

    init {
        refreshInstalled()
    }

    fun refreshInstalled() = channels.forEach { it.refreshInstalled() }

    /** Installed tools bundle version ("legacy" for a pre-manifest install), or null. */
    fun installedToolsVersion(): String? = toolManager.installedToolsVersion()

    /** "APK" when the installed tools are this APK's copy, "downloaded" otherwise. */
    fun toolsSource(): String? = toolManager.toolsSource()

    /** Installed Metasploit bundle version, or null. */
    fun installedMsfVersion(): String? = toolManager.installedMsfVersion()

    /** Daily automatic check (app start). Silent; respects the Wi-Fi only setting; never downloads. */
    fun autoCheckIfDue() {
        val due = System.currentTimeMillis() - appSettings.bundleLastCheckMs >= CHECK_INTERVAL_MS
        if (!due || (appSettings.bundleWifiOnly && !env.isOnWifi())) return
        runCheck(silent = true)
    }

    fun checkForUpdates() = runCheck(silent = false)

    private fun runCheck(silent: Boolean) {
        synchronized(this) {
            if (checkJob?.isActive == true) return
            checkJob = scope.launch {
                _checking.value = true
                if (!silent) channels.forEach { it.markChecking() }
                try {
                    val json = env.fetchReleasesJson()
                    appSettings.bundleLastCheckMs = System.currentTimeMillis()
                    channels.forEach { it.applyCheck(json, silent) }
                } catch (e: java.io.IOException) {
                    Logger.w("BundleUpdateManager: releases check failed: ${e.message}")
                    if (!silent) channels.forEach { it.checkFailed("Update check failed: ${e.message}") }
                } finally {
                    _checking.value = false
                }
            }
        }
    }
}
