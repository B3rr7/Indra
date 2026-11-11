package org.indra.android.core.system.terminal

import kotlin.math.roundToInt

/**
 * The transcript's type scale: how large a line is drawn, and how far the zoom control may move it.
 *
 * Pure, so the rules that are easy to get wrong are unit-tested instead of eyeballed on a device:
 * the interesting cases are all at the ends — zooming past the maximum, a floating panel two points
 * smaller than the screen, and a panel whose size must not follow the screen below a readable
 * floor.
 */
object TerminalType {
    /** `ip addr` fits at 10 and `ls -l` is readable; below that the terminal is a wall of noise. */
    const val MIN = 10
    const val MAX = 20
    const val STEP = 2

    /** What the console shipped with before it was adjustable. */
    const val DEFAULT = 12

    /** The floating panel is deliberately smaller than the full screen: it covers other apps. */
    const val COMPACT_DELTA = 2

    /** The panel's own floor — a 6sp line is not legible to anybody. */
    const val COMPACT_MIN = 9

    /** Leading as a multiple of the font size: 12sp/17sp is the ratio the console shipped with. */
    private const val LINE_RATIO = 1.4166f

    fun normalize(size: Int): Int = size.coerceIn(MIN, MAX)

    /** One press of the zoom rocker; [direction] is +1 for zoom in, -1 for zoom out. */
    fun step(
        current: Int,
        direction: Int,
    ): Int = normalize(normalize(current) + direction * STEP)

    /** The size a line is actually drawn at on this surface. */
    fun fontFor(
        size: Int,
        compact: Boolean,
    ): Int = if (compact) (normalize(size) - COMPACT_DELTA).coerceAtLeast(COMPACT_MIN) else normalize(size)

    /** Leading that keeps the ratio the default size has, so a zoomed line still reads as one. */
    fun lineHeight(font: Int): Int = (font * LINE_RATIO).roundToInt()

    fun canZoomIn(size: Int): Boolean = normalize(size) < MAX

    fun canZoomOut(size: Int): Boolean = normalize(size) > MIN
}
