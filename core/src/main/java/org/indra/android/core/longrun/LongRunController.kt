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

        /** A stop that is waiting out [STOP_DEBOUNCE_MS] of quiet before it tears the service down. */
        private var stopJob: Job? = null

        /** Called whenever the tracked-child count changes. */
        fun onRunningChanged(running: Int) {
            latest = running
            if (LongRunNotice.isActive(running)) {
                // A run is live (again): a queued stop is now wrong. Cancel it before anything else.
                stopJob?.cancel()
                stopJob = null
                when {
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
            } else {
                cancelPending()
                if (active) scheduleStop()
            }
        }

        private fun start(running: Int) {
            // A start must never overlap a queued stop: `stopService()` immediately followed by
            // `startForegroundService()` is a documented way to be killed with
            // `ForegroundServiceDidNotStartInTimeException` (the record is torn down while still
            // waiting for the start it was just handed — seen on device at 01:35:38, 3 ms apart).
            stopJob?.cancel()
            stopJob = null
            val started =
                runCatching {
                    ContextCompat.startForegroundService(context, LongRunService.intent(context, running))
                }.onFailure {
                    Logger.w("Long-run service refused (${it::class.java.simpleName}: ${it.message})")
                }
            active = started.isSuccess || active
        }

        /**
         * Tears the service down after [STOP_DEBOUNCE_MS] with nothing running.
         *
         * Not immediately, because the tracked count flaps across zero whenever a scan ends next to
         * a short probe (Home's `ip neigh` is a tracked child that lives ~50 ms). A stop and a start
         * a few milliseconds apart is what killed the process; waiting also gives a just-promoted
         * service time to satisfy its `startForeground()` contract before it is stopped. A run that
         * starts during the wait cancels this job, so the notification is never torn down under a
         * live tool.
         */
        private fun scheduleStop() {
            if (stopJob?.isActive == true) return
            stopJob =
                scope.launch {
                    delay(STOP_DEBOUNCE_MS)
                    if (!LongRunNotice.isActive(latest)) stopNow()
                }
        }

        private fun stopNow() {
            // stopService is always allowed, including from the background.
            runCatching { context.stopService(LongRunService.stopIntent(context)) }
                .onFailure { Logger.w("Long-run service did not stop: ${it.message}") }
            active = false
            stopJob = null
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

            /**
             * How long the tracked count must stay at zero before the service is stopped.
             *
             * Long enough to ride out a probe flapping across zero and to let a just-promoted
             * service satisfy its start contract; short enough that a notification never lingers
             * noticeably after the last tool ends.
             */
            const val STOP_DEBOUNCE_MS = 1_000L
        }
    }
