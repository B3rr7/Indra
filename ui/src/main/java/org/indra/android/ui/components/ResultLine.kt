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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.indra.android.ui.theme.Flat
import org.indra.android.ui.theme.LocalAccent
import org.indra.android.ui.theme.flatSurfaceColor

/** One row of a [ResultList]: what the row is, what it carries, and an optional right-hand value. */
data class ResultLine(
    /** Row identity for the lazy list — an IP, a port number, a cookie. Never the text. */
    val key: String,
    /** The row's subject, rendered monospaced: an address, a port, a hop. */
    val title: String,
    /** Optional second line: a MAC, a service name, a hint. */
    val detail: String = "",
    /** Optional right-aligned value in the tool's accent: a latency, a state. */
    val trailing: String = "",
)

/**
 * A dense result table: one card, one row per hit, hairline dividers, the tool's accent on the
 * bullet and the trailing value.
 *
 * The screens this replaces rendered **one card per row** — which on the moto g60 meant a 12dp
 * padding, an 8dp gap and a hairline for every host in a sweep, so a 30-host radar was a page of
 * boxes you could scroll forever and compare nothing in. A result table is read by scanning two
 * columns; that needs rows, not cards.
 *
 * The accent comes from `LocalAccent`, so a row set inside a plugin screen is tinted by that tool
 * without passing anything.
 *
 * @param modifier must bound the height: the body is a `LazyColumn`.
 * @param header optional line above the rows (a count, a state).
 */
@Composable
fun ResultList(
    lines: List<ResultLine>,
    modifier: Modifier = Modifier,
    header: String? = null,
) {
    val accent = LocalAccent.current
    Card(
        modifier = modifier,
        shape = Flat.shape,
        colors = CardDefaults.cardColors(containerColor = flatSurfaceColor()),
        border = BorderStroke(Flat.hairline, accent.edge(EDGE_ALPHA)),
        elevation = CardDefaults.cardElevation(defaultElevation = Flat.elevation),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (header != null) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent.soft,
                    modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = HEADER_PADDING_V),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(
                    items = lines,
                    key = { _, line -> line.key },
                    contentType = { _, _ -> "result-row" },
                ) { index, line ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = Flat.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA),
                        )
                    }
                    ResultRow(line, accent.base)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    line: ResultLine,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BULLET_GAP),
    ) {
        Box(modifier = Modifier.size(BULLET_SIZE).background(accent, CircleShape))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = line.title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (line.detail.isNotBlank()) {
                Text(
                    text = line.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (line.trailing.isNotBlank()) {
            Text(
                text = line.trailing,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
    }
}

private val ROW_PADDING_H = 14.dp
private val ROW_PADDING_V = 10.dp
private val HEADER_PADDING_V = 8.dp
private val BULLET_SIZE = 6.dp
private val BULLET_GAP = 12.dp
private const val EDGE_ALPHA = 0.28f
private const val DIVIDER_ALPHA = 0.5f
