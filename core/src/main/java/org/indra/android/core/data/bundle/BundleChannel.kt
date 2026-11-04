package org.indra.android.core.data.bundle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.data.AppSettings
import org.indra.android.core.system.Logger
import org.json.JSONException
import java.io.File
import java.io.IOException

/** Update state machine of one bundle channel. */
sealed class BundleOp {
    data class Checking(val channelId: String) : BundleOp()
    data class UpdateAvailable(val channelId: String, val manifest: BundleManifest) : BundleOp()
    data class Downloading(val channelId: String, val progress: Float) : BundleOp()
    data class Verifying(val channelId: String) : BundleOp()
    data class Staging(val channelId: String, val files: Int) : BundleOp()
    data class PendingSwap(val channelId: String, val reason: String) : BundleOp()
    data class Swapping(val channelId: String) : BundleOp()
    data class Done(val channelId: String) : BundleOp()
    data class Error(val channelId: String, val message: String) : BundleOp()
    data class Idle(val channelId: String) : BundleOp()
}

/** Outcome of a check pass, kept separate so the state machine has one place to branch. */
private sealed class CheckOutcome {
    data object NoRelease : CheckOutcome()
    data object BadManifest : CheckOutcome()
    data object UpToDate : CheckOutcome()
    data class Available(val manifest: BundleManifest) : CheckOutcome()
    data class Failure(val message: String) : CheckOutcome()
}

/** Outcome of an install pass. */
private sealed class InstallOutcome {
    data object Done : InstallOutcome()
    data class Failed(val message: String) : InstallOutcome()
}

/**
 * One bundle (tools or msf): its installed state, its update state machine, and
 * the check/download/stage/swap pipeline. The signed manifest is verified before
 * anything is downloaded; staging re-checks the archive sha256 while streaming,
 * and the swap is atomic with rollback.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class BundleChannel(
    val id: String,
    val label: String,
    val tagPrefix: String,
    val spec: BundleSpec,
    val installer: BundleInstaller,
    val downloader: BundleDownloader,
    val appSettings: AppSettings,
    val env: BundleEnv,
    val downloadDir: File,
    val scope: CoroutineScope,
    val hooks: BundleHooks,
) {
    private companion object {
        const val SWAP_POLL_MS = 1000L
        const val DONE_LINGER_MS = 500L
    }

    private val _op = MutableStateFlow<BundleOp>(BundleOp.Idle(id))
    val op: StateFlow<BundleOp> = _op.asStateFlow()

    private var checkJob: Job? = null

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        scope.launch {
            _op.value = BundleOp.Checking(id)
            val installed = withContext(Dispatchers.IO) {
                runCatching { installer.readInstalled(spec) }.getOrNull()
            }
            _op.value = if (installed != null) BundleOp.Idle(id) else BundleOp.Error(id, "not installed")
        }
    }

    /** Called by [BundleUpdateManager] to mark the checking state. */
    fun markChecking() {
        _op.value = BundleOp.Checking(id)
    }

    /** Called by [BundleUpdateManager] after a shared release check completes. */
    fun applyCheck(json: String, silent: Boolean) {
        val outcome = try {
            evaluate(json)
        } catch (e: IOException) {
            CheckOutcome.Failure("check failed: ${e.message}")
        } catch (e: JSONException) {
            CheckOutcome.Failure("check failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            CheckOutcome.Failure("check failed: ${e.message}")
        }
        applyOutcome(outcome, silent)
    }

    private fun evaluate(json: String): CheckOutcome {
        val release = GitHubReleases.pickLatest(json, tagPrefix)
        val verified = release?.let { env.verifyManifest(it.manifest.url, it.signature.url) }
        return when {
            release == null -> CheckOutcome.NoRelease
            verified == null -> CheckOutcome.BadManifest
            else -> {
                val manifest = BundleManifest.parse(String(verified, Charsets.UTF_8))
                val installed = hooks.installedManifest()
                if (installed == null || BundleVersions.isNewer(manifest, installed)) {
                    CheckOutcome.Available(manifest)
                } else {
                    CheckOutcome.UpToDate
                }
            }
        }
    }

    private fun applyOutcome(outcome: CheckOutcome, silent: Boolean) {
        when (outcome) {
            is CheckOutcome.Available -> {
                _op.value = BundleOp.UpdateAvailable(id, outcome.manifest)
                appSettings.setAvailableManifest(id, outcome.manifest)
            }
            CheckOutcome.BadManifest -> {
                if (!silent) _op.value = BundleOp.Error(id, "manifest verification failed")
            }
            CheckOutcome.NoRelease -> {
                if (!silent) _op.value = BundleOp.Error(id, "no qualifying release")
            }
            CheckOutcome.UpToDate -> {
                if (!silent) _op.value = BundleOp.Idle(id)
            }
            is CheckOutcome.Failure -> {
                Logger.w("BundleChannel($id): ${outcome.message}")
                if (!silent) _op.value = BundleOp.Error(id, outcome.message)
            }
        }
    }

    /** Called when the shared check fails. */
    fun checkFailed(message: String) {
        _op.value = BundleOp.Error(id, message)
    }

    /** User-initiated check. */
    fun checkForUpdates() = runCheck(silent = false)

    private fun runCheck(silent: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            if (!silent) _op.value = BundleOp.Checking(id)
            try {
                val json = env.fetchReleasesJson()
                appSettings.bundleLastCheckMs = System.currentTimeMillis()
                applyCheck(json, silent)
            } catch (e: IOException) {
                Logger.w("BundleChannel($id): check failed: ${e.message}")
                if (!silent) _op.value = BundleOp.Error(id, "check failed: ${e.message}")
            }
        }
    }

    /** User taps Update. */
    fun installUpdate() {
        val manifest = (_op.value as? BundleOp.UpdateAvailable)?.manifest ?: return
        scope.launch { install(manifest) }
    }

    private suspend fun install(manifest: BundleManifest) {
        _op.value = BundleOp.Verifying(id)
        val outcome = try {
            performInstall(manifest)
        } catch (e: IOException) {
            InstallOutcome.Failed("install failed: ${e.message}")
        } catch (e: JSONException) {
            InstallOutcome.Failed("install failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            InstallOutcome.Failed("install failed: ${e.message}")
        }
        when (outcome) {
            is InstallOutcome.Failed -> _op.value = BundleOp.Error(id, outcome.message)
            InstallOutcome.Done -> {
                _op.value = BundleOp.Done(id)
                delay(DONE_LINGER_MS)
                refreshInstalled()
            }
        }
    }

    private suspend fun performInstall(manifest: BundleManifest): InstallOutcome {
        val release = GitHubReleases.pickLatest(env.fetchReleasesJson(), tagPrefix)
        val verified = release?.let { env.verifyManifest(it.manifest.url, it.signature.url) }
        val asset = release?.asset(manifest.file)
        return when {
            release == null || verified == null -> InstallOutcome.Failed("release vanished or signature mismatch")
            asset == null -> InstallOutcome.Failed("asset ${manifest.file} missing in release")
            else -> {
                download(asset, manifest)
                awaitSwapWindow()
                _op.value = BundleOp.Swapping(id)
                hooks.beforeSwap?.invoke()
                try {
                    withContext(Dispatchers.IO) { installer.swap(spec) }
                } finally {
                    hooks.afterSwap?.invoke()
                }
                appSettings.setAvailableManifest(id, null)
                InstallOutcome.Done
            }
        }
    }

    private suspend fun download(asset: ReleaseAsset, manifest: BundleManifest) {
        val dest = File(downloadDir, asset.name)
        _op.value = BundleOp.Downloading(id, 0f)
        try {
            downloader.download(asset.url, dest, asset.size) { progress ->
                _op.value = BundleOp.Downloading(id, progress)
            }
            _op.value = BundleOp.Staging(id, 0)
            withContext(Dispatchers.IO) {
                dest.inputStream().use { input ->
                    installer.stage(spec, input, manifest, manifest.toJson()) { _, files ->
                        if (files >= 0) _op.value = BundleOp.Staging(id, files)
                    }
                }
            }
        } finally {
            dest.delete()
        }
    }

    private suspend fun awaitSwapWindow() {
        hooks.swapBlocker()?.let { reason ->
            _op.value = BundleOp.PendingSwap(id, reason)
            while (hooks.swapBlocker() != null) delay(SWAP_POLL_MS)
        }
    }

    fun cancel() {
        checkJob?.cancel()
    }
}
