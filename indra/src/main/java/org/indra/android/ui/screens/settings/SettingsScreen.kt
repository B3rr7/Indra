package org.indra.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.indra.android.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingPort by remember { mutableStateOf<PortField?>(null) }

    editingPort?.let { field ->
        PortEditDialog(
            field = field,
            currentValue =
                when (field) {
                    PortField.HTTP_PROXY -> uiState.httpProxyPort
                    PortField.HTTP_SERVER -> uiState.httpServerPort
                    PortField.HTTPS_REDIRECT -> uiState.httpsRedirectorPort
                    PortField.MSF_RPC -> uiState.msfRpcPort
                },
            onDismiss = { editingPort = null },
            onSave = { port ->
                viewModel.setPort(field, port)
                editingPort = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── System status hero ───────────────────────────────────────
            item { StatusHeroCard(uiState) }

            // ── Network Configuration ────────────────────────────────────
            item {
                SettingsSection(
                    title = "Network Configuration",
                    icon = Icons.Default.Wifi,
                    accent = SettingsPalette.Network,
                )
            }
            item {
                SettingsCard {
                    SettingsItem(
                        title = "HTTP Proxy Port",
                        subtitle = "Local proxy server",
                        value = uiState.httpProxyPort.toString(),
                        icon = Icons.Default.WifiTethering,
                        accent = SettingsPalette.Ports,
                        onClick = { editingPort = PortField.HTTP_PROXY },
                    )
                    SettingsItem(
                        title = "HTTP Server Port",
                        subtitle = "HTTP server for MITM",
                        value = uiState.httpServerPort.toString(),
                        icon = Icons.Default.Dns,
                        accent = SettingsPalette.HttpServer,
                        onClick = { editingPort = PortField.HTTP_SERVER },
                    )
                    SettingsItem(
                        title = "HTTPS Redirector Port",
                        subtitle = "HTTPS redirection",
                        value = uiState.httpsRedirectorPort.toString(),
                        icon = Icons.Default.Https,
                        accent = SettingsPalette.Https,
                        onClick = { editingPort = PortField.HTTPS_REDIRECT },
                    )
                    SettingsItem(
                        title = "MSF RPC Port",
                        subtitle = "Metasploit RPC connection",
                        value = uiState.msfRpcPort.toString(),
                        icon = Icons.Default.Terminal,
                        accent = SettingsPalette.MsfRpc,
                        onClick = { editingPort = PortField.MSF_RPC },
                        last = true,
                    )
                }
            }

            // ── General ──────────────────────────────────────────────────
            item {
                SettingsSection(
                    title = "General",
                    icon = Icons.Default.Tune,
                    accent = SettingsPalette.General,
                )
            }
            item {
                SettingsCard {
                    SettingsSwitchItem(
                        title = "Keep Wake Lock",
                        subtitle = "Prevent device from sleeping",
                        checked = true,
                        onCheckedChange = { },
                        icon = Icons.Default.BrightnessHigh,
                        accent = SettingsPalette.WakeLock,
                    )
                    SettingsSwitchItem(
                        title = "Debug Error Logging",
                        subtitle = "Enable detailed logging",
                        checked = false,
                        onCheckedChange = { },
                        icon = Icons.Default.BugReport,
                        accent = SettingsPalette.DebugLog,
                        last = true,
                    )
                }
            }

            // ── Native Core ──────────────────────────────────────────────
            item {
                SettingsSection(
                    title = "Native Core",
                    icon = Icons.Default.Storage,
                    accent = SettingsPalette.Core,
                )
            }
            item { NativeCoreCard(uiState, viewModel) }

            item {
                SettingsCard {
                    SettingsSwitchItem(
                        title = "Rootless mode",
                        subtitle = "Run daemon as app user (no root). Disables raw-socket features.",
                        checked = uiState.rootlessMode,
                        onCheckedChange = { viewModel.setRootlessMode(it) },
                        icon = Icons.Default.Security,
                        accent = SettingsPalette.Core
                    )
                }
            }

            // ── Bundled Tools ────────────────────────────────────────────
            item {
                SettingsSection(
                    title = "Bundled Tools",
                    icon = Icons.Default.Build,
                    accent = SettingsPalette.Core,
                )
            }
            item { ToolsStatusCard(uiState) }

            // ── Binary Updates (OTA) ─────────────────────────────────────
            item {
                SettingsSection(
                    title = "Binary Updates (OTA)",
                    icon = Icons.Default.CloudDownload,
                    accent = SettingsPalette.Ota,
                )
            }
            item { OtaCard(uiState, viewModel) }

            if (BuildConfig.DEBUG) {
                item {
                    SettingsSection(
                        title = "OTA Test Host (dev only)",
                        icon = Icons.Default.BugReport,
                        accent = SettingsPalette.DebugLog,
                    )
                }
                item { OtaTestHostCard(uiState, viewModel) }
            }

            // ── About ────────────────────────────────────────────────────
            item {
                SettingsSection(
                    title = "About",
                    icon = Icons.Default.Info,
                    accent = SettingsPalette.About,
                )
            }
            item {
                SettingsCard {
                    SettingsItem(
                        title = "License",
                        subtitle = "GNU General Public License v3",
                        value = "GPL v3",
                        icon = Icons.Default.Gavel,
                        accent = SettingsPalette.License,
                        last = true,
                    )
                }
            }

            // App Info Footer
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    )
                    Text(
                        text = "Indra",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Network Penetration Testing Suite",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusHeroCard(uiState: SettingsUiState) {
    // Graceful fallback when the settings UiState lacks fancy fields — values are read directly.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                ),
                            ),
                    ).padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                (if (uiState.daemonRunning) SettingsPalette.Running else SettingsPalette.Stopped)
                                    .copy(alpha = 0.2f),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (uiState.daemonRunning) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (uiState.daemonRunning) SettingsPalette.Running else SettingsPalette.Stopped,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = if (uiState.daemonRunning) "Daemon running" else "Daemon stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Core ${uiState.coreVersion} · App ${uiState.version} · Magisk root",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = MaterialTheme.typography.titleSmall.letterSpacing,
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit = {},
    last: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(21.dp),
                    tint = accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!last) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    accent: Color,
    last: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(21.dp),
                tint = accent,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.35f),
                ),
        )
    }
    if (!last) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}





@Composable
private fun PortEditDialog(
    field: PortField,
    currentValue: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var text by remember(field) { mutableStateOf(currentValue.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (!valid) {
                    Text(
                        text = "Enter a port between 1 and 65535",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

