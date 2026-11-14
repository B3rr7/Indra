package org.indra.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A coloured bloom behind an icon tile, painted in the tool's own hue.
 *
 * A card shadow cannot do this: Compose's elevation shadows are grey, and on a near-black page a
 * grey shadow is invisible. What reads as "glow" on a dark surface is a **bloom** — a radial
 * gradient in the accent colour that fades to transparent, plus a thin bright ring at the tile's
 * edge where the light is strongest.
 *
 * Three stops rather than two, because a linear alpha ramp from colour to transparent has a visible
 * hard edge at the outside: the intermediate stop pulls the falloff out to where the eye stops
 * noticing, which is what makes it look like light instead of like a coloured circle.
 *
 * Drawn with `drawBehind`, so the tile and its glyph sit on top; no `RenderEffect`/blur (API 31+)
 * and no extra layer, which keeps minSdk 26 working and costs one draw op per card.
 */
fun Modifier.iconGlow(
    color: Color,
    radius: Dp = 34.dp,
    intensity: Float = 0.45f,
): Modifier =
    drawBehind {
        val r = radius.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        drawBloom(color, center, r, intensity)
        drawEdgeRing(color, center, r, intensity)
    }

private fun DrawScope.drawBloom(
    color: Color,
    center: Offset,
    radius: Float,
    intensity: Float,
) {
    val brush =
        Brush.radialGradient(
            colors =
                listOf(
                    color.copy(alpha = intensity),
                    color.copy(alpha = intensity * MID_STOP_FACTOR),
                    color.copy(alpha = OUTER_ALPHA),
                ),
            center = center,
            radius = radius,
        )
    drawCircle(brush = brush, radius = radius, center = center)
}

/**
 * The ring: a 1.5dp stroke at ~60% of the bloom's radius, so the halo has a bright edge near the
 * tile and dissolves outward. Without it the bloom reads as a blurred smudge.
 */
private fun DrawScope.drawEdgeRing(
    color: Color,
    center: Offset,
    radius: Float,
    intensity: Float,
) {
    val ringRadius = radius * RING_RADIUS_FACTOR
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        color.copy(alpha = intensity * RING_ALPHA_FACTOR),
                        Color.Transparent,
                    ),
                center = center,
                radius = ringRadius,
            ),
        radius = ringRadius,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringWidth()),
    )
}

/** The ring is thinner than a hairline at low densities and exactly 1.5dp at 3x. */
private fun DrawScope.ringWidth(): Float = RING_WIDTH_DP.dp.toPx()

/** The box that a glow is drawn *around*: the tile's own size plus the bloom's spill. */
@Composable
fun GlowBox(
    color: Color,
    modifier: Modifier = Modifier,
    tile: Dp = 42.dp,
    spill: Dp = 26.dp,
    intensity: Float = 0.45f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(tile + spill)
                .iconGlow(color = color, radius = (tile + spill) / 2, intensity = intensity)
                .then(modifier),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        content()
    }
}

private const val MID_STOP_FACTOR = 0.18f
private const val OUTER_ALPHA = 0.02f
private const val RING_ALPHA_FACTOR = 1.6f
private const val RING_RADIUS_FACTOR = 0.55f
private const val RING_WIDTH_DP = 1.5f

/** Spacing between the glow tint and the tile it surrounds, used by the call sites. */
val GlowSpill = 26.dp
