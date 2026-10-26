// Extracted from SettingsScreen.kt (2026-10-24 code-health sprint): the palette
// and the self-contained cards (native core, tools status, OTA, OTA test host)
// so the main file stays about screen structure, not card internals.
package org.indra.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal object SettingsPalette {
    val Network = Color(0xFF448AFF) // blue
    val Ports = Color(0xFF00E5FF) // cyan
    val HttpServer = Color(0xFF536DFE) // indigo
    val Https = Color(0xFF7C4DFF) // violet
    val MsfRpc = Color(0xFFE040FB) // magenta
    val General = Color(0xFFFFC107) // amber
    val WakeLock = Color(0xFFFF9800) // orange
    val DebugLog = Color(0xFFC6FF00) // lime
    val Core = Color(0xFF1DE9B6) // teal
    val Ota = Color(0xFF00B0FF) // sky
    val About = Color(0xFFFF4081) // pink
    val License = Color(0xFFFFD600) // yellow
    val Running = Color(0xFF00E676) // green
    val Stopped = Color(0xFFFF5252) // red
}

@Composable
internal fun RootAccessCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SettingsPalette.Core.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = SettingsPalette.Core,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Root access",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = uiState.rootType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (uiState.isRooted) "Granted" else "Not granted",
                    color = if (uiState.isRooted) SettingsPalette.Running else SettingsPalette.Stopped,
                )
            }

            if (!uiState.isRooted) {
                Button(
                    onClick = viewModel::requestRootAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Request root access")
                }
            }
        }
    }
}

@Composable
internal fun ToolsStatusCard(uiState: SettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.toolStatus.sortedBy { it.name }.forEach { tool ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (tool.available) SettingsPalette.Running else SettingsPalette.Stopped),
                    )
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (tool.available) "available" else "not bundled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.toolStatus.isEmpty()) {
                Text(
                    text = "No tool status available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
internal fun StatusPill(
    text: String,
    color: Color,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
internal fun OtaTestHostCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    var url by remember(uiState.otaManifestUrl) { mutableStateOf(uiState.otaManifestUrl) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Manifest URL",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { viewModel.setOtaManifestUrl(url) },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply URL") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Allow plain-HTTP / non-pinned test hosts",
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = uiState.otaAllowTestHosts,
                    onCheckedChange = viewModel::setOtaAllowTestHosts,
                )
            }
            Text(
                text = "Dev only. Use with `adb reverse tcp:8443 tcp:8443` and a local `python3 -m http.server`.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LogsCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onViewLog: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // This card used to be a second copy of the OTA check/update buttons under a
            // "Logs & Reports" heading: nothing on it had anything to do with logs, and the
            // real (already implemented) log/crash plumbing in the ViewModel had no caller.
            LogsSummary(uiState)
            LogsActions(uiState = uiState, viewModel = viewModel, onViewLog = onViewLog)
            Text(
                text = "Reports stay on this device until you share them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogsActions(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onViewLog: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onViewLog,
            modifier = Modifier.weight(1f),
        ) {
            Text("View log")
        }
        FilledTonalButton(
            onClick = {
                shareFiles(
                    context = context,
                    files = listOfNotNull(viewModel.getDebugLogFile()),
                    chooserTitle = "Share Indra log",
                )
            },
            enabled = uiState.debugLogSizeBytes > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("Share log")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = {
                shareFiles(
                    context = context,
                    files = viewModel.getCrashLogFiles(),
                    chooserTitle = "Share Indra crash reports",
                )
            },
            enabled = uiState.crashLogCount > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("Share crashes")
        }
        TextButton(
            onClick = viewModel::clearDebugLog,
            enabled = uiState.debugLogSizeBytes > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("Clear log")
        }
        TextButton(
            onClick = viewModel::clearCrashLogs,
            enabled = uiState.crashLogCount > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("Clear crashes")
        }
    }
}

@Composable
private fun LogsSummary(uiState: SettingsUiState) {
    Text(
        text = "Debug log: ${formatBytes(uiState.debugLogSizeBytes)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text =
            when (uiState.crashLogCount) {
                0 -> "No crash reports"
                1 -> "1 crash report"
                else -> "${uiState.crashLogCount} crash reports"
            },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!uiState.debugErrorLogging) {
        Text(
            text = "File logging is off — enable \"Debug error logging\" above to record output.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB

internal fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0 -> "empty"
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> String.format(java.util.Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
    }

/**
 * Hands [files] to another app through the `${applicationId}.fileprovider` provider
 * (`res/xml/file_paths.xml` exposes `logs/` and `crashes/`). Failures are swallowed on
 * purpose: a device with no app able to receive the intent should not crash Settings.
 */
internal fun shareFiles(
    context: android.content.Context,
    files: List<java.io.File>,
    chooserTitle: String,
) {
    val existing = files.filter { it.isFile && it.length() > 0 }
    if (existing.isEmpty()) return
    val uris =
        ArrayList(
            existing.mapNotNull { file ->
                runCatching {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
            },
        )
    if (uris.isEmpty()) return
    val intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
}

@Composable
internal fun NativeCoreCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SettingsPalette.Core.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = SettingsPalette.Core,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "indra daemon",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Installed version ${uiState.coreVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (uiState.daemonRunning) "Running" else "Stopped",
                    color = if (uiState.daemonRunning) SettingsPalette.Running else SettingsPalette.Stopped,
                )
            }

            if (uiState.coreBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            uiState.coreMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = viewModel::installCore,
                    enabled = !uiState.coreBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Install")
                }
                if (uiState.daemonRunning) {
                    OutlinedButton(
                        onClick = viewModel::stopDaemon,
                        enabled = !uiState.coreBusy,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = SettingsPalette.Stopped,
                            ),
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = viewModel::startDaemon,
                        enabled = !uiState.coreBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Start")
                    }
                }
            }
        }
    }
}
