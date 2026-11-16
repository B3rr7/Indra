package org.indra.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallpaper surface rules.
 *
 * The bug being pinned: a wallpaper could be picked, stored and decoded, and the user never saw
 * it. `WallpaperBackground` painted the image, then the host `Scaffold` painted
 * `MaterialTheme.colorScheme.background` over the whole window — the default `containerColor` —
 * and every destination's `Scaffold` did it again. Nothing was broken in the picker; the page
 * fill was opaque.
 *
 * These functions are the pure half of the fix: the composables read the ambient, these decide
 * what the ambient means, so "the page must be transparent over a wallpaper" is a test and not a
 * convention someone can quietly undo.
 */
class WallpaperSurfaceTest {
    @Test
    fun `a page is transparent when an image is behind it`() {
        val background = Color(0xFF101014)

        assertEquals(Color.Transparent, pageFillFor(wallpaperActive = true, background = background))
    }

    @Test
    fun `a page keeps the theme background when no image is set`() {
        // Transparent with nothing behind it would be an undefined page colour — the window's own
        // background showing through edge to edge. The image wrapper paints the theme colour even
        // with no wallpaper, but a destination must not depend on being wrapped.
        val background = Color(0xFF101014)

        assertEquals(background, pageFillFor(wallpaperActive = false, background = background))
    }

    @Test
    fun `chrome is solid without a wallpaper`() {
        assertEquals(1f, chromeAlphaFor(wallpaperActive = false), 0f)
    }

    @Test
    fun `chrome stays translucent but dense over a wallpaper`() {
        val alpha = chromeAlphaFor(wallpaperActive = true)

        assertTrue("the image must show through the bar", alpha < 1f)
        // The bars carry the app's only labels over a photo the app did not choose. Below ~0.7 a
        // bright sky costs the title its contrast; at 1.0 the image is framed out of the bar.
        assertTrue("the bar must not dissolve into the image", alpha >= 0.7f)
        assertEquals(WALLPAPER_CHROME_ALPHA, alpha, 0f)
    }
}
