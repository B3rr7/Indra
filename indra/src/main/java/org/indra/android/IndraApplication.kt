package org.indra.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IndraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        org.indra.android.core.system.Logger.setDebugEnabled(BuildConfig.DEBUG)

        // Install crash reporter
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                org.indra.android.core.system.CrashReporter(applicationContext).report(throwable)
            } catch (e: Exception) {
                // Ignore
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
