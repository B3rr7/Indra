package org.indra.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.indra.android.core.system.NativeCoreAction
import org.indra.android.core.system.NativeCoreState
import org.indra.android.ui.components.FormError

/** Above this luminance the accent is bright enough to need black content (green, red, teal). */
private const val CONTENT_LUMINANCE_THRESHOLD = 0.45f

/** Backing tint of the "Live" badge; the dot and text use the accent at full strength. */
private const val LIVE_BADGE_ALPHA = 0.16f

/**
 * The Native Core card: one state, one button.
 *
 * The card used to stack Install + Start/Stop under a status pill, and the caller had to reason
 * about which of them applied. Three controls for a two-dimensional lifecycle means most
 * combinations are wrong, and they were shown anyway: "Start" on a device where nothing had been
 * extracted, "Install" beside a running daemon. The button now *is* the state — it carries the
 * label, the icon and the colour of the one step the core can take next ([NativeCoreState]) — and
 * [SettingsViewModel.onCoreAction] reads the action back out of that state instead of the UI
 * passing one in.
 *
 * Layout is deliberately three rows: header (identity + status), the action (button + live badge),
 * and one outcome line. The busy state is carried by the button itself (spinner + "Installing
 * core…") rather than by a fourth progress bar, so there is exactly one place to look.
 */
@Composable
internal fun NativeCoreCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val state = uiState.coreState
    val busy = uiState.coreBusyAction

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardHeader(
                    title = "Native daemon",
                    subtitle = state.subtitle(uiState.coreVersion),
                    icon = Icons.Default.Memory,
                    accent = SettingsPalette.Core,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = state.statusLabel, color = state.pillColor())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoreActionButton(
                    state = state,
                    busy = busy,
                    onClick = viewModel::onCoreAction,
                    modifier = Modifier.weight(1f),
                )
                // The spinning badge is the "it is alive" signal, so it is only meaningful while
                // the daemon really is running and nothing is in flight.
                if (state == NativeCoreState.Running && busy == null) LiveBadge()
            }

            // Outcome: a failure is an error, not a note — "Install failed: …" used to render in
            // the same muted grey as "Daemon running".
            uiState.coreError?.let { FormError(it) }
            uiState.coreMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The morphing lifecycle button. Enabled only when nothing is in flight — the disabled state is
 * also the progress indicator, which is why [busy] outranks [state] for both copy and colour.
 */
@Composable
private fun CoreActionButton(
    state: NativeCoreState,
    busy: NativeCoreAction?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = state.accent(MaterialTheme.colorScheme.primary)
    Button(
        onClick = onClick,
        enabled = busy == null,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = contentColorOn(accent),
                disabledContainerColor = accent.copy(alpha = 0.20f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        if (busy == null) {
            Icon(
                imageVector = state.icon(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = state.buttonLabel, fontWeight = FontWeight.SemiBold, maxLines = 1)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(text = busy.busyLabel, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Spinning "live" badge: the daemon is up and answering right now. */
@Composable
private fun LiveBadge() {
    Row(
        modifier =
            Modifier
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SettingsPalette.Running.copy(alpha = LIVE_BADGE_ALPHA))
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = SettingsPalette.Running,
        )
        Text(
            text = "Live",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = SettingsPalette.Running,
        )
    }
}

/** The accent of the step the button would take: primary to install, green to start, red to stop. */
private fun NativeCoreState.accent(primary: Color): Color =
    when (this) {
        NativeCoreState.NotInstalled -> primary
        NativeCoreState.InstalledStopped -> SettingsPalette.Running
        NativeCoreState.Running -> SettingsPalette.Stopped
    }

/** Header subtitle: what the core is, in the user's terms rather than in state names. */
private fun NativeCoreState.subtitle(version: String): String =
    when (this) {
        NativeCoreState.NotInstalled -> "Bundled tools and the indrad daemon are not extracted yet"
        NativeCoreState.InstalledStopped -> "Core $version · installed, not running"
        NativeCoreState.Running -> "Core $version · daemon listening and authenticated"
    }

/** Traffic-light pill: needs attention, idle but healthy, alive. */
private fun NativeCoreState.pillColor(): Color =
    when (this) {
        NativeCoreState.NotInstalled -> SettingsPalette.Stopped
        NativeCoreState.InstalledStopped -> SettingsPalette.WakeLock
        NativeCoreState.Running -> SettingsPalette.Running
    }

private fun NativeCoreState.icon(): ImageVector =
    when (this) {
        NativeCoreState.NotInstalled -> Icons.Default.Download
        NativeCoreState.InstalledStopped -> Icons.Default.PlayArrow
        NativeCoreState.Running -> Icons.Default.Stop
    }

/**
 * `onPrimary` cannot be reused across the three accents (they are fixed palette colours), and the
 * green/red ones are bright: pick black or white from the container's own luminance.
 */
private fun contentColorOn(container: Color): Color =
    if (container.luminance() > CONTENT_LUMINANCE_THRESHOLD) Color.Black else Color.White
