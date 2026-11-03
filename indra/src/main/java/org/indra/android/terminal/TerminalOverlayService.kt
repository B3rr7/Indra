package org.indra.android.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import org.indra.android.MainActivity
import org.indra.android.R
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.screens.terminal.TerminalBubble
import org.indra.android.ui.screens.terminal.TerminalBubblePanel
import org.indra.android.ui.screens.terminal.TerminalPanelActions
import org.indra.android.ui.theme.IndraTheme
import javax.inject.Inject

private const val CHANNEL_ID = "terminal_bubble"
private const val NOTIFICATION_ID = 4711
private const val COLLAPSED_SIZE_DP = 64
private const val EDGE_MARGIN_DP = 12
private const val VERTICAL_ANCHOR = 0.55f

/** Smallest the expanded panel may be shrunk to, as a fraction of the usable size. */
private const val MIN_PANEL_FRACTION = 0.35f

/** Floor for the expanded panel in dp, so it can never collapse past usefulness. */
private const val MIN_PANEL_DP = 180

/** Fallback panel size before the session has loaded the persisted one. */
private const val DEFAULT_PANEL_WIDTH_FRACTION = 0.94f
private const val DEFAULT_PANEL_HEIGHT_FRACTION = 0.45f

/**
 * Draws the terminal bubble above every other app.
 *
 * A foreground service is the only thing Android lets an app paint over other windows while
 * the activity is gone, so this is what makes "run the shell, open Chrome, watch it" work.
 * The window is a plain `ComposeView` with hand-built owners (an overlay has no activity to
 * inherit them from) rendering the exact same panel the in-app bubble uses, and the command
 * itself lives in the [TerminalSession] singleton — this service is only a surface.
 */
@Suppress("TooManyFunctions") // window lifecycle + resize + notification + owner plumbing
@AndroidEntryPoint
class TerminalOverlayService : Service() {
    @Inject
    lateinit var session: TerminalSession

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val owner = OverlayOwner()
    private var view: ComposeView? = null

    // Compose state: changing these from the service (tap handlers) must re-render the view.
    private var expanded by mutableStateOf(false)

    // Expanded panel size as a fraction of the screen, so a pinch/grip resize can be applied to
    // the window params and remembered (shared with the in-app bubble through the session).
    private var panelWidthFraction = DEFAULT_PANEL_WIDTH_FRACTION
    private var panelHeightFraction = DEFAULT_PANEL_HEIGHT_FRACTION

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startForegroundNotification()
        attach()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onDestroy() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attach() {
        val metrics = resources.displayMetrics
        val collapsed = dp(COLLAPSED_SIZE_DP)
        params =
            WindowManager.LayoutParams(
                collapsed,
                collapsed,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = metrics.widthPixels - collapsed - dp(EDGE_MARGIN_DP)
                y = (metrics.heightPixels * VERTICAL_ANCHOR).toInt()
            }
        val content =
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    IndraTheme {
                        OverlayContent(
                            session = session,
                            expanded = expanded,
                            actions =
                                OverlayActions(
                                    onToggle = { applyExpanded(!expanded) },
                                    onMove = ::dragBy,
                                    panel =
                                        TerminalPanelActions(
                                            onRun = session::run,
                                            onStop = session::stop,
                                            onCollapse = { applyExpanded(false) },
                                            onClose = {
                                                session.setPreferences(bubbleVisible = false)
                                                stopSelf()
                                            },
                                            onZoom = ::zoomBy,
                                            onResizeBy = { offset -> resizeBy(offset.x, offset.y) },
                                        ),
                                ),
                        )
                    }
                }
            }
        view = content
        runCatching { windowManager.addView(content, params) }
    }

    /**
     * Resizes the window between the bubble and the panel, and takes focus for the IME.
     *
     * `SOFT_INPUT_ADJUST_RESIZE` is deprecated for activities (they resize from insets now),
     * but an overlay window has no activity to do that for it — without it the keyboard
     * covers the panel's input line and the bubble becomes unusable for typing.
     */
    @Suppress("DEPRECATION")
    private fun applyExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val metrics = resources.displayMetrics
        if (value) {
            // Pick up whatever size was last chosen (it may have been set from the in-app bubble).
            val saved = session.state.value
            panelWidthFraction = saved.panelWidth
            panelHeightFraction = saved.panelHeight
            // Focusable so the soft keyboard can type into the panel; the platform IME only
            // accepts an overlay window that can take focus.
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            params.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            applyPanelSize()
            params.x = (params.x - dp(EDGE_MARGIN_DP)).coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
            params.y =
                (params.y - dp(EDGE_MARGIN_DP) + dp(COLLAPSED_SIZE_DP))
                    .coerceIn(0, (usableHeight() - params.height).coerceAtLeast(0))
            view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        } else {
            val collapsed = dp(COLLAPSED_SIZE_DP)
            params.width = collapsed
            params.height = collapsed
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
    }

    /** Display height minus the navigation bar; the window must stay above it. */
    private fun usableHeight(): Int = (resources.displayMetrics.heightPixels - bottomInset()).coerceAtLeast(1)

    /**
     * Applies [panelWidthFraction]/[panelHeightFraction] to the window, clamped so the panel stays
     * on screen and above the navigation bar, then persists the size for both surfaces.
     */
    private fun applyPanelSize() {
        val metrics = resources.displayMetrics
        val minPx = dp(MIN_PANEL_DP)
        val usable = usableHeight()
        params.width =
            (panelWidthFraction * metrics.widthPixels).toInt()
                .coerceIn(minPx, (metrics.widthPixels - dp(EDGE_MARGIN_DP)).coerceAtLeast(minPx))
        params.height =
            (panelHeightFraction * usable).toInt()
                .coerceIn(minPx, usable.coerceAtLeast(minPx))
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (usable - params.height).coerceAtLeast(0))
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        session.setPreferences(panelWidth = panelWidthFraction, panelHeight = panelHeightFraction)
    }

    /** Pinch factor (1.0 = no change) from the panel header. */
    private fun zoomBy(factor: Float) {
        panelWidthFraction = (panelWidthFraction * factor).coerceIn(MIN_PANEL_FRACTION, 1f)
        panelHeightFraction = (panelHeightFraction * factor).coerceIn(MIN_PANEL_FRACTION, 1f)
        applyPanelSize()
    }

    /** Corner-grip drag delta in px. */
    private fun resizeBy(
        dx: Float,
        dy: Float,
    ) {
        val metrics = resources.displayMetrics
        panelWidthFraction = (panelWidthFraction + dx / metrics.widthPixels).coerceIn(MIN_PANEL_FRACTION, 1f)
        panelHeightFraction = (panelHeightFraction + dy / usableHeight()).coerceIn(MIN_PANEL_FRACTION, 1f)
        applyPanelSize()
    }

    /** Moves the whole window with the finger; clamped so it can never be dragged off-screen. */
    private fun dragBy(
        dx: Float,
        dy: Float,
    ) {
        val metrics = resources.displayMetrics
        params.x = (params.x + dx.toInt()).coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y =
            (params.y + dy.toInt())
                .coerceIn(0, (metrics.heightPixels - bottomInset() - params.height).coerceAtLeast(0))
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /**
     * Height of the navigation bar (gesture pill or three buttons).
     *
     * `displayMetrics.heightPixels` is the whole display, so a window clamped against it puts
     * its last rows *under* the bar: the expanded panel's input line and its bottom rounded
     * edge were cut off on the device. Note this is only the bar — the keyboard is handled by
     * [WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE].
     */
    private fun bottomInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return dp(EDGE_MARGIN_DP)
        return runCatching {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars())
                .bottom
        }.getOrDefault(dp(EDGE_MARGIN_DP))
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Terminal bubble", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the floating terminal alive while you use other apps."
                    setShowBadge(false)
                },
            )
        }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification: Notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Terminal bubble")
                .setContentText("The floating terminal is running over other apps.")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TerminalOverlayService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalOverlayService::class.java))
        }

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** The per-app "Display over other apps" screen, which is the fix for the in-app-only bubble. */
        fun permissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The overlay's callbacks, bundled so [OverlayContent] keeps a short signature: moving the
 * window and the panel's own gestures are the same "act on the window" family.
 */
private data class OverlayActions(
    val onToggle: () -> Unit,
    val onMove: (Float, Float) -> Unit,
    val panel: TerminalPanelActions,
)

@Composable
private fun OverlayContent(
    session: TerminalSession,
    expanded: Boolean,
    actions: OverlayActions,
) {
    val state by session.state.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (expanded) {
            TerminalBubblePanel(
                state = state,
                actions = actions.panel,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TerminalBubble(
                running = state.running,
                onTap = actions.onToggle,
                onDrag = { offset -> actions.onMove(offset.x, offset.y) },
            )
        }
    }
}

/**
 * The owners a `ComposeView` needs and an overlay window has nobody to inherit from.
 *
 * `LifecycleRegistry` keeps `collectAsStateWithLifecycle` collecting (a destroyed owner stops
 * it silently, which is how an overlay bubble ends up frozen on its first frame).
 */
private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val saved = SavedStateRegistryController.create(this)

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
