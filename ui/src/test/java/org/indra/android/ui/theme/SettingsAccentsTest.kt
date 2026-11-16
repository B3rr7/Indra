package org.indra.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the derivation Settings' seventeen accents come from.
 *
 * The failure this guards against is the one that shipped: Settings carried its own list of hues, so
 * the palette picker recoloured the whole app and left Settings exactly as it was. The table is now
 * generated from the palette's primary, which makes four properties load-bearing — every accent is
 * distinct, every accent stays inside the palette's own hue band, every accent stays legible on a
 * near-black page, and the three status colours never move.
 */
class SettingsAccentsTest {
    @Test
    fun `every palette yields fourteen distinct accents`() {
        Palettes.all.forEach { palette ->
            val accents = settingsAccentsFor(palette.primary).decorative()
            assertEquals("${palette.id}: a duplicated accent makes two rows read as one", 14, accents.toSet().size)
            closestPair(accents).let { gap ->
                assertTrue("${palette.id}: two accents are only $gap apart", gap > MIN_PAIR_DISTANCE)
            }
        }
    }

    @Test
    fun `accents stay inside the palette's own hue band`() {
        // The point of rotating the primary's hue rather than listing hues: a green palette must give
        // Settings greens, teals and limes — never an orange, which would be a second palette.
        Palettes.all.forEach { palette ->
            val anchor = hueOf(palette.primary)
            settingsAccentsFor(palette.primary).decorative().forEach { accent ->
                val gap = hueGap(hueOf(accent), anchor)
                assertTrue("${palette.id}: a hue ${gap.toInt()}° from the anchor is not this palette", gap <= ARC_LIMIT)
            }
        }
    }

    @Test
    fun `the ramp is centred on the palette's hue`() {
        // Rotation must straddle the anchor rather than start at one end of the arc, otherwise the
        // whole table leans away from the palette it is supposed to belong to.
        Palettes.all.forEach { palette ->
            val anchor = hueOf(palette.primary)
            val accents = settingsAccentsFor(palette.primary).decorative()
            val nearest = accents.minOf { hueGap(hueOf(it), anchor) }
            assertTrue("${palette.id}: nearest accent is ${nearest.toInt()}° off the anchor", nearest <= CENTRED_LIMIT)
        }
    }

    @Test
    fun `every accent is visible on a near-black page`() {
        // A pale palette (Ice) and a desaturated one (Forest) both have to clear this: an accent that
        // is too dark is an invisible icon, which is how the theme looked before the tonal table.
        Palettes.all.forEach { palette ->
            settingsAccentsFor(palette.primary).decorative().forEach { accent ->
                val brightest = maxOf(accent.red, accent.green, accent.blue)
                assertTrue("${palette.id}: accent $accent is too dark", brightest >= MIN_VALUE)
                assertTrue("${palette.id}: accent $accent is too bright", brightest <= MAX_VALUE)
            }
        }
    }

    @Test
    fun `status colours do not follow the palette`() {
        // Green means alive and red means dead in every palette: a red palette painting "Running"
        // red is a status colour that stopped being a status colour.
        Palettes.all.forEach { palette ->
            val accents = settingsAccentsFor(palette.primary)
            val alive = accents.running
            assertTrue("${palette.id}: running is not green", alive.green > alive.red && alive.green > alive.blue)
            val dead = accents.stopped
            assertTrue("${palette.id}: stopped is not red", dead.red > dead.green && dead.red > dead.blue)
            val attention = accents.attention
            assertTrue("${palette.id}: attention is not amber", attention.red > attention.blue && attention.green > attention.blue)
        }
    }

    @Test
    fun `a palette change recolours settings`() {
        // The defect in one line: two palettes used to produce identical Settings colours.
        val ember = settingsAccentsFor(Palettes.Ember.primary).decorative()
        val solar = settingsAccentsFor(Palettes.Solar.primary).decorative()
        val shared = ember.zip(solar).count { (a, b) -> a == b }
        assertTrue("Ember and Solar share $shared of 14 accents", shared < 4)
    }

    private fun SettingsAccents.decorative(): List<Color> =
        listOf(
            appearance, network, ports, httpServer, https, msfRpc, general,
            debugLog, wallpaper, core, ota, appUpdate, about, license,
        )

    /** Smallest euclidean distance between any two accents, in 0…1 channel space. */
    private fun closestPair(accents: List<Color>): Float {
        var smallest = Float.MAX_VALUE
        for (i in accents.indices) {
            for (j in i + 1 until accents.size) {
                smallest = minOf(smallest, distance(accents[i], accents[j]))
            }
        }
        return smallest
    }

    private fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    /** Hue in degrees. Uniform channel offsets do not move it, so the ramp's lift is irrelevant here. */
    private fun hueOf(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        val hue =
            when {
                delta == 0f -> 0f
                color.red >= max - CHANNEL_EPSILON -> 60f * ((color.green - color.blue) / delta).mod(6f)
                color.green >= max - CHANNEL_EPSILON -> 60f * (((color.blue - color.red) / delta) + 2f)
                else -> 60f * (((color.red - color.green) / delta) + 4f)
            }
        return hue.mod(360f)
    }

    private fun hueGap(a: Float, b: Float): Float {
        val gap = abs(a - b).mod(360f)
        return minOf(gap, 360f - gap)
    }


    private companion object {
        /** Two accents closer than this read as one colour on a phone-sized tile. */
        const val MIN_PAIR_DISTANCE = 0.05f

        /** The ramp spans ±90°; the extra degree absorbs float rounding. */
        const val ARC_LIMIT = 91f

        /** Half a ramp step: the nearest accent must still sit on the palette's own hue. */
        const val CENTRED_LIMIT = 14f

        /** Mirrors the clamp in `settingsAccentsFor`. */
        const val MIN_VALUE = 0.6f

        /** Any accent brighter than this would compete with the tool hues on the same page. */
        const val MAX_VALUE = 0.93f

        const val CHANNEL_EPSILON = 1e-6f
    }
}
