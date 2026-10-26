package org.indra.android.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Icon tile + title + subtitle header shared by the update-ish cards below. */
@Composable
internal fun CardHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                contentDescription = null,
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
    }
}

/** Busy bar + status line shared by every action card in Settings. */
@Composable
internal fun CardStatus(
    busy: Boolean,
    message: String?,
) {
    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    message?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Check / Update button pair shared by the OTA and App Update cards. */
@Composable
private fun CheckUpdateButtons(
    checkEnabled: Boolean,
    updateEnabled: Boolean,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    updateLabel: String = "Update",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onCheck,
            enabled = checkEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("Check")
        }
        FilledTonalButton(
            onClick = onUpdate,
            enabled = updateEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(updateLabel)
        }
    }
}

@Composable
internal fun AlpineCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    // This card used to be a third verbatim copy of the OTA card: no heading, only
    // Check/Update — so SettingsViewModel.installAlpine() had no caller and the
    // Terminal's chroot switch ("install Alpine in Settings") was a dead end.
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
            ) {
                CardHeader(
                    title = "Alpine Linux chroot",
                    subtitle = "Real Linux userspace inside the Terminal",
                    icon = Icons.Default.Terminal,
                    accent = SettingsPalette.Core,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = if (uiState.alpineInstalled) "Installed" else "Not installed",
                    color = if (uiState.alpineInstalled) SettingsPalette.Running else SettingsPalette.Stopped,
                )
            }

            CardStatus(busy = uiState.alpineBusy, message = uiState.alpineMessage)

            if (!uiState.alpineInstalled) {
                Button(
                    onClick = viewModel::installAlpine,
                    enabled = !uiState.alpineBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uiState.alpineBusy) "Installing…" else "Install")
                }
            }
        }
    }
}

@Composable
internal fun OtaCard(
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
            CardHeader(
                title = "Native binaries (OTA)",
                subtitle = "Signed hot-swap of the core's tools and daemon",
                icon = Icons.Default.CloudDownload,
                accent = SettingsPalette.Ota,
            )
            CardStatus(
                busy = uiState.otaChecking || uiState.otaUpdating,
                message = uiState.otaMessage,
            )
            CheckUpdateButtons(
                checkEnabled = !uiState.otaChecking && !uiState.otaUpdating,
                updateEnabled = !uiState.otaChecking && !uiState.otaUpdating && uiState.otaUpdateAvailable,
                onCheck = viewModel::checkOtaUpdates,
                onUpdate = viewModel::installOtaUpdates,
            )
        }
    }
}

@Composable
internal fun AppUpdateCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    // This card used to repeat the binary-OTA buttons verbatim (the previous
    // copy-paste bug class that also produced the headerless Alpine card), so the
    // APK self-update plumbing in SettingsViewModel.checkAppUpdate()/installAppUpdate()
    // never had a caller. Now it checks and installs the app itself.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardHeader(
                    title = "App update",
                    subtitle = "Current APK version ${uiState.version}",
                    icon = Icons.Default.SystemUpdate,
                    accent = SettingsPalette.Ota,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text =
                        when {
                            uiState.appUpdateChecking -> "Checking"
                            uiState.appUpdateAvailable -> "Update available"
                            else -> "Up to date"
                        },
                    color =
                        when {
                            uiState.appUpdateChecking -> SettingsPalette.Ota
                            uiState.appUpdateAvailable -> SettingsPalette.WakeLock
                            else -> SettingsPalette.Running
                        },
                )
            }
            CardStatus(
                busy = uiState.appUpdateChecking,
                message = uiState.appUpdateMessage,
            )
            CheckUpdateButtons(
                checkEnabled = !uiState.appUpdateChecking,
                updateEnabled = uiState.appUpdateAvailable && !uiState.appUpdateChecking,
                onCheck = viewModel::checkAppUpdate,
                onUpdate = viewModel::installAppUpdate,
                updateLabel = "Install update",
            )
        }
    }
}
