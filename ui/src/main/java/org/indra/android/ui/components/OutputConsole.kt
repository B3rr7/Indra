package org.indra.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

private const val CONSOLE_PADDING = 8
private const val HEADER_HORIZONTAL_PADDING = 12
private const val HEADER_VERTICAL_PADDING = 2
private const val EMPTY_STATE_PADDING = 24
private const val EMPTY_STATE_SPACING = 4

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
    val listState = rememberLazyListState()
    val follow = rememberAutoFollowState(listState)
    val headTrimmed = (appendCount - lines.size).coerceAtLeast(0)
    AutoFollowScrollEffect(listState, appendCount, { lines.size - 1 }, follow, headTrimmed)
    val clipboard = LocalClipboardManager.current

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HEADER_HORIZONTAL_PADDING.dp, vertical = HEADER_VERTICAL_PADDING.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isRunning) "$title · running" else title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(copyText.ifBlank { lines.joinToString("\n") })) }) {
                    Text("Copy")
                }
            }
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
                    items(
                        items = lines,
                        key = { it },
                        contentType = { "console-line" },
                    ) { line ->
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
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
