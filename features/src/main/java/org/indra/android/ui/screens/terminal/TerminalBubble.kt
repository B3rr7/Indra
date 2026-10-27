package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    modifier: Modifier = Modifier,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PANEL_RADIUS.dp),
        color = TerminalPalette.background,
        contentColor = TerminalPalette.foreground,
        border = BorderStroke(1.dp, TerminalPalette.accent.copy(alpha = 0.5f)),
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 2.dp),
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
                    onClick = onCollapse,
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
                    onClick = onClose,
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
            TerminalConsole(
                state = state,
                modifier = Modifier.weight(1f),
                compact = true,
                showSnippets = false,
                onRun = onRun,
                onStop = onStop,
            )
        }
    }
}
