package org.indra.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the palette derivation, because a palette is four swatches and the app needs sixteen roles.
 *
 * The failure mode this guards against is not cosmetic: a palette whose primary is pale and whose
 * `onPrimary` was assumed white renders an unreadable button, and a background that is lighter than
 * its surface makes every card disappear into the page.
 */
class PaletteTest {
    /** WCAG relative-luminance ratio, the number a designer would check. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun `every palette is dark and its surface sits above its page`() {
        Palettes.all.forEach { palette ->
            assertTrue("${palette.id}: background is too light", palette.background.luminance() < 0.12f)
            assertTrue(
                "${palette.id}: surface must be at least as light as the page",
                palette.surface.luminance() >= palette.background.luminance(),
            )
        }
    }

    @Test
    fun `label text is readable on every page and card`() {
        Palettes.all.forEach { palette ->
            assertTrue(
                "${palette.id}: onBackground contrast ${contrast(palette.onBackground, palette.background)}",
                contrast(palette.onBackground, palette.background) >= READABLE,
            )
            assertTrue(
                "${palette.id}: onSurfaceVariant contrast ${contrast(palette.onSurfaceVariant, palette.surface)}",
                contrast(palette.onSurfaceVariant, palette.surface) >= MUTED_READABLE,
            )
        }
    }

    @Test
    fun `filled buttons choose the text colour that is legible`() {
        Palettes.all.forEach { palette ->
            assertTrue(
                "${palette.id}: onPrimary contrast ${contrast(palette.onPrimary, palette.primary)}",
                contrast(palette.onPrimary, palette.primary) >= READABLE,
            )
            assertTrue(
                "${palette.id}: onSecondary contrast ${contrast(palette.onSecondary, palette.secondary)}",
                contrast(palette.onSecondary, palette.secondary) >= READABLE,
            )
        }
    }

    @Test
    fun `the scheme carries the palette's own colours`() {
        Palettes.all.forEach { palette ->
            val scheme = palette.scheme()
            assertEquals(palette.id, palette.primary, scheme.primary)
            assertEquals(palette.id, palette.background, scheme.background)
            assertEquals(palette.id, palette.surface, scheme.surface)
            assertEquals(palette.id, palette.onPrimary, scheme.onPrimary)
        }
    }

    @Test
    fun `ids are unique and every palette is in the catalogue`() {
        val ids = Palettes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Palettes.all.forEach { assertTrue(Palettes.byId(it.id) === it) }
    }

    @Test
    fun `an unknown or empty id falls back to the default`() {
        assertEquals(Palettes.default, Palettes.byId("no-such-palette"))
        assertEquals(Palettes.default, Palettes.byId(null))
        assertEquals(Palettes.default, Palettes.byId(""))
    }

    @Test
    fun `a pale accent gets dark text and a deep one gets white`() {
        assertEquals(Color(0xFF06121A), readableOn(Color(0xFFC6FF00)))
        assertEquals(Color.White, readableOn(Color(0xFF311B92)))
    }

    @Test
    fun `the catalogue is varied rather than eight shades of one hue`() {
        // The complaint that started the palette work was "cyan everywhere": a catalogue whose
        // primaries all hug one hue would recreate it.
        val hues = Palettes.all.map { it.primary.hueBucket() }
        assertTrue("palettes repeat a hue family: $hues", hues.toSet().size >= MIN_DISTINCT_HUES)
        assertNotEquals(Palettes.all.first().primary, Palettes.all.last().primary)
    }

    /** Six coarse buckets (60° each): enough to prove the catalogue is not monochrome. */
    private fun Color.hueBucket(): Int {
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (delta == 0f) return -1
        val hue =
            when (max) {
                red -> 60f * (((green - blue) / delta) % 6f)
                green -> 60f * (((blue - red) / delta) + 2f)
                else -> 60f * (((red - green) / delta) + 4f)
            }
        return (((hue + 360f) % 360f) / 60f).toInt()
    }

    private companion object {
        const val READABLE = 4.5f

        /** Muted body text is allowed WCAG's large-text ratio. */
        const val MUTED_READABLE = 3.0f
        const val MIN_DISTINCT_HUES = 4
    }
}
