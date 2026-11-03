package org.indra.android.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
// Wildcards on purpose: `Icons.Filled.Home` and `Icons.Outlined.Home` are two extension
// properties with the same simple name, so importing both by name does not compile.
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import org.indra.android.ui.theme.StatusCyan
import org.indra.android.ui.theme.StatusOrange
import org.indra.android.ui.theme.StatusPurple

// Geometry and motion constants. Every literal lives here so the layout reads as a shape rather
// than a pile of magic numbers (detekt MagicNumber).
private val BAR_HEIGHT = 62.dp
private val BAR_PADDING_H = 8.dp
private val BAR_PADDING_V = 6.dp
private val TAB_RADIUS = 14.dp
private val PILL_RADIUS = 12.dp
private val PILL_H_PADDING = 18.dp
private val PILL_V_PADDING = 2.dp
private val ICON_SIZE = 22.dp
private val LABEL_TOP = 2.dp
private val LABEL_SIZE = 11.sp

private const val DIVIDER_ALPHA = 0.5f
private const val PILL_ALPHA = 0.16f

/**
 * Flat 2.0 bottom navigation.
 *
 * A solid `surface` bar with one hairline divider and **no** elevation, glow or gradient. Every
 * tab shows its icon and label at all times (balanced, minimal), and the selected tab owns an
 * accent colour from the shared status palette (Home cyan, Tools orange, Settings purple) that
 * tints its icon, its label and a small flat pill behind the icon.
 *
 * Tabs are equal width so the bar never shifts as the selection changes; only the colour and the
 * pill cross-fade. It is a plain `selectable(role = Tab)` row inside a `selectableGroup`, which
 * keeps screen-reader semantics intact without a Material `NavigationBar`'s own indicator.
 */
@Composable
fun BottomNavBar(
    navigationState: NavigationState,
    onNavigateToTab: (NavKey) -> Unit,
) {
    val items =
        listOf(
            BottomNavItem("Home", Screen.Home, Icons.Filled.Home, Icons.Outlined.Home, StatusCyan),
            BottomNavItem("Tools", Screen.Tools, Icons.Filled.Build, Icons.Outlined.Build, StatusOrange),
            BottomNavItem("Settings", Screen.Settings, Icons.Filled.Settings, Icons.Outlined.Settings, StatusPurple),
        )

    val currentRoute = navigationState.currentRoute
    val matchedIndex = items.indexOfFirst { it.route == currentRoute }
    // Plugin screens live in the same graph, so their route matches no tab. Remembering the last
    // real tab keeps its accent lit while a tool is open instead of snapping back to Home.
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(matchedIndex) { if (matchedIndex >= 0) lastIndex = matchedIndex }
    val selectedIndex = if (matchedIndex >= 0) matchedIndex else lastIndex

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // A hairline instead of a shadow: the flat separation other Indra surfaces use.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = BAR_PADDING_H, vertical = BAR_PADDING_V)
                    .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                BottomNavEntry(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onNavigateToTab(item.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One tab: a flat accent pill behind the icon plus an always-visible label.
 *
 * Colour does the selection work — both the icon and the label animate to the tab's accent when
 * selected and rest at `onSurfaceVariant` otherwise.
 */
@Composable
private fun BottomNavEntry(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) item.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "tint",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) item.accent.copy(alpha = PILL_ALPHA) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "pillColor",
    )

    Column(
        modifier =
            modifier
                .height(BAR_HEIGHT)
                .clip(RoundedCornerShape(TAB_RADIUS))
                .selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(PILL_RADIUS))
                    .background(pillColor)
                    .padding(horizontal = PILL_H_PADDING, vertical = PILL_V_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(modifier = Modifier.height(LABEL_TOP))
        Text(
            text = item.label,
            color = tint,
            fontSize = LABEL_SIZE,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

data class BottomNavItem(
    val label: String,
    /** The destination this tab selects; compared by identity with the stack's current route. */
    val route: NavKey,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    /** Per-tab accent: icon, label and the pill behind the icon all derive from it. */
    val accent: Color,
)
