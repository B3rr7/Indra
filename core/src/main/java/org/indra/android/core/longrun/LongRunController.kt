package org.indra.android.core.longrun

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.indra.android.core.system.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops [LongRunService] as the number of tracked children changes.
 *
 * Called from [org.indra.android.core.child.ChildProcessManager], which already knows every child
 * the app started (that registry is what `stopAllRunning()` walks before a binary swap), so the
 * service needs no bookkeeping of its own — `running == 0` is the honest "nothing to keep alive".
 *
 * A refused start is logged, not thrown: `startForegroundService` is unavailable to a background
 * app on Android 12+, and in that situation the run continues anyway (the process simply is not
 * protected from the freezer). Killing a scan because its notification could not be posted would
 * be worse than losing the guarantee.
 */
@Singleton
class LongRunController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var active = false

        /** Called whenever the tracked-child count changes. */
        fun onRunningChanged(running: Int) {
            if (!LongRunNotice.isActive(running)) {
                if (active) stop()
                return
            }
            start(running)
        }

        private fun start(running: Int) {
            val started =
                runCatching {
                    ContextCompat.startForegroundService(context, LongRunService.intent(context, running))
                }.onFailure {
                    Logger.w("Long-run service refused (${it::class.java.simpleName}: ${it.message})")
                }
            active = started.isSuccess || active
        }

        private fun stop() {
            // stopService is always allowed, including from the background.
            runCatching { context.stopService(LongRunService.stopIntent(context)) }
                .onFailure { Logger.w("Long-run service did not stop: ${it.message}") }
            active = false
        }
    }
