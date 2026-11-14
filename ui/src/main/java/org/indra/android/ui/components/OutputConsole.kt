package org.indra.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.indra.android.core.system.terminal.Ansi
import org.indra.android.ui.theme.Accent
import org.indra.android.ui.theme.Brand
import org.indra.android.ui.theme.Flat
import org.indra.android.ui.theme.LocalAccent
import org.indra.android.ui.theme.flatSurfaceColor

private const val CONSOLE_PADDING = 8
private const val HEADER_HORIZONTAL_PADDING = 12
private const val HEADER_VERTICAL_PADDING = 2
private const val EMPTY_STATE_PADDING = 24
private const val EMPTY_STATE_SPACING = 4
private const val DOT_SIZE = 8
private const val DOT_RADIUS = 4
private const val DOT_GAP = 8
private const val PILL_RADIUS = 999
private const val PILL_FILL_ALPHA = 0.18f
private const val PILL_H_PADDING = 8
private const val PILL_V_PADDING = 2
private const val EDGE_ALPHA = 0.30f

/**
 * The one live-output console (WS-2.1; the rest of the sweep migrates onto it in WS-3.3).
 *
 * @param lines the lines to show, already capped by the ViewModel.
 * @param appendCount the **monotonic** number of lines appended so far. Auto-follow is keyed on
 *   it rather than on `lines.size`, because a capped list stops growing and the view would then
 *   freeze at the cap.
 * @param modifier must bound the height (e.g. `Modifier.weight(1f)`): the body is a
 *   `LazyColumn`, so an unbounded parent crashes the measure pass.
 * @param placeholder shown in the empty state — the caller decides whether "nothing yet" and
 *   "ran, found nothing" read the same (they must not).
 * @param copyText the full transcript to copy; falls back to the visible lines.
 */
@Composable
fun LiveOutputCard(
    lines: List<String>,
    appendCount: Int,
    modifier: Modifier = Modifier,
    title: String = "Output",
    placeholder: String = "No output yet",
    isRunning: Boolean = false,
    copyText: String = "",
) {
    // The frame's accent, so a console inside a plugin screen is tinted by that tool without the
    // screen passing anything.
    val accent = LocalAccent.current
    val listState = rememberLazyListState()
    val follow = rememberAutoFollowState(listState)
    val headTrimmed = (appendCount - lines.size).coerceAtLeast(0)
    AutoFollowScrollEffect(listState, appendCount, { lines.size - 1 }, follow, headTrimmed)
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = modifier,
        shape = Flat.shape,
        colors = CardDefaults.cardColors(containerColor = flatSurfaceColor()),
        border = BorderStroke(Flat.hairline, accent.edge(EDGE_ALPHA)),
        elevation = CardDefaults.cardElevation(defaultElevation = Flat.elevation),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ConsoleHeader(
                title = title,
                accent = accent,
                isRunning = isRunning,
                onCopy = { clipboard.setText(AnnotatedString(copyText.ifBlank { lines.joinToString("\n") })) },
            )
            if (lines.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(EMPTY_STATE_PADDING.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(CONSOLE_PADDING.dp),
                ) {
                    itemsIndexed(
                        items = lines,
                        // Keyed by position, not by text: a console line has no identity and
                        // tools legitimately print the same line twice (aircrack-ng prints
                        // "Reading packets, please wait..." once per capture file). A content
                        // key made the second copy crash the whole app with
                        // "Key ... was already used" — found on device. The list is append-only
                        // and only trimmed from the head, so the position does not reorder.
                        key = { index, _ -> index },
                        contentType = { _, _ -> "console-line" },
                    ) { _, line ->
                        Text(
                            // Tools that draw progress bars emit ANSI cursor control
                            // (`.[23C00 00 …`), and a console is not a terminal: aircrack-ng
                            // printed its whole key-progress meter as escape noise here.
                            text = Ansi.strip(line),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** The console's title row: accent dot, title in the tool's tint, a running pill and Copy. */
@Composable
private fun ConsoleHeader(
    title: String,
    accent: Accent,
    isRunning: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HEADER_HORIZONTAL_PADDING.dp, vertical = HEADER_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(end = DOT_GAP.dp)
                    .size(DOT_SIZE.dp)
                    .background(accent.base, RoundedCornerShape(DOT_RADIUS.dp)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = accent.soft,
            modifier = Modifier.weight(1f),
        )
        if (isRunning) {
            Surface(color = accent.fill(PILL_FILL_ALPHA), shape = RoundedCornerShape(PILL_RADIUS.dp)) {
                Text(
                    text = "running",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.base,
                    modifier = Modifier.padding(horizontal = PILL_H_PADDING.dp, vertical = PILL_V_PADDING.dp),
                )
            }
        }
        TextButton(onClick = onCopy) { Text("Copy", style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * A neutral empty state: "the tool ran and there is nothing", never "the tool could not be run".
 * A failure belongs in [FormError] instead.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(EMPTY_STATE_PADDING.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EMPTY_STATE_SPACING.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The single inline error style: a tool failure or a rejected input, never an empty state. */
@Composable
fun FormError(
    text: String,
    modifier: Modifier = Modifier,
) {
    // A filled surface rather than a bare red line: a failure is the one thing on these screens a
    // user must not scroll past, and red text on a dark card was easy to miss among the output.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = androidx.compose.material3.MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
