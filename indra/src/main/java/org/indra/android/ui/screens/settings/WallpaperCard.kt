package org.indra.android.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.indra.android.core.system.wallpaper.WallpaperDim
import org.indra.android.ui.components.FormError
import org.indra.android.ui.theme.Flat
import org.indra.android.ui.theme.flatEdge
import org.indra.android.ui.theme.flatSurfaceColor

/**
 * The app background picker.
 *
 * Deliberately **not** the device wallpaper. Setting the system wallpaper from a pentesting app's
 * settings screen would change something outside the app that the user did not ask to change, and
 * that they would have to find their way back to. This sets Indra's own background, stored in the
 * app's private directory and removed when the app is uninstalled.
 *
 * The picker is the system photo picker (`PickVisualMedia`), which grants read access to the one
 * chosen file and needs no storage permission — so this works on a device where the user has
 * denied everything else.
 */
@Composable
internal fun WallpaperCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val picker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            // Null means the user backed out of the picker, which is not an error.
            if (uri != null) viewModel.setWallpaper(uri)
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .flatEdge(),
        shape = Flat.shape,
        colors = CardDefaults.cardColors(containerColor = flatSurfaceColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = Flat.elevation),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WallpaperHeader(uiState)
            WallpaperButtons(uiState, viewModel, picker)
            if (uiState.wallpaper.active) {
                DimPicker(uiState, viewModel)
            }
            uiState.wallpaperError?.let { FormError(it) }
        }
    }
}

/** Identity row: what the background is and what state it is in. */
@Composable
private fun WallpaperHeader(uiState: SettingsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SettingsPalette.Wallpaper.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Wallpaper,
                contentDescription = null,
                tint = SettingsPalette.Wallpaper,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "App background",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    when {
                        uiState.wallpaperBusy -> "Preparing image…"
                        uiState.wallpaper.active -> "A custom image is set"
                        else -> "Uses the plain theme colour"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uiState.wallpaperBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** Choose / change / remove. */
@Composable
private fun WallpaperButtons(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperAction(
            label = if (uiState.wallpaper.active) "Change image" else "Choose image",
            icon = Icons.Default.Image,
            enabled = !uiState.wallpaperBusy,
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        if (uiState.wallpaper.active) {
            WallpaperAction(
                label = "Remove",
                icon = Icons.Default.Delete,
                enabled = !uiState.wallpaperBusy,
                onClick = viewModel::clearWallpaper,
            )
        }
    }
}

/**
 * The scrim control.
 *
 * Only rendered with an image set: a control for something that does nothing is noise. The
 * explanation is here rather than in the subtitle because "Strong" otherwise reads as a bug.
 */
@Composable
private fun DimPicker(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Dim the background",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperDim.entries.forEach { dim ->
                DimChip(
                    dim = dim,
                    selected = dim == uiState.wallpaper.dim,
                    onClick = { viewModel.setWallpaperDim(dim) },
                )
            }
        }
        Text(
            text = "The image sits behind the app's text, so it is dimmed to keep labels " +
                "readable. Off shows the photo untouched.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A text action with an icon: the card's two buttons. */
@Composable
private fun WallpaperAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint =
                if (enabled) {
                    SettingsPalette.Wallpaper
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** One scrim level. A chip rather than a slider: four discrete steps, each with a name. */
@Composable
private fun DimChip(
    dim: WallpaperDim,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = dim.label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color =
            if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
