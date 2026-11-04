package org.indra.android

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.indra.android.core.client.IndraClient
import org.indra.android.core.data.bundle.BundleUpdateManager
import org.indra.android.core.system.CrashReporter
import org.indra.android.core.system.Logger
import org.indra.android.core.system.SettingsRepository
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class IndraApplication : Application() {
    /** Process-lifetime scope for one-shot startup work that must stay off the main thread. */
    private val startupJob = SupervisorJob()
    private val startupScope = CoroutineScope(startupJob + Dispatchers.IO)

    /**
     * Resolved lazily inside [startupScope]: building the bundle system touches `filesDir`
     * (disk), which must not happen on Main during `super.onCreate()`.
     */
    @Inject
    lateinit var bundleUpdateManager: Provider<BundleUpdateManager>

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installStrictMode()
        Logger.init(this)
        Logger.setDebugEnabled(BuildConfig.DEBUG)

        // Both steps below touch disk, so they must not run on Main (StrictMode flags them and a
        // cold start pays for it): reading SharedPreferences creates/parses the prefs file, and
        // enabling file logging creates the log file. The flag only decides where later lines
        // go, so a few milliseconds of deferred configuration costs nothing.
        startupScope.launch {
            applyLoggingPreferences()
            // `System.loadLibrary` reads and maps two .so files (~100 ms). Whichever thread
            // first touches IndraClient pays that, and it used to be Main (Hilt injecting
            // CoreManager/SmartToolBox into MainActivity or a ViewModel). Warm it here instead;
            // later touches see an initialized class.
            IndraClient.isAvailable()
            // Background bundle check: at most once a day, Wi-Fi-only when the user asked for it,
            // never downloads. A failure is silent here — Settings surfaces it on demand.
            runCatching { bundleUpdateManager.get().autoCheckIfDue() }
        }

        installCrashHandler()
    }

    override fun onTerminate() {
        startupJob.cancel()
        super.onTerminate()
    }

    private fun applyLoggingPreferences() {
        val prefs = getSharedPreferences(SettingsRepository.PREFS_NAME, MODE_PRIVATE)
        Logger.setFileLogging(prefs.getBoolean(SettingsRepository.KEY_FILE_LOGGING, false))
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashReporter(applicationContext).report(throwable)
            } catch (e: Exception) {
                // A crash reporter that throws would swallow the original crash.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Debug-only StrictMode: reports (never crashes on) main-thread disk/network work.
     *
     * This app straddles a JNI daemon, root `su` calls and file walks, so "did that just run
     * on Main?" is the single easiest way to reintroduce an ANR. `penaltyLog` only writes a
     * `StrictMode policy violation` line: watch it with
     * `adb logcat -s StrictMode` during a screen sweep.
     */
    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }
}
