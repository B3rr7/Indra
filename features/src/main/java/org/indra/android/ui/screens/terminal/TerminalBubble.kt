package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.indra.android.core.system.terminal.TerminalSession

private const val BUBBLE_SIZE = 56
private const val BUBBLE_ICON = 26
private const val RUNNING_DOT = 10
private const val PANEL_RADIUS = 16
private const val HEADER_FONT = 12
private const val GRIP_SIZE = 22
private const val GRIP_STROKE = 1.5f
private const val GRIP_GAP = 5
private const val GRIP_LINES = 3

/**
 * Everything the floating panel can do, bundled so the composable keeps a short signature.
 *
 * [onZoom] is a two-finger pinch factor (1.0 = no change) handled on the panel header;
 * [onResizeBy] is a one-finger drag delta (px) from the bottom-right grip. Both are applied to
 * the host's size state, which is what actually resizes the panel/window.
 */
data class TerminalPanelActions(
    val onRun: (String) -> Unit,
    val onStop: () -> Unit,
    val onCollapse: () -> Unit,
    val onClose: () -> Unit,
    val onZoom: (Float) -> Unit,
    val onResizeBy: (Offset) -> Unit,
)

/**
 * The floating terminal head — a small round button that rides above the app (and, with the
 * overlay permission, above other apps) and opens the panel on tap.
 *
 * Dragging is reported to the host instead of being applied here: the in-app host moves a
 * `Modifier.offset`, the overlay moves its `WindowManager` window, and both need the same
 * gesture. Anything else would fight the platform.
 */
@Composable
fun TerminalBubble(
    running: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val ring = if (running) TerminalPalette.accent else TerminalPalette.info.copy(alpha = 0.6f)
    Box(
        modifier =
            modifier
                .size(BUBBLE_SIZE.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    }
                }
                .background(TerminalPalette.background, CircleShape)
                .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = "Terminal bubble",
            tint = TerminalPalette.prompt,
            modifier = Modifier.size(BUBBLE_ICON.dp),
        )
        if (running) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(TerminalPalette.accent, CircleShape)
                        .border(1.dp, TerminalPalette.background, CircleShape)
                        .size(RUNNING_DOT.dp),
            )
        }
    }
}

/**
 * The expanded bubble: a floating console that can sit over another app.
 *
 * Deliberately not a dialog — it must not take the whole screen or dim what is behind it,
 * because the point is watching a long command while doing something else.
 */
@Composable
fun TerminalBubblePanel(
    state: TerminalSession.State,
    actions: TerminalPanelActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(PANEL_RADIUS.dp),
            color = TerminalPalette.background,
            contentColor = TerminalPalette.foreground,
            border = BorderStroke(1.dp, TerminalPalette.accent.copy(alpha = 0.5f)),
            shadowElevation = 12.dp,
        ) {
            Column {
                BubblePanelHeader(state = state, actions = actions)
                TerminalConsole(
                    state = state,
                    modifier = Modifier.weight(1f),
                    compact = true,
                    showSnippets = false,
                    onRun = actions.onRun,
                    onStop = actions.onStop,
                )
            }
        }
        // A one-finger drag handle at the bottom-right; remembers its size across restarts.
        ResizeGrip(
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            onResizeBy = actions.onResizeBy,
        )
    }
}

/**
 * The panel's title bar. Pinching it resizes the panel (the console below keeps its own
 * scroll/tap gestures, so a pinch on the body would fight them).
 */
@Composable
private fun BubblePanelHeader(
    state: TerminalSession.State,
    actions: TerminalPanelActions,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) actions.onZoom(zoom)
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = TerminalPalette.prompt,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = state.cwd.ifBlank { "shell" },
            color = if (state.running) TerminalPalette.accent else TerminalPalette.info,
            fontFamily = FontFamily.Monospace,
            fontSize = HEADER_FONT.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = actions.onCollapse,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Collapse",
                tint = TerminalPalette.info,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = actions.onClose,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close bubble",
                tint = TerminalPalette.info,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Three short diagonal lines in the bottom-right corner: the panel's resize affordance. */
@Composable
private fun ResizeGrip(
    onResizeBy: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(GRIP_SIZE.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onResizeBy(dragAmount)
                    }
                },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = GRIP_STROKE.dp.toPx()
            val gap = GRIP_GAP.dp.toPx()
            for (i in 1..GRIP_LINES) {
                val d = i * gap
                drawLine(
                    color = TerminalPalette.info,
                    start = Offset(size.width - d, size.height - stroke / 2f),
                    end = Offset(size.width - stroke / 2f, size.height - d),
                    strokeWidth = stroke,
                )
            }
        }
    }
}
