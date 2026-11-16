package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.terminal.TerminalTab
import org.indra.android.core.system.terminal.TerminalTabRules

private const val TAB_HEIGHT = 34
private const val TAB_MIN_WIDTH = 64
private const val LABEL_FONT = 12
private const val CLOSE_GLYPH = 14
private const val ADD_GLYPH = 16
private const val CLOSE_TARGET = 24
private const val DENSE_TOUCH_TARGET = 24
private const val RUNNING_DOT = 6
private const val STRIP_PAD_H = 8
private const val STRIP_PAD_V = 4
private const val STRIP_GAP = 6
private const val TAB_PAD_START = 10
private const val TAB_PAD_END = 2
private const val TAB_CORNER = 9
private const val CLOSE_CORNER = 6
private const val INACTIVE_ALPHA = 0.35f
private const val ACTIVE_ALPHA = 0.85f
private const val DISABLED_ALPHA = 0.3f

/**
 * The tab strip under the terminal's top bar: one pill per open session, then a `+`.
 *
 * A browser-style strip rather than a drawer because switching tabs is a thing you do constantly
 * while comparing two outputs, and hiding that behind a second tap would make the feature worse
 * than the single session it replaces. It scrolls horizontally past whatever fits, exactly like the
 * symbol row: a clipped pill would be a session the user cannot reach.
 *
 * Each pill shows its own running dot, so a scan left in another tab is visible without opening it.
 * The `×` only appears while more than one tab is open — the last tab cannot be closed (there would
 * be nothing left to show), so offering the button would be a lie. `exit` / `logout` typed into a
 * tab is the same request from the command line: it closes that tab, and on the last one it
 * restarts it rather than leaving the strip empty.
 */
@Composable
fun TerminalTabStrip(
    tabs: List<TerminalTab>,
    activeId: Int,
    onSelect: (Int) -> Unit,
    onOpen: () -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Compose 1.9 gives every `clickable` a 48dp touch target regardless of its laid-out size. On
    // a 64dp pill that made the × own three quarters of it: the number sat at the left and a tap
    // anywhere near the middle closed the tab. This strip is deliberately dense — the targets are
    // exactly what is drawn — so the platform floor is lowered for its subtree only.
    val viewConfiguration = LocalViewConfiguration.current
    CompositionLocalProvider(
        LocalViewConfiguration provides DenseViewConfiguration(viewConfiguration),
    ) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = STRIP_PAD_H.dp, vertical = STRIP_PAD_V.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(STRIP_GAP.dp),
        ) {
            tabs.forEach { tab ->
                TabPill(
                    tab = tab,
                    selected = tab.id == activeId,
                    closable = tabs.size > 1,
                    onSelect = onSelect,
                    onClose = onClose,
                )
            }
            AddTabButton(enabled = TerminalTabRules.canOpen(tabs.size), onClick = onOpen)
        }
    }
}

/**
 * The platform's view configuration with one change: a clickable never grows past its own bounds.
 *
 * Every timing still comes straight from the platform — this is about touch targets, not gestures.
 */
private class DenseViewConfiguration(
    private val base: ViewConfiguration,
) : ViewConfiguration {
    override val longPressTimeoutMillis: Long get() = base.longPressTimeoutMillis
    override val doubleTapTimeoutMillis: Long get() = base.doubleTapTimeoutMillis
    override val doubleTapMinTimeMillis: Long get() = base.doubleTapMinTimeMillis
    override val touchSlop: Float get() = base.touchSlop

    /** 24dp is the smallest control here, so nothing is inflated and nothing is shrunk. */
    override val minimumTouchTargetSize: DpSize
        get() = DpSize(DENSE_TOUCH_TARGET.dp, DENSE_TOUCH_TARGET.dp)
}

@Composable
private fun TabPill(
    tab: TerminalTab,
    selected: Boolean,
    closable: Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    val state by tab.session.state.collectAsStateWithLifecycle()
    val content = if (selected) TerminalPalette.background else TerminalPalette.foreground
    Surface(
        shape = RoundedCornerShape(TAB_CORNER.dp),
        color =
            if (selected) {
                TerminalPalette.accent.copy(alpha = ACTIVE_ALPHA)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = INACTIVE_ALPHA)
            },
        contentColor = content,
    ) {
        Row(
            modifier =
                Modifier
                    .height(TAB_HEIGHT.dp)
                    // A pill that hugged its contents was almost entirely close button: the label
                    // took ~7dp and the 24dp × the rest, so a finger aiming at the number landed on
                    // the close box and the tab vanished. The minimum width buys a real gap between
                    // them, and the weight spacer below puts all of it in the middle where a tap
                    // means "switch".
                    .defaultMinSize(minWidth = TAB_MIN_WIDTH.dp)
                    .clickable(onClickLabel = "Switch to terminal ${tab.id}") { onSelect(tab.id) }
                    .padding(start = TAB_PAD_START.dp, end = if (closable) TAB_PAD_END.dp else TAB_PAD_START.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tab.id.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = LABEL_FONT.sp,
            )
            if (state.running) {
                Spacer(Modifier.width(STRIP_GAP.dp))
                Box(
                    modifier =
                        Modifier
                            .size(RUNNING_DOT.dp)
                            .background(if (selected) content else TerminalPalette.accent, CircleShape),
                )
            }
            Spacer(Modifier.weight(1f))
            if (closable) {
                // The strip's view configuration already keeps clickables at their drawn size, so
                // this box really is 24dp. Missing it only switches tabs (recoverable) instead of
                // killing the command in one (not).
                Box(
                    modifier =
                        Modifier
                            .size(CLOSE_TARGET.dp)
                            .clip(RoundedCornerShape(CLOSE_CORNER.dp))
                            .clickable(onClickLabel = "Close terminal ${tab.id}") { onClose(tab.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close terminal ${tab.id}",
                        modifier = Modifier.size(CLOSE_GLYPH.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTabButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) {
            TerminalPalette.accent
        } else {
            TerminalPalette.info.copy(alpha = DISABLED_ALPHA)
        }
    // Same dense-target rule as the pill's ×: an inflated 48dp target on a 34dp button would reach
    // into the neighbouring pill's switch area (and, at the cap, a disabled + would sit over the
    // last ×).
    Box(
        modifier =
            Modifier
                .size(TAB_HEIGHT.dp)
                .clip(RoundedCornerShape(TAB_CORNER.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = INACTIVE_ALPHA))
                .clickable(enabled = enabled, onClickLabel = "New terminal tab") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "New terminal tab",
            tint = tint,
            modifier = Modifier.size(ADD_GLYPH.dp),
        )
    }
}
