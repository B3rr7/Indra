@file:Suppress("MagicNumber")

package org.indra.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * One tool's colour, as three tones rather than one hex.
 *
 * A single accent hex is why the plugin screens all looked the same: an icon tinted from it is
 * barely visible, so every header fell back to the theme's cyan. The three tones give a tile a
 * vertical gradient ([base] → [deep]), a border ([edge]), and a wash [fill] that reads as colour
 * without competing with body text.
 *
 * [onAccent] is the text colour that is legible on [base], computed from its luminance instead of
 * assumed to be black or white — the palette spans lime (needs black) and indigo (needs white).
 */
@Immutable
data class Accent(
    val base: Color,
    val deep: Color,
) {
    /** Body-text-safe tint of this accent on a dark surface. */
    val soft: Color get() = base.lerp(IndraBackgroundDark, SOFT_MIX)

    /** Text/icon colour for a filled accent surface (a button, a pill). */
    val onAccent: Color get() = if (base.luminance() > LIGHT_TEXT_LUMINANCE) Color(0xFF06121A) else Color.White

    /** A wash for list rows and chips. */
    fun fill(alpha: Float = FILL_ALPHA): Color = base.copy(alpha = alpha)

    /** The tile gradient: bright at the top, deep at the bottom. */
    val tile: Brush get() = Brush.verticalGradient(listOf(base.lerp(deep, TILE_MIX), deep))

    /** A hairline that follows the tile, so a card's edge carries the tool's colour. */
    fun edge(alpha: Float = EDGE_ALPHA): Color = base.copy(alpha = alpha)

    private companion object {
        const val SOFT_MIX = 0.35f
        const val TILE_MIX = 0.15f
        const val FILL_ALPHA = 0.14f
        const val EDGE_ALPHA = 0.45f
        const val LIGHT_TEXT_LUMINANCE = 0.55f
    }
}

private fun Color.lerp(other: Color, fraction: Float): Color =
    Color(
        red = red + (other.red - red) * fraction,
        green = green + (other.green - green) * fraction,
        blue = blue + (other.blue - blue) * fraction,
        alpha = 1f,
    )

/**
 * The tool palette: one stable hue per plugin id, no repeats.
 *
 * Kept in `:ui` rather than beside a screen because three places need the same answer — the Home
 * and Tools cards, a plugin screen's header, and the settings list. Two copies of this table is how
 * a tool ends up orange on one screen and cyan on the next.
 *
 * Adding a plugin: give it a hue here. [accentFor] falls back to [Brand] so a missing entry is a
 * neutral screen, never a crash.
 */
@Suppress(
    "MagicNumber", // A palette is data: each literal is a hue with its name in the comment.
    "CyclomaticComplexMethod", // One branch per tool id, by design.
)
fun accentFor(id: String?): Accent {
    val base =
        when (id) {
            // Recon
            "port_scanner" -> Color(0xFF00E5FF) // cyan
            "inspector" -> Color(0xFF448AFF) // blue
            "traceroute" -> Color(0xFF7C4DFF) // violet
            "network_radar" -> Color(0xFF00BFA5) // sea green
            "masscan" -> Color(0xFF00B0FF) // light blue
            "geomac" -> Color(0xFF2979FF) // strong blue
            // Offensive
            "exploit_finder" -> Color(0xFFFF9800) // orange
            "msf" -> Color(0xFFFF3D00) // Metasploit's own accent
            "mitm" -> Color(0xFFFF5252) // red
            "one_tap_mitm" -> Color(0xFFFF4081) // pink
            "packet_forger" -> Color(0xFFE040FB) // magenta
            "router_pwn" -> Color(0xFFFF6D00) // deep orange
            "searchsploit" -> Color(0xFFAEEA00) // acid lime
            "arsenal" -> Color(0xFFD500F9) // purple
            // Wireless
            "wifi_audit" -> Color(0xFF00E676) // green
            "wifi_scanner" -> Color(0xFF1DE9B6) // teal
            "wifite" -> Color(0xFF64DD17) // light green
            "reaver" -> Color(0xFFFFC400) // amber
            "handshakes" -> Color(0xFF00B8D4) // aqua
            "whisperpair" -> Color(0xFF3D5AFE) // bluetooth blue
            "wifi_keygen" -> Color(0xFF76FF03) // signal green
            "link_probe" -> Color(0xFFF50057) // interference pink
            // Web / service enumeration
            "vuln_scanner" -> Color(0xFFC6FF00) // lime
            "web_tester" -> Color(0xFF18FFFF) // bright aqua
            "gobuster" -> Color(0xFFFFAB00) // gold
            "bettercap" -> Color(0xFF651FFF) // deep purple
            // Credentials
            "login_cracker" -> Color(0xFFFFD600) // yellow
            "credential_tester" -> Color(0xFFFFA000) // deeper gold
            "password_auditor" -> Color(0xFFFF8A80) // coral
            // Traffic / interception
            "packet_capture" -> Color(0xFF536DFE) // indigo
            "session_hijack" -> Color(0xFFFF1744) // crimson
            "dns_spoofer" -> Color(0xFFB388FF) // lavender
            // Device / system
            "mac_changer" -> Color(0xFFB0BEC5) // slate
            "hid" -> Color(0xFF7C4DFF) // violet
            "duckyscript" -> Color(0xFFAA00FF) // electric purple
            "usb_arsenal" -> Color(0xFF64FFDA) // mint
            "iptables" -> Color(0xFFFF7A00) // fire
            "terminal" -> Color(0xFF00E676) // terminal green
            "msf_console" -> Color(0xFFFF3D00)
            else -> Brand.base
        }
    return Accent(base = base, deep = base.lerp(Color.Black, DEEP_MIX))
}

/**
 * The accent of the surface being composed.
 *
 * [PluginScaffold] provides the tool's own accent here, so a console, a chip or a status pill
 * rendered anywhere inside a plugin screen picks up that tool's colour without every screen
 * threading a parameter through its own call tree — and without widening `LiveOutputCard`'s
 * parameter list for a value the frame already knows.
 */
val LocalAccent = staticCompositionLocalOf { Accent(Brand.base, Brand.deep) }

/** The app's own accent, used where no tool owns the surface. */
object Brand {
    val base = Color(0xFF22D3EE)
    val deep = Color(0xFF7C3AED)

    val gradient: Brush get() = Brush.linearGradient(listOf(base, deep))
}

/** 0.45 takes a bright hue to a tile's bottom without turning it into a different colour family. */
private const val DEEP_MIX = 0.45f

/** The palette entry a screen should use for a plugin id, resolved once per composition. */
@Composable
fun rememberAccent(id: String?): Accent = accentFor(id)

/**
 * Section headings ("Quick Actions", "Security Tools") share one treatment: small, wide-tracked,
 * and dimmed, so they read as structure rather than as another card title.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )
        trailing?.invoke()
    }
}
