package org.indra.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Whether an image is painted behind the app right now.
 *
 * Provided by `WallpaperBackground` and read by the scaffolds, the page gradients and the
 * persistent chrome. It is an ambient rather than a parameter because four unrelated places have
 * to agree on it, and any one of them keeping an opaque fill makes the whole image invisible no
 * matter what the others do — which is exactly the bug this file exists for: the wallpaper was
 * painted, then covered by a `Scaffold` whose default `containerColor` is the opaque background.
 *
 * Defaults to `false`, so anything composed outside the wallpaper wrapper (a preview, a test)
 * renders exactly the flat dark page it always did.
 */
val LocalWallpaperActive = staticCompositionLocalOf { false }

/**
 * Fill for a destination's page area.
 *
 * `background` while the app is a flat dark page — a screen is opaque, so what is behind it can
 * never leak through. `Transparent` once a wallpaper is behind the app, because the image *is* the
 * page and the scrim in `WallpaperBackground` is what keeps it readable. The image wrapper always
 * paints the theme background first, so a transparent page still has a defined colour with no
 * wallpaper set.
 */
@Composable
@ReadOnlyComposable
fun pageFill(): Color = pageFillFor(LocalWallpaperActive.current, MaterialTheme.colorScheme.background)

/**
 * Fill for the app's persistent chrome: top app bars and the bottom navigation.
 *
 * Solid `surface` normally. Translucent once a wallpaper is behind the app, for the same reason the
 * page stops painting a background: a solid strip at the top and another at the bottom would frame
 * the image into a rectangle instead of letting it be a background. [WALLPAPER_CHROME_ALPHA] is
 * deliberately heavy — the bars carry the app's only labels over a photo the app did not choose.
 */
@Composable
@ReadOnlyComposable
fun chromeFill(): Color =
    MaterialTheme.colorScheme.surface.copy(alpha = chromeAlphaFor(LocalWallpaperActive.current))

/** The page fill for a known wallpaper state. Pure, so the rule is unit-tested. */
internal fun pageFillFor(
    wallpaperActive: Boolean,
    background: Color,
): Color = if (wallpaperActive) Color.Transparent else background

/** The chrome alpha for a known wallpaper state. Pure, so the rule is unit-tested. */
internal fun chromeAlphaFor(wallpaperActive: Boolean): Float =
    if (wallpaperActive) WALLPAPER_CHROME_ALPHA else 1f

/**
 * How much of `surface` a bar keeps over a wallpaper.
 *
 * 0.86: translucent enough that the image reads through the bar, dense enough that `onSurface`
 * labels keep their contrast on a bright part of the photo. Below ~0.7 the top bar starts losing
 * the title against a sky.
 */
internal const val WALLPAPER_CHROME_ALPHA = 0.86f
