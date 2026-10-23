package org.indra.android.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.indra.android.ui.theme.StatusCyan
import org.indra.android.ui.theme.StatusGreen
import org.indra.android.ui.theme.StatusOrange
import org.indra.android.ui.theme.StatusRed
import org.indra.android.ui.theme.StatusBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlugin: (String) -> Unit,
    onNavigateToNetworkRadar: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "INDRA",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = MaterialTheme.typography.titleLarge.letterSpacing
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Hero Network Status Card
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(600)) + slideInVertically(
                        animationSpec = tween(600),
                        initialOffsetY = { -40 }
                    )
                ) {
                    NetworkStatusHeroCard(
                        networkName = uiState.networkName,
                        networkDetail = uiState.networkDetail,
                        isConnected = uiState.isConnected,
                        targetCount = uiState.targets.size,
                        onClick = onNavigateToNetworkRadar
                    )
                }
            }

            // Quick Actions
            item {
                SectionTitle(
                    title = "Quick Actions",
                    icon = Icons.Default.Bolt
                )
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        QuickActionPill(
                            icon = Icons.Default.Radar,
                            label = "Network Scan",
                            onClick = { onNavigateToPlugin("port_scanner") }
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.DeviceHub,
                            label = "Inspect",
                            onClick = { onNavigateToPlugin("inspector") }
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.Timeline,
                            label = "Trace Route",
                            onClick = { onNavigateToPlugin("traceroute") }
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.SyncAlt,
                            label = "MITM Attack",
                            onClick = { onNavigateToPlugin("mitm") },
                            accentColor = StatusRed
                        )
                    }
                    item {
                        QuickActionPill(
                            icon = Icons.Default.BugReport,
                            label = "Vuln Scan",
                            onClick = { onNavigateToPlugin("vuln_scanner") },
                            accentColor = StatusOrange
                        )
                    }
                }
            }

            // Tools Section
            item {
                SectionTitle(
                    title = "Security Tools",
                    icon = Icons.Default.Build,
                    subtitle = "${uiState.plugins.size} modules available"
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
                                    modifier = Modifier.weight(1f)
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
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
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
    targetCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = if (isConnected) {
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            )
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    )
                )
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) StatusGreen.copy(alpha = 0.2f)
                            else StatusRed.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (isConnected) StatusGreen else StatusRed
                    )
                }
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isConnected) "Network Connected" else "Disconnected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isConnected) networkName else "No active network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isConnected && networkDetail.isNotBlank()) {
                        Text(
                            text = networkDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = StatusCyan
                            )
                            Text(
                                text = "$targetCount targets discovered",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusCyan,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = accentColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
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
    updateEnabled: Boolean = true
) {
    val toolColor = getToolColor(plugin.id)
    
    Card(
        onClick = onClick,
        enabled = plugin.available,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(toolColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getToolIcon(plugin.id),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (plugin.available) toolColor else toolColor.copy(alpha = 0.4f)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onUpdate != null && plugin.available) {
                        Text(
                            text = if (updating) "Updating…" else "Update",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (updating) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.clickable(
                                enabled = updateEnabled && !updating,
                                onClick = onUpdate
                            )
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (plugin.available) {
                        plugin.description
                    } else {
                        "${plugin.description} · not in native core"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
            }
        }
    }
}

@Composable
fun getToolIcon(id: String): ImageVector {
    return when (id) {
        "port_scanner" -> Icons.Default.Radar
        "inspector" -> Icons.Default.Search
        "traceroute" -> Icons.Default.Timeline
        "mitm" -> Icons.Default.SyncAlt
        "exploit_finder" -> Icons.Default.BugReport
        "login_cracker" -> Icons.Default.Lock
        "packet_forger" -> Icons.AutoMirrored.Filled.Send
        "router_pwn" -> Icons.Default.Router
        "wifi_audit" -> Icons.Default.Wifi
        "vuln_scanner" -> Icons.Default.Security
        "web_tester" -> Icons.Default.Language
        "credential_tester" -> Icons.Default.Key
        "network_radar" -> Icons.Default.WifiTethering
        "packet_capture" -> Icons.Default.Cable
        else -> Icons.Default.Extension
    }
}

@Composable
fun getToolColor(id: String): Color {
    // One distinct hue per tool — no repeats
    return when (id) {
        "port_scanner" -> Color(0xFF00E5FF)  // cyan
        "inspector" -> Color(0xFF448AFF)     // blue
        "traceroute" -> Color(0xFF7C4DFF)    // violet
        "mitm" -> Color(0xFFFF5252)          // red
        "exploit_finder" -> Color(0xFFFF9800) // orange
        "login_cracker" -> Color(0xFFFFC107) // amber
        "packet_forger" -> Color(0xFFE040FB) // magenta
        "router_pwn" -> Color(0xFFFF4081)    // pink
        "wifi_audit" -> Color(0xFF00E676)    // green
        "vuln_scanner" -> Color(0xFFC6FF00)  // lime
        "web_tester" -> Color(0xFF1DE9B6)    // teal
        "credential_tester" -> Color(0xFFFFD600) // yellow
        "network_radar" -> Color(0xFF00BFA5) // sea green
        "packet_capture" -> Color(0xFF536DFE) // medium indigo blue
        else -> MaterialTheme.colorScheme.primary
    }
}
