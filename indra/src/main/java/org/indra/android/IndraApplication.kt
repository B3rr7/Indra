package org.indra.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IndraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        org.indra.android.core.system.Logger.init(this)
        org.indra.android.core.system.Logger
            .setDebugEnabled(BuildConfig.DEBUG)

        val prefs = getSharedPreferences("indra_prefs", MODE_PRIVATE)
        org.indra.android.core.system.Logger
            .setFileLogging(prefs.getBoolean("PREF_DEBUG_ERROR_LOGGING", false))

        // Install crash reporter
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                org.indra.android.core.system
                    .CrashReporter(applicationContext)
                    .report(throwable)
            } catch (e: Exception) {
                // Ignore
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
