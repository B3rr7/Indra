@file:Suppress("MagicNumber")

package org.indra.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A four-colour palette, applied to roles rather than dumped onto the screen.
 *
 * Colour Hunt palettes are four swatches chosen as a *set*; a theme needs roles (background,
 * surface, primary, on-primary, outline…). [Palette] keeps the swatches the palette is known by and
 * [scheme] derives the sixteen roles Material needs, so a palette can never leave the app with, say,
 * a dark blue "on-primary" on a dark blue button.
 *
 * Every palette here is dark-first: Indra is a dark-only app by decision (`Theme.kt`), and these are
 * dark palettes in the Color Hunt sense — the "Dark", "Night", "Space", "Retro" families that
 * dominate the popular page. A future light mode would need a second derivation, not a second list.
 */
@Immutable
data class Palette(
    val id: String,
    val name: String,
    /** Page background: the darkest swatch, or near it. */
    val background: Color,
    /** Cards and bars: one step above [background]. */
    val surface: Color,
    /** The accent that drives buttons, focus rings and the active tab. */
    val primary: Color,
    /** The second accent: secondary actions, hero gradients, selection. */
    val secondary: Color,
) {
    /** Text/icon colour for a filled [primary] surface, chosen by luminance, never assumed. */
    val onPrimary: Color get() = readableOn(primary)

    val onSecondary: Color get() = readableOn(secondary)

    /** Body text on the page. */
    val onBackground: Color get() = ON_DARK

    val onSurface: Color get() = ON_DARK

    /** Secondary text: a desaturated wash of the palette's own light end. */
    val onSurfaceVariant: Color get() = ON_DARK_MUTED

    val outline: Color get() = surface.blend(primary, OUTLINE_MIX)

    val outlineVariant: Color get() = surface.blend(primary, OUTLINE_VARIANT_MIX)

    /** A container tint of [primary] over [background], for the top bar and hero cards. */
    val primaryContainer: Color get() = background.blend(primary, CONTAINER_MIX)

    val onPrimaryContainer: Color get() = primary.blend(ON_DARK, ON_CONTAINER_MIX)

    val secondaryContainer: Color get() = background.blend(secondary, CONTAINER_MIX)

    val onSecondaryContainer: Color get() = secondary.blend(ON_DARK, ON_CONTAINER_MIX)

    val tertiary: Color get() = secondary.blend(primary, TERTIARY_MIX)

    val onTertiary: Color get() = readableOn(tertiary)

    val tertiaryContainer: Color get() = background.blend(tertiary, CONTAINER_MIX)

    val onTertiaryContainer: Color get() = tertiary.blend(ON_DARK, ON_CONTAINER_MIX)

    /** The hero gradient: primary into secondary, the palette's identity in one fill. */
    val heroGradient: List<Color> get() = listOf(primaryContainer, background.blend(secondary, HERO_MIX))

    /** The Material scheme this palette produces. */
    fun scheme(): ColorScheme =
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = ERROR,
            onError = ERROR_ON,
            errorContainer = ERROR_CONTAINER,
            onErrorContainer = ERROR_ON_CONTAINER,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
        )

    private companion object {
        val ON_DARK = Color(0xFFEDF2F7)
        val ON_DARK_MUTED = Color(0xFF9FB0C4)
        val ERROR = Color(0xFFFF6B6B)
        val ERROR_ON = Color(0xFF3A0007)
        val ERROR_CONTAINER = Color(0xFF5C1017)
        val ERROR_ON_CONTAINER = Color(0xFFFFDAD6)

        const val ON_CONTAINER_MIX = 0.35f
        const val OUTLINE_MIX = 0.35f
        const val OUTLINE_VARIANT_MIX = 0.16f
        const val CONTAINER_MIX = 0.16f
        const val TERTIARY_MIX = 0.5f
        const val HERO_MIX = 0.22f
    }
}

/**
 * Black or white, whichever is actually legible on [color].
 *
 * Compared by WCAG contrast ratio rather than by a luminance threshold: a mid-tone accent (gold,
 * coral) can sit on the wrong side of any single cutoff, and the threshold version picked white on
 * `#FCA311`, where white reads at 1.9:1. The two candidates are simply measured and the better one
 * wins — `PaletteTest` requires at least 4.5:1 for every palette.
 */
fun readableOn(color: Color): Color =
    if (contrastRatio(INK, color) >= contrastRatio(Color.White, color)) INK else Color.White

/** WCAG relative-luminance ratio between two colours (1.0–21.0). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + CONTRAST_OFFSET) / (minOf(la, lb) + CONTRAST_OFFSET)
}

private val INK = Color(0xFF06121A)
private const val CONTRAST_OFFSET = 0.05f

/** Linear blend in sRGB, which is what a tonal step is in practice. */
fun Color.blend(other: Color, fraction: Float): Color =
    Color(
        red = red + (other.red - red) * fraction,
        green = green + (other.green - green) * fraction,
        blue = blue + (other.blue - blue) * fraction,
        alpha = 1f,
    )

/**
 * The palette catalogue.
 *
 * Four operator sets, not a wardrobe of moods: each is one hue identity a pentest console can
 * live in — Ember (violet and signal orange, the default), Forest Terminal (green phosphor),
 * Ice Station (navy with ice and steel accents) and Solar Gold (amber on deep blue). Every one
 * is a *dark* set on a neutral base page, so tool output keeps its own meaning — severity
 * colours and transcripts read true on any of them — and the contrast rules above hold. The
 * swatches are the palette's own colours — the derivation decides which role each plays.
 *
 * A stored id that is no longer in the catalogue (older builds shipped more) falls back to
 * [default] through [byId] — the one path every reader of the preference takes.
 */
@Suppress("MagicNumber") // A catalogue is data: each literal is a swatch from the palette itself.
object Palettes {
    val Ember =
        Palette(
            id = "ember",
            name = "Ember",
            background = Color(0xFF12081B),
            surface = Color(0xFF1C0F29),
            primary = Color(0xFFC77DFF),
            secondary = Color(0xFFFF8906),
        )

    val Forest =
        Palette(
            id = "forest",
            name = "Forest Terminal",
            background = Color(0xFF08120E),
            surface = Color(0xFF10201A),
            primary = Color(0xFF52B788),
            secondary = Color(0xFFD8F3DC),
        )

    val Ice =
        Palette(
            id = "ice",
            name = "Ice Station",
            background = Color(0xFF03045E),
            surface = Color(0xFF071A6B),
            primary = Color(0xFF90E0EF),
            secondary = Color(0xFF4895EF),
        )

    val Solar =
        Palette(
            id = "solar",
            name = "Solar Gold",
            background = Color(0xFF0B1524),
            surface = Color(0xFF14213D),
            primary = Color(0xFFFCA311),
            secondary = Color(0xFF4CC9F0),
        )

    /** Everything the picker offers, in picker order. */
    val all: List<Palette> = listOf(Ember, Forest, Ice, Solar)

    /** The palette used when nothing has been chosen (and when a stored id is unknown). */
    val default: Palette = Ember

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: default
}

/**
 * The palette the app is currently themed with.
 *
 * Provided by [IndraTheme] and read by anything that needs a palette-level colour (the hero
 * gradient, a wallpaper tint) rather than a role from `MaterialTheme.colorScheme`.
 */
val LocalPalette = staticCompositionLocalOf { Palettes.default }
