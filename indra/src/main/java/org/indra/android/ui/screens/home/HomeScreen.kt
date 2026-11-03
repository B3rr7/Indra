package org.indra.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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

/** How visible the section rule is: a separator, not a border. */
private const val RULE_ALPHA = 0.18f

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
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
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
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        QuickActionPill(
                            icon = Icons.Default.Radar,
                            label = "Network Scan",
                            onClick = { onNavigateToPlugin("port_scanner") },
                            enabled = uiState.plugins.find { it.id == "port_scanner" }?.openable ?: true,
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.DeviceHub,
                            label = "Inspect",
                            onClick = { onNavigateToPlugin("inspector") },
                            enabled = uiState.plugins.find { it.id == "inspector" }?.openable ?: true,
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.Timeline,
                            label = "Trace Route",
                            onClick = { onNavigateToPlugin("traceroute") },
                            enabled = uiState.plugins.find { it.id == "traceroute" }?.openable ?: true,
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.SyncAlt,
                            label = "MITM Attack",
                            onClick = { onNavigateToPlugin("mitm") },
                            accentColor = StatusRed,
                            enabled = uiState.plugins.find { it.id == "mitm" }?.openable ?: true,
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.BugReport,
                            label = "Vuln Scan",
                            onClick = { onNavigateToPlugin("vuln_scanner") },
                            accentColor = StatusOrange,
                            enabled = uiState.plugins.find { it.id == "vuln_scanner" }?.openable ?: true,
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
                    uiState.plugins.chunked(2).forEach { rowPlugins ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowPlugins.forEach { plugin ->
                                ModernToolCard(
                                    plugin = plugin,
                                    onClick = { onNavigateToPlugin(plugin.id) },
                                    modifier = Modifier.weight(1f),
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
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = Color.Transparent,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Flat 2.0: a tonal fill, not a gradient. A gradient is a depth cue from the
                    // same era as the shadow — it implies a light source the rest of the app does
                    // not have. The connected/disconnected state is carried by the accent colour
                    // on the icon and the headline, which is a stronger signal than a colour ramp.
                    .background(
                        if (isConnected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = Flat.SURFACE_ALPHA)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = Flat.SURFACE_ALPHA)
                        },
                    ).padding(24.dp),
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = StatusCyan,
        )
        Text(
            // null = the neighbour-table probe could not run; claiming "0 targets" there
            // would be a lie, so invite a scan instead.
            text =
                when (targetCount) {
                    null -> "Tap to scan the network"
                    1 -> "1 target discovered"
                    else -> "$targetCount targets discovered"
                },
            style = MaterialTheme.typography.bodySmall,
            color = StatusCyan,
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
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
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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
    val toolColor = getToolColor(plugin.id)

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
                .flatEdge(),
        shape = Flat.shape,
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
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(toolColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = getToolIcon(plugin.id),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (plugin.openable) toolColor else toolColor.copy(alpha = 0.4f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (onUpdate != null && plugin.available) {
                        Text(
                            text = if (updating) "Updating…" else "Update",
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (updating) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
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

@Suppress(
    "MagicNumber", // A palette is data, not opaque numbers: each literal is a commented hue.
    "CyclomaticComplexMethod", // One branch per tool id, by design.
)
@Composable
fun getToolColor(id: String): Color {
    // One distinct hue per tool — no repeats, no fallback to the theme primary.
    return when (id) {
        "port_scanner" -> Color(0xFF00E5FF) // cyan
        "inspector" -> Color(0xFF448AFF) // blue
        "traceroute" -> Color(0xFF7C4DFF) // violet
        "mitm" -> Color(0xFFFF5252) // red
        "exploit_finder" -> Color(0xFFFF9800) // orange
        "msf" -> Color(0xFFDD2C00) // deep red — Metasploit's own accent
        "login_cracker" -> Color(0xFFFFC107) // amber
        "packet_forger" -> Color(0xFFE040FB) // magenta
        "router_pwn" -> Color(0xFFFF4081) // pink
        "wifi_audit" -> Color(0xFF00E676) // green
        "vuln_scanner" -> Color(0xFFC6FF00) // lime
        "web_tester" -> Color(0xFF1DE9B6) // teal
        "gobuster" -> Color(0xFFFFAB00) // amber
        "credential_tester" -> Color(0xFFFFD600) // yellow
        "network_radar" -> Color(0xFF00BFA5) // sea green
        "packet_capture" -> Color(0xFF536DFE) // medium indigo
        "masscan" -> Color(0xFF00B0FF) // light blue
        "bettercap" -> Color(0xFF651FFF) // deep purple
        "responder" -> Color(0xFF00C853) // emerald
        "wifite" -> Color(0xFF64DD17) // light green
        "reaver" -> Color(0xFFFF6D00) // deep orange
        "geomac" -> Color(0xFF2979FF) // strong blue
        "whisperpair" -> Color(0xFFD500F9) // purple
        "searchsploit" -> Color(0xFF3D5AFE) // indigo
        "arsenal" -> Color(0xFF8D6E63) // brown
        "hid" -> Color(0xFF78909C) // blue grey
        "duckyscript" -> Color(0xFF546E7A) // dark blue grey
        "usb_arsenal" -> Color(0xFF607D8B) // grey blue
        "session_hijack" -> Color(0xFFD32F2F) // dark red
        "password_auditor" -> Color(0xFFFFA000) // amber dark
        "dns_spoofer" -> Color(0xFF00897B) // teal dark
        "one_tap_mitm" -> Color(0xFFE53935) // red
        "topology" -> Color(0xFF1976D2) // blue
        "mac_changer" -> Color(0xFF5E35B1) // deep purple
        "wifi_scanner" -> Color(0xFF43A047) // green
        "handshakes" -> Color(0xFF8E24AA) // purple
        "iptables" -> Color(0xFF5D4037) // dark brown
        "terminal" -> Color(0xFF212121) // near black
        else -> MaterialTheme.colorScheme.primary
    }
}
