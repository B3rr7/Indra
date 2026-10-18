package org.indra.android.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.indra.android.ui.components.SectionHeader
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Network Section
            item {
                SectionHeader(title = "Network")
            }
            item {
                SettingsItem(
                    title = "HTTP Proxy Port",
                    value = uiState.httpProxyPort.toString(),
                    icon = Icons.Default.Wifi,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = "HTTP Server Port",
                    value = uiState.httpServerPort.toString(),
                    icon = Icons.Default.Dns,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = "HTTPS Redirector Port",
                    value = uiState.httpsRedirectorPort.toString(),
                    icon = Icons.Default.Security,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = "MSF RPC Port",
                    value = uiState.msfRpcPort.toString(),
                    icon = Icons.Default.Terminal,
                    onClick = { }
                )
            }

            // General Section
            item {
                SectionHeader(title = "General")
            }
            item {
                SettingsSwitchItem(
                    title = "Keep Wake Lock",
                    checked = true,
                    onCheckedChange = { }
                )
            }
            item {
                SettingsSwitchItem(
                    title = "Debug Error Logging",
                    checked = false,
                    onCheckedChange = { }
                )
            }

            // About Section
            item {
                SectionHeader(title = "About")
            }
            item {
                SettingsItem(
                    title = "Version",
                    value = uiState.version,
                    icon = Icons.Default.Info,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = "Core Version",
                    value = uiState.coreVersion,
                    icon = Icons.Default.Storage,
                    onClick = { }
                )
            }
            item {
                SettingsItem(
                    title = "Root Manager",
                    value = "Magisk",
                    icon = Icons.Default.AdminPanelSettings,
                    onClick = { }
                )
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
