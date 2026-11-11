package org.indra.android.ui.screens.tools

import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.indra.android.core.system.terminal.TerminalSessions
import org.indra.android.core.system.terminal.TerminalType
import org.indra.android.terminal.TerminalOverlayService
import org.indra.android.ui.screens.terminal.TerminalConsole
import org.indra.android.ui.screens.terminal.TerminalConsoleState
import org.indra.android.ui.screens.terminal.TerminalPalette
import org.indra.android.ui.screens.terminal.TerminalTabStrip

/** Height of the zoom rocker; two halves of 22dp still reach the 44dp the bar has room for. */
private const val ROCKER_HEIGHT = 44
private const val ICON_GLYPH = 18
private const val DISABLED_ALPHA = 0.3f
private const val ROCKER_ALPHA = 0.45f

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
    val onZoom: (Int) -> Unit,
    val onToggleChroot: () -> Unit,
    val onSelectInputMode: (TerminalInputMode) -> Unit,
    val onToggleSymbolKeys: () -> Unit,
    val onAllowOverlay: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onNavigateBack: () -> Unit,
    sessions: TerminalSessions,
) {
    val tabs by sessions.state.collectAsStateWithLifecycle()
    val active = tabs.activeTab
    val session = active.session
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // One console state per tab. A tab switch takes the console out of the tree (`key`), which
    // disposes every `remember` inside it; this map is what brings back the half-typed command,
    // the caret and the scroll position with the tab.
    val consoles = remember { mutableMapOf<Int, TerminalConsoleState>() }
    val console = consoles.getOrPut(active.id) { TerminalConsoleState() }

    // The user may grant "display over other apps" while we are paused; the offer to grant
    // must disappear when they come back, otherwise the screen keeps nagging after the fix.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(context)
        sessions.refresh()
    }
    LaunchedEffect(Unit) { sessions.refresh() }

    Scaffold(
        topBar = {
            TerminalTopBar(
                state = state,
                canOverlay = canOverlay,
                actions =
                    TerminalActions(
                        onNavigateBack = onNavigateBack,
                        onToggleBubble = { sessions.setPreferences(bubbleVisible = !state.bubbleVisible) },
                        onClear = session::clear,
                        onZoom = { direction ->
                            sessions.setPreferences(
                                fontSize = TerminalType.step(state.fontSize, direction),
                            )
                        },
                        onToggleChroot = { session.setChroot(!state.chroot) },
                        onSelectInputMode = { mode -> sessions.setPreferences(mode = mode) },
                        onToggleSymbolKeys = { sessions.setPreferences(symbolKeys = !state.symbolKeys) },
                        onAllowOverlay = {
                            context.startActivity(TerminalOverlayService.permissionIntent(context))
                        },
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // The Scaffold consumed the top bar's height; consuming the same padding keeps
                    // the IME inset the rest of the screen sees the *uncovered* part only.
                    .consumeWindowInsets(padding)
                    // The window is edge-to-edge (enableEdgeToEdge), so the platform does not
                    // shrink it for the IME: without this the prompt line and the send button
                    // sit *under* the keyboard and the terminal cannot be typed into.
                    .imePadding(),
        ) {
            TerminalTabStrip(
                tabs = tabs.tabs,
                activeId = active.id,
                onSelect = sessions::select,
                onOpen = sessions::open,
                onClose = sessions::close,
            )
            // Keyed by the tab: switching disposes this composition so the scroll behaviour's
            // effects restart for the new tab, while `console` carries the state that must
            // survive (text, caret, scroll, follow).
            key(active.id) {
                TerminalContent(
                    state = state,
                    console = console,
                    canOverlay = canOverlay,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    onRun = session::run,
                    onStop = session::stop,
                    onAllowOverlay = {
                        context.startActivity(TerminalOverlayService.permissionIntent(context))
                    },
                )
            }
        }
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
            ZoomRocker(fontSize = state.fontSize, onZoom = actions.onZoom)
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
 * The zoom rocker: two chevrons in one pill, up for bigger text, down for smaller.
 *
 * A rocker rather than a menu entry because the transcript's size is the one terminal setting
 * changed *while reading* — the control belongs next to the output it scales, not two taps deep in
 * a menu. Each half is a plain clickable box instead of an [IconButton]: two 48dp buttons stacked
 * in the bar would be taller than the bar itself.
 */
@Composable
private fun ZoomRocker(
    fontSize: Int,
    onZoom: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ROCKER_ALPHA),
        modifier =
            Modifier
                .padding(end = 4.dp)
                .width(28.dp)
                .height(ROCKER_HEIGHT.dp),
    ) {
        Column {
            ZoomKey(
                icon = Icons.Default.KeyboardArrowUp,
                description = "Zoom in",
                enabled = TerminalType.canZoomIn(fontSize),
                modifier = Modifier.weight(1f),
                onClick = { onZoom(1) },
            )
            ZoomKey(
                icon = Icons.Default.KeyboardArrowDown,
                description = "Zoom out",
                enabled = TerminalType.canZoomOut(fontSize),
                modifier = Modifier.weight(1f),
                onClick = { onZoom(-1) },
            )
        }
    }
}

/**
 * One half of the rocker.
 *
 * The tint is computed rather than passed whole: an explicit `tint =` ignores `enabled`, which is
 * how the history arrows ended up looking live while disabled.
 */
@Composable
private fun ZoomKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(ICON_GLYPH.dp),
        )
    }
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
    console: TerminalConsoleState,
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
            console = console,
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
