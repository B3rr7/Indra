@file:Suppress("MagicNumber")

package org.indra.android.ui.theme

import androidx.compose.ui.graphics.Color

// The theme's colours now come from a palette (`Palette.kt`), role by role. What is left here is
// the part that is *not* a palette: status semantics (a scan running is green whatever the palette
// is) and the two anchors the accent system derives from.

/** The page background of the default palette — the anchor `Accent.soft` mixes towards. */
val IndraBackgroundDark = Color(0xFF12081B)

// Status colours are semantic, not decorative: they must mean the same thing under every palette,
// which is why they are constants rather than palette roles.
val StatusGreen = Color(0xFF00E676)
val StatusRed = Color(0xFFFF1744)
val StatusOrange = Color(0xFFFF9100)
val StatusBlue = Color(0xFF2979FF)
val StatusCyan = Color(0xFF00E5FF)
val StatusPurple = Color(0xFFAA00FF)
