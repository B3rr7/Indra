package org.indra.android.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import org.indra.android.ui.components.entryStagger
import org.indra.android.ui.components.GlowBox
import org.indra.android.ui.components.GlowSpill
import org.indra.android.ui.components.pressScale
import org.indra.android.ui.theme.LocalPalette
import org.indra.android.ui.theme.accentFor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.theme.StatusCyan
import org.indra.android.ui.theme.StatusGreen
import org.indra.android.ui.theme.StatusOrange
import org.indra.android.ui.theme.Flat
import org.indra.android.ui.theme.StatusRed
import org.indra.android.ui.theme.flatEdge
import org.indra.android.ui.theme.flatSurfaceColor
import org.indra.android.ui.theme.pageFill

/** How visible the section rule is: a separator, not a border. */
private const val CARD_EDGE_ALPHA = 0.16f
private const val TILE_FILL_ALPHA = 0.12f
private val TILE_SIZE = 42.dp
private const val GLOW_INTENSITY = 0.55f
private const val GLOW_INTENSITY_DISABLED = 0.12f
private const val RULE_ALPHA = 0.18f
private const val HERO_FILL_ALPHA = 0.55f
private const val PAGE_GLOW_ALPHA = 0.45f
private const val PILL_CONTAINER_ALPHA = 0.55f

private const val COUNT_ANIMATION_MS = 600
private const val PULSE_MIN_ALPHA = 0.35f
private const val PULSE_DURATION_MS = 1400

/** One pill in the Quick Actions row. */
private data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
)

/**
 * The row's content, with each pill's accent derived from the tool it opens — a quick action is a
 * shortcut to that tool's screen, so it should already look like it.
 */
private fun quickActions(plugins: List<PluginInfo>): List<QuickAction> =
    listOf(
        Triple("port_scanner", "Network Scan", Icons.Default.Radar),
        Triple("inspector", "Inspect", Icons.Default.DeviceHub),
        Triple("traceroute", "Trace Route", Icons.Default.Timeline),
        Triple("mitm", "MITM Attack", Icons.Default.SyncAlt),
        Triple("vuln_scanner", "Vuln Scan", Icons.Default.BugReport),
    ).map { (id, label, icon) ->
        QuickAction(id, label, icon, plugins.find { it.id == id }?.openable ?: true)
    }

/** Entrance order on Home: quick actions, then the tool grid, in one cascade. */
private const val QUICK_ACTION_START = 0
private const val TOOL_GRID_START = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlugin: (String) -> Unit,
    onNavigateToNetworkRadar: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Re-read on every entry to Home (tab switch, back from a plugin screen) rather than
    // only once per ViewModel lifetime.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = pageFill(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "INDRA",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = MaterialTheme.typography.titleLarge.letterSpacing,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
            )
        },
    ) { paddingValues ->
        val palette = LocalPalette.current
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = PAGE_GLOW_ALPHA),
                                pageFill(),
                            ),
                        ),
                    )
                    .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Hero Network Status Card — rendered directly; an always-visible AnimatedVisibility
            // allocates an animation scope every composition for an animation that never runs.
            item {
                NetworkStatusHeroCard(
                    networkName = uiState.networkName,
                    networkDetail = uiState.networkDetail,
                    isConnected = uiState.isConnected,
                    targetCount = uiState.targetCount,
                    onClick = onNavigateToNetworkRadar,
                )
            }

            // Quick Actions
            item {
                SectionTitle(title = "Quick Actions")
            }

            item {
                // Data-driven rather than five copied blocks: the row's order is also the stagger
                // order, and a literal index per block is exactly the kind of number that drifts.
                val actions = remember(uiState.plugins) { quickActions(uiState.plugins) }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                        QuickActionPill(
                            icon = action.icon,
                            label = action.label,
                            onClick = { onNavigateToPlugin(action.id) },
                            accentColor = accentFor(action.id).base,
                            enabled = action.enabled,
                            modifier = Modifier.entryStagger(QUICK_ACTION_START + index),
                        )
                    }
                }
            }

            // Tools Section
            item {
                SectionTitle(
                    title = "Security Tools",
                    // Not "N modules available": with the daemon stopped that count is false.
                    // Cards carry their own status label, so this only counts modules.
                    subtitle = "${uiState.plugins.size} modules",
                )
            }

            // Tools Grid — chunked rows keep cards at natural height inside the LazyColumn
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val toolRows = remember(uiState.plugins) { uiState.plugins.chunked(2) }
                    toolRows.forEachIndexed { rowIndex, rowPlugins ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowPlugins.forEachIndexed { columnIndex, plugin ->
                                ModernToolCard(
                                    plugin = plugin,
                                    onClick = { onNavigateToPlugin(plugin.id) },
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .entryStagger(TOOL_GRID_START + rowIndex * 2 + columnIndex),
                                )
                            }
                            if (rowPlugins.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkStatusHeroCard(
    networkName: String,
    networkDetail: String,
    isConnected: Boolean,
    targetCount: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val heroEdge = if (isConnected) palette.primary else MaterialTheme.colorScheme.error
    // The palette's own two accents, not two theme containers: the hero is the one surface that has
    // to change *visibly* when the palette changes, because it is what the picker previews.
    val heroFill =
        if (isConnected) {
            Brush.linearGradient(palette.heroGradient.map { it.copy(alpha = HERO_FILL_ALPHA) })
        } else {
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                ),
            )
        }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, heroEdge.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(heroFill)
                    .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroNetworkIcon(isConnected)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (isConnected) "Network Connected" else "Disconnected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isConnected) networkName else "No active network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isConnected && networkDetail.isNotBlank()) {
                        Text(
                            text = networkDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isConnected) {
                        TargetCountRow(targetCount)
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeroNetworkIcon(isConnected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (isConnected) {
                        StatusGreen.copy(alpha = 0.2f)
                    } else {
                        StatusRed.copy(alpha = 0.2f)
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (isConnected) StatusGreen else StatusRed,
        )
    }
}

@Composable
private fun TargetCountRow(targetCount: Int?) {
    val palette = LocalPalette.current
    // Counting up is the one piece of motion on this screen that carries information: the number
    // arrives after a probe, and the count is what the user came to see.
    val animatedCount by animateIntAsState(
        targetValue = targetCount ?: 0,
        animationSpec = tween(durationMillis = COUNT_ANIMATION_MS),
        label = "targetCount",
    )
    // A slow breath on the dot says "this is live", which a static dot cannot.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(palette.primary.copy(alpha = if (targetCount == null) PULSE_MIN_ALPHA else pulse)),
        )
        Text(
            // null = the neighbour-table probe could not run; claiming "0 targets" there
            // would be a lie, so invite a scan instead.
            text =
                when (targetCount) {
                    null -> "Tap to scan the network"
                    1 -> "1 target discovered"
                    else -> "$animatedCount targets discovered"
                },
            style = MaterialTheme.typography.bodySmall,
            color = palette.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A section subheading: the label, an optional count on the right, and a hairline rule.
 *
 * This used to take an `icon: ImageVector` and draw it in the primary colour, which made the two
 * headings on this screen ("Quick Actions", "Security Tools") the loudest things on it — a 24dp
 * accent-coloured glyph next to every title, competing with the hero card and the tool cards that
 * are the actual content. A subheading's job is to separate sections, not to attract attention, so
 * the icon is gone and the rule does that work instead.
 *
 * The count moved to the right end of the rule: it is metadata about the section, and left-aligned
 * under the title it read as a second line of the heading.
 */
@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                // Uppercase + tracking is what makes a heading read as structure at 12sp: it
                // stops competing with the 14sp card titles right below it.
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The rule fills whatever space the title leaves, so short and long titles both end
            // at the same right edge.
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = RULE_ALPHA),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun QuickActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        interactionSource = interaction,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                // A tonal pill needs to be a step *above* the page; with the palette's surface and
                // the page only a few levels apart, 0.7 of the surface disappeared. The palette's
                // primary container gives it a tint and a boundary at once.
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = PILL_CONTAINER_ALPHA),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = accentColor,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ModernToolCard(
    plugin: PluginInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdate: (() -> Unit)? = null,
    updating: Boolean = false,
    updateEnabled: Boolean = true,
) {
    val accent = accentFor(plugin.id)
    val interaction = remember { MutableInteractionSource() }

    Card(
        onClick = onClick,
        // A stopped daemon is still worth opening: the screen says how to start it.
        enabled = plugin.openable,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                // Flat 2.0: the hairline is what gives the card a boundary now that the shadow is
                // gone. It is drawn inside the same shape the card clips to, so the edge and the
                // fill agree at the corners.
                .pressScale(interaction)
                .border(1.dp, accent.edge(CARD_EDGE_ALPHA), Flat.shape),
        shape = Flat.shape,
        interactionSource = interaction,
        colors =
            CardDefaults.cardColors(
                containerColor = flatSurfaceColor(),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = Flat.elevation),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // The glow is the tile's own colour: the bloom sits *behind* the tile, so the
                // icon looks lit rather than outlined.
                GlowBox(
                    color = accent.base,
                    tile = TILE_SIZE,
                    spill = GlowSpill,
                    intensity = if (plugin.openable) GLOW_INTENSITY else GLOW_INTENSITY_DISABLED,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(TILE_SIZE)
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (plugin.openable) accent.tile else SolidColor(accent.fill(TILE_FILL_ALPHA))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = getToolIcon(plugin.id),
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                            // White on the gradient: every palette hue is dark enough at the tile's
                            // bottom for a white glyph, and `onAccent` picks black for the pale ones.
                            tint = if (plugin.openable) accent.onAccent else accent.soft,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (onUpdate != null && plugin.available) {
                        Text(
                            text = if (updating) "Updating…" else "Update",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (updating) MaterialTheme.colorScheme.onSurfaceVariant else accent.base,
                            modifier =
                                Modifier.clickable(
                                    enabled = updateEnabled && !updating,
                                    onClick = onUpdate,
                                ),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = accent.soft,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        plugin.statusLabel?.let { "${plugin.description} · $it" }
                            ?: plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                )
                plugin.version?.let { version ->
                    Text(
                        // The tool's own tint, not the brand's: with 37 cards on screen, a brand
                        // colour on this line made the whole list read as one cyan surface.
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun getToolIcon(id: String): ImageVector =
    when (id) {
        "port_scanner" -> Icons.Default.Radar
        "inspector" -> Icons.Default.Search
        "traceroute" -> Icons.Default.Timeline
        "mitm" -> Icons.Default.SyncAlt
        "exploit_finder" -> Icons.Default.BugReport
        "msf" -> Icons.Default.Terminal
        "login_cracker" -> Icons.Default.Lock
        "packet_forger" -> Icons.AutoMirrored.Filled.Send
        "router_pwn" -> Icons.Default.Router
        "wifi_audit" -> Icons.Default.Wifi
        "vuln_scanner" -> Icons.Default.Security
        "web_tester" -> Icons.Default.Language
        "gobuster" -> Icons.Default.FolderOpen
        "credential_tester" -> Icons.Default.Key
        "network_radar" -> Icons.Default.WifiTethering
        "packet_capture" -> Icons.Default.Cable
        "bettercap" -> Icons.Default.Terminal
        "responder" -> Icons.Default.NetworkCheck
        "wifite" -> Icons.Default.WifiLock
        "reaver" -> Icons.Default.LockOpen
        "geomac" -> Icons.Default.Map
        "whisperpair" -> Icons.Default.Bluetooth
        "searchsploit" -> Icons.AutoMirrored.Filled.ManageSearch
        "arsenal" -> Icons.Default.Inventory2
        "hid" -> Icons.Default.Usb
        "duckyscript" -> Icons.Default.Keyboard
        "usb_arsenal" -> Icons.Default.Usb
        "session_hijack" -> Icons.Default.PersonOff
        "password_auditor" -> Icons.Default.Password
        "dns_spoofer" -> Icons.Default.Dns
        "one_tap_mitm" -> Icons.Default.FlashOn
        "topology" -> Icons.Default.AccountTree
        "mac_changer" -> Icons.Default.Edit
        "wifi_scanner" -> Icons.Default.WifiFind
        "terminal" -> Icons.Default.Terminal
        "handshakes" -> Icons.Default.FolderOpen
        "iptables" -> Icons.Default.Security
        "masscan" -> Icons.Default.Speed
        else -> Icons.Default.Extension
    }

@Composable
fun getToolColor(id: String): Color {
    // The palette lives in `:ui` (`Accent.kt`) because a plugin screen's header and its card have
    // to agree; this accessor is kept for call sites that only need the base hue.
    return accentFor(id).base
}
