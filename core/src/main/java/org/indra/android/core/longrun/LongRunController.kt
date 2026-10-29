package org.indra.android.core.longrun

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * **Everything is a child, not just scans.** Home's `ip neigh` probe, the neighbour table and a
 * dozen other one-shot commands are tracked children that live ~50 ms, so promoting a foreground
 * service on the first `track()` posted a notification on every app start and — worse — stopped
 * the service again before `onStartCommand` could call `startForeground()`, which the framework
 * answers by killing the process (`ForegroundServiceDidNotStartInTimeException`, on device every
 * cold start). The service is therefore only promoted once a run has lasted
 * [PROMOTE_AFTER_MS]; shorter commands never touch it.
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Last count reported by [ChildProcessManager]; the delayed start reads this. */
        @Volatile
        private var latest = 0

        @Volatile
        private var active = false

        private var pending: Job? = null

        /** Called whenever the tracked-child count changes. */
        fun onRunningChanged(running: Int) {
            latest = running
            when {
                !LongRunNotice.isActive(running) ->
                    if (active) {
                        stop()
                    } else {
                        cancelPending()
                    }
                // Already up: re-send with the new count so the notification stays truthful.
                active -> start(running)
                pending?.isActive == true -> Unit // a promotion is already scheduled
                else ->
                    pending =
                        scope.launch {
                            delay(PROMOTE_AFTER_MS)
                            if (LongRunNotice.isActive(latest)) start(latest) else cancelPending()
                        }
            }
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
            cancelPending()
            // stopService is always allowed, including from the background.
            runCatching { context.stopService(LongRunService.stopIntent(context)) }
                .onFailure { Logger.w("Long-run service did not stop: ${it.message}") }
            active = false
        }

        private fun cancelPending() {
            pending?.cancel()
            pending = null
        }

        companion object {
            /**
             * How long a run must last before it is worth a foreground service.
             *
             * Longer than any startup probe (Home's neighbour table answers in ~50 ms) and far
             * shorter than the scans this exists for (30-minute budget).
             */
            const val PROMOTE_AFTER_MS = 2_000L
        }
    }
