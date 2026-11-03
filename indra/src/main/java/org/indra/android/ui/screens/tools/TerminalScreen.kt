package org.indra.android.ui.screens.tools

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.terminal.TerminalInputMode
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.terminal.TerminalOverlayService
import org.indra.android.ui.screens.terminal.TerminalConsole
import org.indra.android.ui.screens.terminal.TerminalPalette

/**
 * The full-screen terminal.
 *
 * The screen is only a surface: the command runs in [TerminalSession], which outlives it, so
 * leaving for another tool (or backgrounding the app with the bubble on) does not kill the
 * shell. Re-entering re-renders the same transcript.
 */
/**
 * Everything the top bar and its menu can do, in one object.
 *
 * Bundled rather than passed as eight callbacks: the bar and the menu both need most of them, and
 * a nine-parameter composable is a signature nobody reads.
 */
private data class TerminalActions(
    val onNavigateBack: () -> Unit,
    val onToggleBubble: () -> Unit,
    val onClear: () -> Unit,
    val onToggleChroot: () -> Unit,
    val onSelectInputMode: (TerminalInputMode) -> Unit,
    val onToggleSymbolKeys: () -> Unit,
    val onAllowOverlay: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onNavigateBack: () -> Unit,
    session: TerminalSession,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // The user may grant "display over other apps" while we are paused; the offer to grant
    // must disappear when they come back, otherwise the screen keeps nagging after the fix.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(context)
        session.refresh()
    }
    LaunchedEffect(Unit) { session.refresh() }

    Scaffold(
        topBar = {
            TerminalTopBar(
                state = state,
                canOverlay = canOverlay,
                actions =
                    TerminalActions(
                        onNavigateBack = onNavigateBack,
                        onToggleBubble = { session.setPreferences(bubbleVisible = !state.bubbleVisible) },
                        onClear = session::clear,
                        onToggleChroot = { session.setChroot(!state.chroot) },
                        onSelectInputMode = { mode -> session.setPreferences(mode = mode) },
                        onToggleSymbolKeys = { session.setPreferences(symbolKeys = !state.symbolKeys) },
                        onAllowOverlay = {
                            context.startActivity(TerminalOverlayService.permissionIntent(context))
                        },
                    ),
            )
        },
    ) { padding ->
        TerminalContent(
            state = state,
            canOverlay = canOverlay,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // The window is edge-to-edge (enableEdgeToEdge), so the platform does not
                    // shrink it for the IME: without this the prompt line and the send button
                    // sit *under* the keyboard and the terminal cannot be typed into.
                    .imePadding(),
            onRun = session::run,
            onStop = session::stop,
            onAllowOverlay = { context.startActivity(TerminalOverlayService.permissionIntent(context)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalTopBar(
    state: TerminalSession.State,
    canOverlay: Boolean,
    actions: TerminalActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text("Terminal")
                Text(
                    text = state.cwd.ifBlank { "no working directory yet" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = actions.onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = actions.onToggleBubble) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = if (state.bubbleVisible) "Hide bubble" else "Show bubble",
                    tint =
                        if (state.bubbleVisible) {
                            TerminalPalette.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            TextButton(onClick = actions.onClear, enabled = !state.running) {
                Text("Clear")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Menu, contentDescription = "More")
            }
            TerminalMenu(
                state = state,
                canOverlay = canOverlay,
                actions = actions,
                onDismiss = { menuOpen = false },
                expanded = menuOpen,
            )
        },
    )
}

/**
 * The terminal's "more" menu.
 *
 * Extracted from [TerminalTopBar] because it grew past a readable length once the input type and
 * the symbol row moved in — and the top bar's own job is a title and three buttons.
 */
@Composable
private fun TerminalMenu(
    state: TerminalSession.State,
    canOverlay: Boolean,
    actions: TerminalActions,
    onDismiss: () -> Unit,
    expanded: Boolean,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = { onDismiss() }) {
        DropdownMenuItem(
            text = { Text(if (state.chroot) "Run in Alpine chroot: on" else "Run in Alpine chroot: off") },
            // Rootless mode cannot enter the guest at all (chroot/mount need
            // CAP_SYS_ADMIN), so the item is disabled with the reason rather than
            // offered and then refused on the first command.
            enabled = state.alpineInstalled && !state.running && !state.rootless,
            onClick = {
                onDismiss()
                actions.onToggleChroot()
            },
        )
        if (state.rootless) {
            DropdownMenuItem(
                text = { Text("Alpine chroot needs root (rootless mode)") },
                enabled = false,
                onClick = {},
            )
        }
        if (!state.alpineInstalled) {
            DropdownMenuItem(
                text = { Text("Install Alpine in Settings → optional artifacts") },
                enabled = false,
                onClick = {},
            )
        }
        if (!canOverlay) {
            DropdownMenuItem(
                text = { Text("Allow floating over other apps…") },
                onClick = {
                    onDismiss()
                    actions.onAllowOverlay()
                },
            )
        }
        HorizontalDivider()
        // Input type: the IME contract for a shell. A chat-style keyboard rewrites `-la`
        // and capitalises `ls`, so this is the setting that stops it.
        TerminalInputMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = if (mode == state.inputMode) "✓ ${mode.label}" else "   ${mode.label}",
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onDismiss()
                    actions.onSelectInputMode(mode)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (state.symbolKeys) "Symbol keys: on" else "Symbol keys: off") },
            onClick = {
                onDismiss()
                actions.onToggleSymbolKeys()
            },
        )
    }
}

@Composable
private fun TerminalContent(
    state: TerminalSession.State,
    canOverlay: Boolean,
    modifier: Modifier = Modifier,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onAllowOverlay: () -> Unit,
) {
    Column(modifier = modifier) {
        if (state.bubbleVisible && !canOverlay) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "The bubble stays inside Indra until it may draw over other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAllowOverlay) {
                    Text("Allow", fontSize = 12.sp)
                }
            }
        }
        TerminalConsole(
            state = state,
            modifier = Modifier.weight(1f),
            onRun = onRun,
            onStop = onStop,
        )
        if (state.chroot) {
            Text(
                text = "chroot: Alpine /bin/sh — Android's own binaries are not visible inside",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } else if (state.rootless) {
            // Say which shell this is. "Rootless" alone would not explain why `nmap` is missing,
            // and the honest answer is that this shell is the app's own, not the daemon's.
            Text(
                text = "rootless: /system/bin/sh as the app user — Android's own tools only, " +
                    "no bundled binaries, no root",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
