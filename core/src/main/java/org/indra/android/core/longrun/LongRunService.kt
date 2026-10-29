package org.indra.android.core.longrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.system.Logger
import javax.inject.Inject

/**
 * The foreground service that keeps a long tool run alive while the screen is off.
 *
 * Nearly every scan is given a 30-minute budget (`LONG_RUNNING_TIMEOUT_MS`), and on a doze device
 * a plain background process is a candidate for the freezer the moment the screen goes off — so
 * the app was promising a budget it could not deliver. A foreground service is the only thing
 * Android lets an app hold for that.
 *
 * It is started from [LongRunController] when the first child is tracked and stopped when the last
 * one ends, so there is nothing to remember to cancel: the notification *is* the fact that a tool
 * is running. `specialUse` is the declared type (a network scan or capture is not a data sync);
 * `POST_NOTIFICATIONS` is requested at launch, and a user who denied it still gets the service —
 * the notification is simply not visible.
 */
@AndroidEntryPoint
class LongRunService : Service() {
    @Inject
    lateinit var children: ChildProcessManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    @Suppress("ReturnCount")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            // The in-app Stop buttons kill one tool; this is the notification's "everything".
            children.stopAllRunning()
            stopSelf()
            return START_NOT_STICKY
        }
        val running = intent?.getIntExtra(EXTRA_RUNNING, 0) ?: 0
        if (!LongRunNotice.isActive(running)) {
            // Every tool already ended between the last update and this one.
            stopSelf()
            return START_NOT_STICKY
        }
        val started =
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(running), TYPE) }
                .onFailure { Logger.w("Long-run notification refused: ${it.message}") }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LongRunNotice.isActive(running)) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Long-running scans", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a scan or capture is running, so it survives the screen going off."
                setShowBadge(false)
            },
        )
    }

    private fun notification(running: Int): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                // A library module cannot name the app's activity; the launcher intent is the
                // same entry point the user taps on the home screen.
                packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, LongRunService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(icon())
            .setContentTitle(LongRunNotice.title(running))
            .setContentText(LongRunNotice.TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(LongRunNotice.TEXT))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** The app's own icon; a library module ships no drawable of its own. */
    private fun icon(): Int = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_sys_warning

    companion object {
        private const val CHANNEL_ID = "long_run"

        /** Distinct from the terminal bubble's 4711 so both can coexist. */
        private const val NOTIFICATION_ID = 4712

        const val ACTION_STOP_ALL = "org.indra.android.core.longrun.STOP_ALL"

        const val EXTRA_RUNNING = "org.indra.android.core.longrun.RUNNING"

        private val TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

        fun intent(
            context: Context,
            running: Int,
        ): Intent = Intent(context, LongRunService::class.java).putExtra(EXTRA_RUNNING, running)

        fun stopIntent(context: Context): Intent = Intent(context, LongRunService::class.java)
    }
}
