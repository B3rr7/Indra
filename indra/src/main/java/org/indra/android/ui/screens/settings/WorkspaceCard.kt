package org.indra.android.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.indra.android.core.system.IndraWorkspace

/**
 * The user-visible workspace on shared storage (`/sdcard/Indra`).
 *
 * Everything Indra produced used to be written into the app's private directory, which no
 * file manager on the phone can open — so a capture could not be pulled off the device
 * without adb, and there was nowhere to drop your own wordlist or helper binary. The card
 * shows the real tree (each row probed from disk, not assumed) and creates it through the
 * root daemon, because the app process itself cannot write to `/sdcard` without all-files
 * access.
 */
@Composable
internal fun WorkspaceCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
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
            WorkspaceHeader(uiState)
            Text(
                text = uiState.indraDirPath.ifBlank { "/sdcard/Indra" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            IndraWorkspace.SUBDIRECTORIES.forEach { name ->
                WorkspaceDirRow(name = name, present = name in uiState.workspacePresent)
            }
            uiState.storageMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WorkspaceActions(uiState = uiState, viewModel = viewModel, onAllowAccess = { openAllFilesAccessSettings(context) })
            Text(
                text =
                    "Drop your own files in from any file manager. Captures written by " +
                        "Packet Capture and Wifite land in captures/.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkspaceHeader(uiState: SettingsUiState) {
    val ready = uiState.workspaceMissing.isEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Indra folder",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        StatusPill(
            text =
                when {
                    ready -> "Ready"
                    uiState.workspacePresent.isEmpty() -> "Missing"
                    else -> "${uiState.workspaceMissing.size} missing"
                },
            color = if (ready) SettingsPalette.Running else SettingsPalette.Stopped,
        )
    }
}

@Composable
private fun WorkspaceActions(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onAllowAccess: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = viewModel::createWorkspaceDirs,
            enabled = !uiState.workspaceCreating && uiState.workspaceMissing.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (uiState.workspaceCreating) "Creating…" else "Create folders")
        }
        if (!uiState.allFilesAccess) {
            FilledTonalButton(
                onClick = onAllowAccess,
                modifier = Modifier.weight(1f),
            ) {
                Text("Allow access")
            }
        }
    }
}

@Composable
private fun WorkspaceDirRow(
    name: String,
    present: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (present) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (present) "$name folder exists" else "$name folder missing",
            modifier = Modifier.size(14.dp),
            tint = if (present) SettingsPalette.Running else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = IndraWorkspace.purposes[name].orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens the system "All files access" page for this app (Android 11+; no-op below). */
private fun openAllFilesAccessSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}
