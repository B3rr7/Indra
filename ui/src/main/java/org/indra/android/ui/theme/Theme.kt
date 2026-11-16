package org.indra.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp

/**
 * Indra's theme: a [Palette] plus the app's type scale and shape ladder.
 *
 * The app is **dark-only** by product decision: every palette in [Palettes] is a dark set, the
 * window theme (`res/values/themes.xml`) is a dark `Theme.Material.NoActionBar`, and
 * `MainActivity.enableEdgeToEdge(SystemBarStyle.dark(...))` pins the system bars to match. Do not
 * reintroduce `isSystemInDarkTheme()`: the Compose surface and the window theme would disagree on a
 * light-mode device and the system-bar icons become unreadable.
 *
 * Switching palettes animates. Sixteen roles are cross-faded over [PALETTE_ANIMATION_MS] instead of
 * swapping instantly — an instant swap of the page, every card and every button at once reads as a
 * flicker rather than as a change of theme. The animation is driven here, once, so no screen has to
 * know that a palette can change at all.
 */
@Composable
fun IndraTheme(
    palette: Palette = Palettes.default,
    content: @Composable () -> Unit,
) {
    val scheme = palette.scheme()
    val animated = scheme.animated(animate = true)
    // Anchored on the animated primary, not on `palette.primary`: Settings' accents derive from this
    // colour, so they cross-fade with the rest of the theme instead of snapping 420 ms early.
    val accents = settingsAccentsFor(animated.primary)
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalSettingsAccents provides accents,
    ) {
        MaterialTheme(
            colorScheme = animated,
            typography = IndraTypography,
            shapes = IndraShapes,
            content = content,
        )
    }
}

/**
 * Cross-fades every role Material actually paints with, and copies the rest.
 *
 * `animateColorAsState` per role is deliberate over a single transition on the palette id: the
 * palette is a data class of sixteen roles, and a `Transition` would need a snapshot per role
 * anyway. Doing it here keeps the call sites unchanged — a screen reads `MaterialTheme.colorScheme`
 * and gets a colour that is already easing towards the new palette.
 */
@Composable
private fun ColorScheme.animated(animate: Boolean): ColorScheme {
    if (!animate) return this
    val spec = tween<androidx.compose.ui.graphics.Color>(durationMillis = PALETTE_ANIMATION_MS)
    val bg by animateColorAsState(background, spec, label = "bg")
    val onBg by animateColorAsState(onBackground, spec, label = "onBg")
    val surf by animateColorAsState(surface, spec, label = "surface")
    val onSurf by animateColorAsState(onSurface, spec, label = "onSurface")
    val surfVar by animateColorAsState(surfaceVariant, spec, label = "surfaceVariant")
    val onSurfVar by animateColorAsState(onSurfaceVariant, spec, label = "onSurfaceVariant")
    val prim by animateColorAsState(primary, spec, label = "primary")
    val onPrim by animateColorAsState(onPrimary, spec, label = "onPrimary")
    val primCont by animateColorAsState(primaryContainer, spec, label = "primaryContainer")
    val onPrimCont by animateColorAsState(onPrimaryContainer, spec, label = "onPrimaryContainer")
    val sec by animateColorAsState(secondary, spec, label = "secondary")
    val onSec by animateColorAsState(onSecondary, spec, label = "onSecondary")
    val outlineAnimated by animateColorAsState(outline, spec, label = "outline")
    val outlineVarAnimated by animateColorAsState(outlineVariant, spec, label = "outlineVariant")
    return copy(
        background = bg,
        onBackground = onBg,
        surface = surf,
        onSurface = onSurf,
        surfaceVariant = surfVar,
        onSurfaceVariant = onSurfVar,
        primary = prim,
        onPrimary = onPrim,
        primaryContainer = primCont,
        onPrimaryContainer = onPrimCont,
        secondary = sec,
        onSecondary = onSec,
        outline = outlineAnimated,
        outlineVariant = outlineVarAnimated,
    )
}

/**
 * The app's three corner radii.
 *
 * 18dp for cards, 14dp for chips and tiles, 26dp for pills and primary actions. Keeping them here
 * rather than at call sites is what stops one screen's card from being 12dp and the next 16dp —
 * the tree used to carry 10, 12, 14, 16, 20 and 24 dp at once.
 */
private val IndraShapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(26.dp),
    )

/** 420 ms: long enough to read as a change of theme, short enough not to block the picker. */
private const val PALETTE_ANIMATION_MS = 420
