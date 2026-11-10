package org.indra.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.EngineInstallState

private const val CARD_PADDING = 12
private const val CARD_SPACING = 8

/**
 * The same card for a [CapabilityFix], which already carries its own sentence and label.
 *
 * Screens hold the fix itself (it is what the ViewModel probed), so this overload is the normal
 * call site and keeps each screen's gate three lines instead of six.
 */
@Composable
fun EngineFixCard(
    fix: CapabilityFix,
    state: EngineInstallState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) = EngineFixCard(fix.fix, fix.buttonLabel, state, onInstall, modifier)

/**
 * The one "this screen needs a component the environment does not have" card.
 *
 * A capability gate used to arrive as a bare [FormError] sentence, and the only install button was
 * in Settings — so the screen told the user what was missing and then left them to find the screen
 * that could add it. This card carries the same sentence **and** the fix, with the shared install
 * state so two screens needing the same component show the same progress instead of starting two
 * installs.
 *
 * The sentence is not rendered in the error colour on purpose: a missing `iw` is a fixable state,
 * not a failure, and the error colour is reserved for real ones (see `FormError`).
 *
 * @param fix the capability's own explanation, from `CapabilityState`.
 * @param buttonLabel the action's label (`Install Alpine`, `Install iw + iproute2`), or null when
 *   no in-app action fixes this — the sentence is then the whole answer.
 * @param state the shared install state.
 * @param onInstall starts the install; called again on Retry after a failure.
 */
@Composable
fun EngineFixCard(
    fix: String,
    buttonLabel: String?,
    state: EngineInstallState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installing = state is EngineInstallState.Installing
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CARD_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(CARD_SPACING.dp),
        ) {
            Text(
                text = "Add the missing component",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = fix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                is EngineInstallState.Installing -> {
                    Text(
                        text = state.label.ifBlank { "Installing…" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Indeterminate until the installer reports a fraction: `apk add` prints no
                    // machine-readable progress, and a fake bar is worse than an honest spinner.
                    val progress = state.progress
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is EngineInstallState.Failed -> FormError(state.message)
                EngineInstallState.Done ->
                    Text(
                        text = "Installed.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                EngineInstallState.Idle -> Unit
            }
            if (buttonLabel != null && !installing) {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state is EngineInstallState.Failed) "Retry" else buttonLabel)
                }
            }
        }
    }
}
