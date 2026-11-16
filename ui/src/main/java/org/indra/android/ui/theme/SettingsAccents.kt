@file:Suppress("MagicNumber") // the ramp is geometry: degrees around a hue circle and slot indices.

package org.indra.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Settings' accent table, generated from the active palette instead of being a second hard table.
 *
 * Settings is the one surface with seventeen decorative rows, so it had its own list of hues — and
 * that list is why switching palettes never changed Settings while it changed everything else. A
 * palette has two anchors and Settings needs fourteen hues, so each accent is the palette's primary
 * **hue rotated** within a ±90° arc: a green palette gives Settings greens, teals and limes, an
 * amber one gives ambers, oranges and golds. The page still belongs to the palette; the rows still
 * have their own identity.
 *
 * Three rules keep it from becoming decoration:
 *
 * * **Status is not identity.** [running] is green, [stopped] is red and [attention] is amber in
 *   every palette. A red palette must not paint "Running" red — a status colour that changes with a
 *   theme is no longer a status colour.
 * * **Consecutive roles are half an arc apart.** The four port rows are stacked inside one card;
 *   neighbouring hues 14° apart read as one colour, so [ROLE_SLOTS] interleaves the arc instead of
 *   walking it.
 * * **Saturation and value are clamped.** A palette's primary decides the *hue*, not the legibility:
 *   a pale primary (`Ice` `#90E0EF`) and a desaturated one (`Forest` `#52B788`) both have to produce
 *   fourteen accents that are visible on a near-black page.
 *
 * Derivation is pure and palette-agnostic — `SettingsAccentsTest` pins distinctness, the hue band,
 * the clamp and the semantic colours for every shipped palette.
 */
@Suppress("LongParameterList") // A table is data: one named field per role, exactly like Palette itself.
@Immutable
data class SettingsAccents(
    val appearance: Color,
    val network: Color,
    val ports: Color,
    val httpServer: Color,
    val https: Color,
    val msfRpc: Color,
    val general: Color,
    val debugLog: Color,
    val wallpaper: Color,
    val core: Color,
    val ota: Color,
    val appUpdate: Color,
    val about: Color,
    val license: Color,
) {
    /** Alive, installed, granted. Green in every palette. */
    val running: Color get() = STATUS_ALIVE

    /** Missing, stopped, failed. Red in every palette. */
    val stopped: Color get() = STATUS_DEAD

    /** Healthy but needs a look: rootless mode, a stopped daemon, a wake lock held. */
    val attention: Color get() = STATUS_ATTENTION

    private companion object {
        val STATUS_ALIVE = Color(0xFF00E676)
        val STATUS_DEAD = Color(0xFFFF5252)
        val STATUS_ATTENTION = Color(0xFFFFC400)
    }
}

/**
 * The accents currently in force.
 *
 * Provided by [IndraTheme] from the same [Palette] it themes with, and anchored on the *animated*
 * primary, so Settings recolours with the same 420 ms cross-fade the rest of the theme uses rather
 * than snapping while every other surface eases.
 */
val LocalSettingsAccents = staticCompositionLocalOf { settingsAccentsFor(Palettes.default.primary) }

/** The number of decorative roles, i.e. the length of the ramp. */
private const val RAMP_SIZE = 14

/** A hue arc wide enough to separate fourteen roles, narrow enough to stay one palette's family. */
private const val ARC_DEGREES = 180f

/** Keeps a desaturated palette (`Forest`, `Crimson`) from generating grey accents. */
private const val MIN_SATURATION = 0.35f

/** Keeps a pale palette (`Ice`) from generating accents that vanish on a dark page. */
private const val MIN_VALUE = 0.62f

/** Keeps a saturated palette (`Neon`, `Mint`) from generating neon that beats the tool hues. */
private const val MAX_VALUE = 0.92f

/**
 * One slot per role, in arc order 0…13 interleaved low/high: role *n* and role *n + 1* are half an
 * arc apart, so two rows in the same card are never two neighbouring hues.
 */
private val ROLE_SLOTS = intArrayOf(0, 7, 1, 8, 2, 9, 3, 10, 4, 11, 5, 12, 6, 13)

/** The whole accent table for one anchor colour. Pure: same input, same table. */
internal fun settingsAccentsFor(primary: Color): SettingsAccents {
    val tone = Tone.of(primary)
    val ramp = List(RAMP_SIZE) { tone.rotated(degreeOfRole(it)) }
    return SettingsAccents(
        appearance = ramp[0],
        network = ramp[1],
        ports = ramp[2],
        httpServer = ramp[3],
        https = ramp[4],
        msfRpc = ramp[5],
        general = ramp[6],
        debugLog = ramp[7],
        wallpaper = ramp[8],
        core = ramp[9],
        ota = ramp[10],
        appUpdate = ramp[11],
        about = ramp[12],
        license = ramp[13],
    )
}

private fun degreeOfRole(role: Int): Float =
    -ARC_DEGREES / 2f + ROLE_SLOTS[role] * (ARC_DEGREES / (RAMP_SIZE - 1))

/**
 * A colour as hue, saturation and value.
 *
 * Hand-written rather than `android.graphics.Color.RGBToHSV` for one reason: this is theme data and
 * `:ui` unit tests run on the JVM, where `android.graphics` returns defaults. A ramp that cannot be
 * asserted is a ramp nobody can change safely.
 */
private class Tone(
    private val hue: Float,
    private val saturation: Float,
    private val value: Float,
) {
    fun rotated(degrees: Float): Color = fromHsv((hue + degrees).mod(360f), saturation, value)

    companion object {
        fun of(color: Color): Tone {
            val max = maxOf(color.red, color.green, color.blue)
            val min = minOf(color.red, color.green, color.blue)
            val delta = max - min
            val h =
                when {
                    delta == 0f -> 0f
                    max == color.red -> 60f * ((color.green - color.blue) / delta).mod(6f)
                    max == color.green -> 60f * (((color.blue - color.red) / delta) + 2f)
                    else -> 60f * (((color.red - color.green) / delta) + 4f)
                }
            val s = if (max <= 0f) 0f else delta / max
            return Tone(
                hue = h,
                saturation = s.coerceIn(MIN_SATURATION, 1f),
                value = max.coerceIn(MIN_VALUE, MAX_VALUE),
            )
        }
    }
}

private fun fromHsv(hue: Float, saturation: Float, value: Float): Color {
    val chroma = value * saturation
    val secondary = chroma * (1f - abs((hue / 60f).mod(2f) - 1f))
    val (r, g, b) =
        when {
            hue < 60f -> Triple(chroma, secondary, 0f)
            hue < 120f -> Triple(secondary, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, secondary)
            hue < 240f -> Triple(0f, secondary, chroma)
            hue < 300f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
    val lift = value - chroma
    return Color(r + lift, g + lift, b + lift)
}
