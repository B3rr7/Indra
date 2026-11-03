package org.indra.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/**
 * Status line shared by every action card in Settings.
 *
 * The busy state lives on [UpdateCardButton] itself (spinner + "Installing…"), so this only
 * carries the outcome copy — one place to look while an action runs, exactly like the Native
 * Core card.
 */
@Composable
internal fun CardStatus(message: String?) {
    message?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The single adaptive action button used by the OTA and App Update cards.
 *
 * Mirrors [NativeCoreCard.CoreActionButton]: one button whose label, icon and colour derive from
 * the card's state. While an action is in flight the button is disabled and shows a spinner plus
 * the busy copy — there is never a second "Check" / "Update" button competing for attention.
 */
@Composable
internal fun UpdateCardButton(
    busy: Boolean,
    label: String,
    busyLabel: String,
    onClick: () -> Unit,
    accent: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = contentColorOn(accent),
                disabledContainerColor = accent.copy(alpha = 0.20f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        if (!busy) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(text = label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(text = busyLabel, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Above this luminance the accent is bright enough to need black content (green, red, teal). */
private const val CONTENT_LUMINANCE_THRESHOLD = 0.45f

/** `onPrimary` can't be reused across fixed accent colours; pick black or white from luminance. */
private fun contentColorOn(container: Color): Color =
    if (container.luminance() > CONTENT_LUMINANCE_THRESHOLD) Color.Black else Color.White

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

            CardStatus(message = uiState.alpineMessage)

            if (!uiState.alpineInstalled) {
                UpdateCardButton(
                    busy = uiState.alpineBusy,
                    label = "Install",
                    busyLabel = "Installing…",
                    onClick = viewModel::installAlpine,
                    accent = SettingsPalette.Core,
                    icon = Icons.Default.Download,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            CardStatus(message = uiState.otaMessage)
            val busy = uiState.otaChecking || uiState.otaUpdating
            UpdateCardButton(
                busy = busy,
                label = if (uiState.otaUpdateAvailable) "Install update" else "Check for updates",
                busyLabel = if (uiState.otaUpdating) "Installing updates…" else "Checking…",
                onClick = {
                    if (uiState.otaUpdateAvailable) {
                        viewModel.installOtaUpdates()
                    } else {
                        viewModel.checkOtaUpdates()
                    }
                },
                accent = SettingsPalette.Ota,
                icon = Icons.Default.CloudDownload,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun AppUpdateCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    // Single adaptive button: "Check for updates" when nothing is known / up to date,
    // "Install update" when an update is available. The busy state (checking or downloading)
    // disables the button and shows a spinner, so there is never a Check / Update pair.
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
                title = "App update",
                subtitle = "Current APK version ${uiState.version}",
                icon = Icons.Default.SystemUpdate,
                accent = SettingsPalette.AppUpdate,
            )
            CardStatus(message = uiState.appUpdateMessage)
            val busy = uiState.appUpdateChecking
            UpdateCardButton(
                busy = busy,
                label = if (uiState.appUpdateAvailable) "Install update" else "Check for updates",
                busyLabel = if (uiState.appUpdateAvailable) "Downloading update…" else "Checking…",
                onClick = {
                    if (uiState.appUpdateAvailable) {
                        viewModel.installAppUpdate()
                    } else {
                        viewModel.checkAppUpdate()
                    }
                },
                accent = SettingsPalette.AppUpdate,
                icon = Icons.Default.SystemUpdate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
