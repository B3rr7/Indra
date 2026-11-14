package org.indra.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Entrance motion for a list of surfaces that are all composed at once (Home's tool grid, the
 * quick-action row).
 *
 * A grid of twelve cards that appears in one frame reads as a page load. The same grid arriving in a
 * 30 ms cascade reads as content — and it costs one `Animatable` per item, stopped as soon as it
 * reaches 1, rather than a `Transition` held for the screen's lifetime.
 *
 * The delay is capped: with 37 tools, `index * 30 ms` would put the last card 1.1 s behind the
 * first, which is an animation the user waits for. Past [STAGGER_CAP_INDEX] the cards arrive
 * together.
 */
@Composable
fun Modifier.entryStagger(
    index: Int,
    stepMs: Int = STAGGER_STEP_MS,
    rise: Boolean = true,
): Modifier {
    val progress = remember(index) { Animatable(0f) }
    LaunchedEffect(index) {
        val delay = (index.coerceAtMost(STAGGER_CAP_INDEX) * stepMs).toLong()
        if (delay > 0L) delay(delay)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        )
    }
    val value = progress.value
    return this
        .alpha(value)
        .graphicsLayer {
            if (rise) translationY = (1f - value) * ENTRY_RISE_PX
            scaleX = ENTRY_SCALE_START + (1f - ENTRY_SCALE_START) * value
            scaleY = ENTRY_SCALE_START + (1f - ENTRY_SCALE_START) * value
        }
}

/**
 * Press feedback for a surface that is not a Material button (a tool card, a quick-action pill).
 *
 * Material's ripple tells you *where* you touched; the 3% scale tells you *that* the whole card took
 * the press, which is the cue a large tile needs — the ripple is invisible under the gradient icon.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = PRESS_ANIMATION_MS),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private const val STAGGER_STEP_MS = 30
private const val STAGGER_CAP_INDEX = 8

/** 4dp of rise at 3.5x density; a pixel value because graphicsLayer takes px. */
private const val ENTRY_RISE_PX = 14f
private const val ENTRY_SCALE_START = 0.97f
private const val PRESS_SCALE = 0.97f
private const val PRESS_ANIMATION_MS = 110
