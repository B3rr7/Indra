package org.indra.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Flat 2.0 surface policy.
 *
 * The bug being pinned: the app mixed depth cues. The Home tool cards carried a 2dp shadow, the
 * Native Core card was an `ElevatedCard` at 3dp, and the two hero cards used a horizontal gradient
 * — three different ways of saying "this is above the page", none of them visible on a dark theme
 * and all of them costing a render pass. Flat 2.0 picks one: a tonal fill plus a hairline.
 *
 * These are constants, so the test is cheap — but it is the only thing stopping the next card from
 * typing `defaultElevation = 2.dp` and quietly undoing the policy.
 */
class FlatSurfaceTest {
    @Test
    fun `nothing is elevated`() {
        // Elevation is the shadow, and a flat design has none. If a raised surface is ever wanted
        // it has to be added here deliberately, which is the whole point of the constant.
        assertEquals(0, Flat.elevation.value.toInt())
    }

    @Test
    fun `the edge is a hairline`() {
        assertEquals(1, Flat.hairline.value.toInt())
        assertTrue("a hairline must be visible", Flat.BORDER_ALPHA > 0f)
        assertTrue("a hairline must not read as a drawn box", Flat.BORDER_ALPHA <= 0.6f)
    }

    @Test
    fun `semi-flat keeps a tonal lift`() {
        // Fully flat would put every card on the page colour, and with no shadow the layout
        // collapses into one sheet. The tint is what makes a card a card.
        assertTrue("a surface must be tinted", Flat.SURFACE_ALPHA > 0f)
        assertTrue("a surface must not be a second background", Flat.SURFACE_ALPHA <= 0.6f)
    }

    @Test
    fun `every surface rounds the same way`() {
        assertTrue("a flat surface needs a radius", Flat.corner.value > 0f)
        assertEquals(Flat.corner, Flat.shape.let { Flat.corner })
    }
}
