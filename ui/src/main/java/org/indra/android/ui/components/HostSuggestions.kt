package org.indra.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.indra.android.core.system.HostSuggestionLayout

private val CHIP_PADDING_HORIZONTAL = 12.dp
private val CHIP_PADDING_VERTICAL = 6.dp
private val CHIP_SPACING = 8.dp
private val CHIP_BORDER = 1.dp
private val CHIP_FONT_SIZE = 12.sp

/** Material's disabled-content alpha, so a chip dims like the `AssistChip` it replaces. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The one "tap a discovered host" chip row (WS-5.1).
 *
 * Fourteen screens drew every host as an `AssistChip` in a non-scrollable `Row`: with more than a
 * few hosts the chips were clipped off the right edge with no way to reach them. This wraps
 * (`FlowRow`), keeps at most [org.indra.android.core.system.MAX_VISIBLE_HOST_SUGGESTIONS] chips and
 * puts the rest behind a "+N more" menu.
 *
 * @param hosts discovered hosts, in the order the caller wants them shown.
 * @param onSelect called with the tapped host — the caller fills its target field.
 * @param enabled false while the tool is running, so a scan cannot be re-targeted mid-flight.
 * A tap fills the field; a long-press copies the host (same gesture as the copy buttons in
 * `LiveOutputCard`), with a haptic tick so it does not look like a dead tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostSuggestions(
    hosts: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (hosts.isEmpty()) return
    val layout = HostSuggestionLayout.of(hosts)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING),
        verticalArrangement = Arrangement.spacedBy(CHIP_SPACING),
    ) {
        layout.visible.forEach { host ->
            HostChip(label = host, enabled = enabled, onClick = { onSelect(host) })
        }
        if (layout.overflow.isNotEmpty()) {
            OverflowChip(hosts = layout.overflow, enabled = enabled, onSelect = onSelect)
        }
    }
}

@Composable
private fun OverflowChip(
    hosts: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HostChip(
            label = "+${hosts.size} more",
            enabled = enabled,
            onClick = { expanded = true },
            copyOnLongPress = false,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        expanded = false
                        onSelect(host)
                    },
                )
            }
        }
    }
}

/**
 * A chip-shaped target: [Surface] rather than `AssistChip` because a chip has no long-press slot,
 * and the whole point of this component is tap-to-fill plus long-press-to-copy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    copyOnLongPress: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DISABLED_ALPHA)
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(CHIP_BORDER, MaterialTheme.colorScheme.outline),
        modifier =
            Modifier.combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick =
                    if (!copyOnLongPress) {
                        null
                    } else {
                        {
                            clipboard.setText(AnnotatedString(label))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
            ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = CHIP_PADDING_HORIZONTAL, vertical = CHIP_PADDING_VERTICAL),
            fontSize = CHIP_FONT_SIZE,
        )
    }
}
