package org.indra.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.indra.android.ui.theme.Accent
import org.indra.android.ui.theme.Brand
import org.indra.android.ui.theme.LocalAccent
import org.indra.android.ui.theme.accentFor
import org.indra.android.ui.theme.chromeFill
import org.indra.android.ui.theme.pageFill

/**
 * The one plugin screen frame: back arrow, title, optional actions, and the content slot.
 *
 * Every plugin screen used to declare its own `Scaffold` + `TopAppBar` + `IconButton` +
 * `Icons.AutoMirrored.Filled.ArrowBack` (36 copies, drifted in padding and title style). All 36
 * plugin screens now use this frame.
 *
 * The frame carries the **tool's own colour** (the same [accentFor] hue its card uses on Home), so
 * a screen is recognisable at a glance and the back arrow, the title rule and the working spinner
 * all agree. Before this, all 36 screens rendered an identical cyan-on-black bar — the colour
 * existed only on the card that opened them.
 *
 * The `actions` slot is the only extension point: a screen that needs a trailing button (Arsenal's
 * add, Handshakes' refresh/export) passes it here rather than reintroducing a `TopAppBar`.
 *
 * @param pluginId the plugin's registry id; omit for a screen that has no tool (the frame then uses
 *   [Brand]). Passing it is one word, and it is what makes the accent automatic.
 * @param actions trailing `TopAppBar` actions, e.g. a Clear button.
 * @param content receives the scaffold's padding values; it owns its own layout so a
 *   screen that needs a `LazyColumn`/`weight(1f)` body is not forced into a Column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    pluginId: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val accent = accentFor(pluginId)
    val base = MaterialTheme.colorScheme
    // Two things happen here, and both are why the accent lives in the frame:
    //
    // 1. the tool's accent is published as `LocalAccent`, so consoles, chips and status pills read
    //    it instead of every screen threading a parameter down;
    // 2. the Material scheme's *primary* becomes the accent, so buttons, switches, sliders,
    //    progress bars and focused field outlines are the tool's colour without one call-site
    //    change. `onPrimary` is computed from the accent's luminance, so a lime tool gets black
    //    text and an indigo tool gets white.
    val toolScheme =
        base.copy(
            primary = accent.base,
            onPrimary = accent.onAccent,
            primaryContainer = accent.fill(CONTAINER_ALPHA).compositeOver(base.surface),
            onPrimaryContainer = accent.soft,
        )
    CompositionLocalProvider(LocalAccent provides accent) {
    MaterialTheme(colorScheme = toolScheme) {
    Scaffold(
        // The page is transparent so a wallpaper behind the app is the background; with none set
        // this resolves to the theme background and the screen looks exactly as it did.
        containerColor = pageFill(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = accent.base,
                            )
                        }
                    },
                    actions = actions,
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = chromeFill(),
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )
                // The accent rule: 2dp of the tool's gradient under the bar. It is the one piece of
                // colour that survives a screen with no accent-tinted content, and it costs one box.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(2.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(accent.base, accent.deep, Brand.base.copy(alpha = 0f)),
                                ),
                                shape = RoundedCornerShape(2.dp),
                            ),
                )
            }
        },
        content = content,
    )
    }
    }
}

/** The accent a screen uses for its own content when it has no plugin id of its own. */
internal val NeutralAccent: Accent get() = Brand.let { Accent(it.base, it.deep) }

/** How much of the tool's accent the primary container carries: enough to read as a tint. */
private const val CONTAINER_ALPHA = 0.18f
