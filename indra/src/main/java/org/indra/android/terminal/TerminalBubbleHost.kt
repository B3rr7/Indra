package org.indra.android.terminal

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.screens.terminal.TerminalBubble
import org.indra.android.ui.screens.terminal.TerminalBubblePanel
import org.indra.android.ui.screens.terminal.TerminalPanelActions
import kotlin.math.roundToInt

private const val BUBBLE_DP = 56
private const val MARGIN_DP = 16

/** Smallest the floating panel may be shrunk to, as a fraction of the container. */
private const val MIN_PANEL_FRACTION = 0.35f
private const val START_VERTICAL_FRACTION = 0.6f

/**
 * The in-app half of the floating terminal.
 *
 * Two mutually exclusive surfaces, one session:
 *  - with the overlay permission, [TerminalOverlayService] draws the bubble over *everything*
 *    (including other apps), so nothing is drawn here;
 *  - without it, the bubble floats inside the activity instead, which still keeps the shell
 *    running while the user moves between tools.
 */
@Composable
fun TerminalBubbleHost(
    session: TerminalSession,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(TerminalOverlayService.canDrawOverlays(context)) }
    var expanded by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = TerminalOverlayService.canDrawOverlays(context)
        session.refresh()
    }

    // The service is the only surface that survives the activity, so it is started (and
    // stopped) from here: the toggle in the terminal screen just flips the session flag.
    LaunchedEffect(state.bubbleVisible, canOverlay) {
        if (state.bubbleVisible && canOverlay) {
            TerminalOverlayService.start(context)
        } else {
            TerminalOverlayService.stop(context)
            // The panel belongs to this activity: it cannot outlive a bubble that moved to
            // the overlay (or was switched off).
            expanded = false
        }
    }

    if (!state.bubbleVisible || canOverlay) return

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        val density = LocalDensity.current
        val bubblePx = with(density) { BUBBLE_DP.dp.toPx() }
        val marginPx = with(density) { MARGIN_DP.dp.toPx() }
        val maxX = (constraints.maxWidth - bubblePx - marginPx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - bubblePx - marginPx).coerceAtLeast(0f)
        // The panel is sized as a fraction of this container, and a resize is expressed as a
        // fraction delta, so the drag/pinch has to be converted with the container's own px size.
        val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        var x by remember { mutableFloatStateOf(maxX) }
        var y by remember { mutableFloatStateOf(maxY * START_VERTICAL_FRACTION) }

        if (expanded) {
            ExpandedFloatingPanel(
                session = session,
                state = state,
                containerWidth = containerW,
                containerHeight = containerH,
                onCollapse = { expanded = false },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MARGIN_DP.dp, bottom = MARGIN_DP.dp)
                        .fillMaxWidth(state.panelWidth)
                        .fillMaxHeight(state.panelHeight),
            )
        } else {
            TerminalBubble(
                running = state.running,
                modifier =
                    Modifier.offset {
                        IntOffset(x.roundToInt(), y.roundToInt())
                    },
                onTap = { expanded = true },
                onDrag = { delta ->
                    x = (x + delta.x).coerceIn(0f, maxX)
                    y = (y + delta.y).coerceIn(0f, maxY)
                },
            )
        }
    }
}

/**
 * The expanded panel and its resize wiring, extracted so [TerminalBubbleHost] stays short.
 *
 * A pinch scales both fractions; a corner-grip drag adds a fraction delta computed from the
 * container's own px size. Both go through the session, so the size is persisted and shared
 * with the over-app overlay.
 */
@Composable
private fun ExpandedFloatingPanel(
    session: TerminalSession,
    state: TerminalSession.State,
    containerWidth: Float,
    containerHeight: Float,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TerminalBubblePanel(
        state = state,
        actions =
            TerminalPanelActions(
                onRun = session::run,
                onStop = session::stop,
                onCollapse = onCollapse,
                onClose = { session.setPreferences(bubbleVisible = false) },
                onZoom = { zoom ->
                    val current = session.state.value
                    session.setPreferences(
                        panelWidth = (current.panelWidth * zoom).coerceIn(MIN_PANEL_FRACTION, 1f),
                        panelHeight = (current.panelHeight * zoom).coerceIn(MIN_PANEL_FRACTION, 1f),
                    )
                },
                onResizeBy = { delta ->
                    val current = session.state.value
                    session.setPreferences(
                        panelWidth = (current.panelWidth + delta.x / containerWidth).coerceIn(MIN_PANEL_FRACTION, 1f),
                        panelHeight = (current.panelHeight + delta.y / containerHeight).coerceIn(MIN_PANEL_FRACTION, 1f),
                    )
                },
            ),
        modifier = modifier,
    )
}
