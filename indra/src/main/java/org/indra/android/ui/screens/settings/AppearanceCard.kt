package org.indra.android.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.indra.android.ui.theme.Flat
import org.indra.android.ui.theme.Palette
import org.indra.android.ui.theme.Palettes
import org.indra.android.ui.theme.flatEdge
import org.indra.android.ui.theme.flatSurfaceColor

/**
 * The colour-palette picker.
 *
 * Each row shows the palette as the four swatches it actually is (background, surface, primary,
 * secondary) rather than as a name: a palette called "Forest Terminal" tells you nothing you can
 * see, and the point of the picker is that the app's whole surface changes when you tap it.
 *
 * Selecting is not a dialog and has no OK button — the theme animates while this card is still on
 * screen, so the row is the preview.
 */
@Composable
internal fun PaletteCard(
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().flatEdge(),
        shape = Flat.shape,
        colors = CardDefaults.cardColors(containerColor = flatSurfaceColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = Flat.elevation),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Palettes.all.forEachIndexed { index, palette ->
                PaletteRow(
                    palette = palette,
                    // An empty preference means "the default palette", and the row must show that.
                    selected =
                        palette.id == selectedId ||
                            (selectedId.isBlank() && palette.id == Palettes.default.id),
                    onClick = { onSelect(palette.id) },
                )
                if (index != Palettes.all.lastIndex) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ROW_PADDING_H)
                                .height(Flat.hairline)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    palette: Palette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The selected row has to read as selected without a radio button: the tint fades in, and the
    // check mark is the same channel the theme animates.
    val selectedTint by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            },
        animationSpec = tween(durationMillis = SELECTION_ANIMATION_MS),
        label = "paletteSelection",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(selectedTint)
                .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP),
    ) {
        Swatches(palette)
        Text(
            text = palette.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The palette's four swatches, in the order they are used: page, card, accent, second accent.
 *
 * The first swatch is the page colour, so it is drawn as an outlined chip — on a dark palette it is
 * nearly the same colour as the card it sits on, and without the outline it looks like a hole.
 */
@Composable
private fun Swatches(palette: Palette) {
    Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(palette.background)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = BORDER_ALPHA), CircleShape),
        )
        Box(modifier = Modifier.size(SWATCH_SIZE).clip(CircleShape).background(palette.surface))
        Box(modifier = Modifier.size(SWATCH_SIZE).clip(CircleShape).background(palette.primary))
        Box(modifier = Modifier.size(SWATCH_SIZE).clip(CircleShape).background(palette.secondary))
    }
}

private val ROW_PADDING_H = 14.dp
private val ROW_PADDING_V = 12.dp
private val SWATCH_SIZE = 18.dp
private val SWATCH_GAP = 8.dp
private const val BORDER_ALPHA = 0.5f
private const val DIVIDER_ALPHA = 0.4f
private const val SELECTED_ALPHA = 0.10f
private const val SELECTION_ANIMATION_MS = 220
