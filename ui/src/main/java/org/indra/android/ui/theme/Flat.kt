package org.indra.android.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Flat 2.0 / semi-flat surface policy.
 *
 * Flat 2.0 drops the shadow as a depth cue. Depth is expressed instead by three things, in this
 * order: a tonal fill (the surface is a shade off the background), a hairline edge (so the shape
 * still has a boundary), and spacing. Shadows are not "reduced" here — they are removed, because a
 * 2dp shadow on a dark theme is invisible anyway and only costs a render pass.
 *
 * "Semi-flat" is the concession: surfaces keep a **tonal** lift, so a card still reads as a layer
 * above the background. Fully flat would put every card on the same colour as the page, and on a
 * dark theme with no shadow that makes the layout collapse into one undifferentiated sheet.
 *
 * Every elevation token is `0.dp` on purpose, and `FlatSurfaceTest` pins that. If a future change
 * wants a raised surface it has to add a token here and change the test, which is the point: the
 * policy should be a decision, not a value someone typed into a card.
 */
object Flat {
    /** No shadow anywhere. The only elevation the app uses. */
    val elevation: Dp = 0.dp

    /** Hairline width: one physical pixel class, not a "thin" guess. */
    val hairline: Dp = 1.dp

    /**
     * How strong the edge is.
     *
     * A hairline at full `outlineVariant` reads as a drawn box on a dark theme; at 0.5 it is a
     * suggestion of an edge, which is what a flat surface wants.
     */
    const val BORDER_ALPHA: Float = 0.5f

    /**
     * How much of the surface tint a card carries.
     *
     * This is the semi-flat part: enough that a card separates from the page, not so much that it
     * looks like a second background.
     */
    const val SURFACE_ALPHA: Float = 0.5f

    /** The default corner radius for a flat surface. */
    val corner: Dp = 16.dp

    /** A card's shape, so every card in the app rounds the same way. */
    val shape: Shape = RoundedCornerShape(corner)
}

/**
 * The hairline edge of a flat surface.
 *
 * Applied to a card that has no shadow, this is what gives the shape a boundary. Use the same
 * [shape] the surface is clipped to, or the border and the fill disagree at the corners.
 */
@Composable
fun Modifier.flatEdge(shape: Shape = Flat.shape): Modifier =
    border(
        width = Flat.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Flat.BORDER_ALPHA),
        shape = shape,
    )

/**
 * A flat surface fill: the tonal lift that replaces the shadow.
 *
 * `surfaceVariant` rather than `surface`, because on this dark theme `surface` is within a few
 * levels of `background` and a card filled with it is invisible.
 */
@Composable
fun flatSurfaceColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = Flat.SURFACE_ALPHA)
