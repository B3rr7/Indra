package org.indra.android

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.PermissionManager
import org.indra.android.core.system.RootAccess
import org.indra.android.core.system.RootManager
import org.indra.android.core.system.RuntimePermissions
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.core.system.wallpaper.WallpaperState
import org.indra.android.core.system.wallpaper.WallpaperStore
import org.indra.android.terminal.TerminalBubbleHost
import org.indra.android.ui.components.WallpaperBackground
import org.indra.android.ui.navigation.BottomNavBar
import org.indra.android.ui.navigation.IndraNavHost
import org.indra.android.ui.navigation.Navigator
import org.indra.android.ui.navigation.Screen
import org.indra.android.ui.navigation.rememberNavigationState
import org.indra.android.ui.screens.settings.openAllFilesAccessSettings
import org.indra.android.ui.theme.IndraTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var rootManager: RootManager

    @Inject
    lateinit var coreManager: CoreManager

    @Inject
    lateinit var indraSystem: IndraSystem

    @Inject
    lateinit var terminalSession: TerminalSession

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var wallpaperStore: WallpaperStore

    /**
     * The current app background, as Compose state.
     *
     * Refreshed in [onResume] rather than only at startup: the picker is a separate activity, so
     * the user returns through `onResume` and the new image has to be picked up there. Reading it
     * once in `setContent` would leave the old background on screen until the app was restarted.
     */
    private val wallpaperState = MutableStateFlow(WallpaperState())

    // What the launch-time permission pass wants the UI to show. A data class (not three
    // separate states) so the dialog, the snackbar and the retry all read one consistent
    // snapshot instead of disagreeing mid-recomposition.
    private var permissionPrompt by mutableStateOf(PermissionPrompt())

    // Permissions whose system dialog this process already showed. `shouldShowRequestPermissionRationale`
    // returns false both *before* the first request and after "don't ask again", so the two are only
    // distinguishable by remembering the ask. Session-scoped on purpose: it is not a grant cache (the
    // grant is re-read from the platform every time), and reading prefs here would put disk I/O on
    // the main thread at startup, which the StrictMode penalty in debug builds flags.
    private val askedPermissions = mutableSetOf<String>()

    // Root hint for the snackbar: set once at startup when `su` exists but Indra is not
    // granted. Magisk v31 has no on-demand prompt, so this is the only in-app signal.
    private var rootHint by mutableStateOf<String?>(null)

    // First-install storage setup: shown once (Android 11+) to nudge the user to grant
    // All files access so captures, wordlists and reports live on shared storage.
    private var showStoragePrompt by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val denied = permissions.filterValues { !it }.keys
            // A denial the platform would still explain is retryable; one it would not is
            // "don't ask again", and only the app's settings page can grant it.
            val blocked = denied.filterNot { shouldShowRequestPermissionRationale(it) }
            permissionPrompt =
                PermissionPrompt(
                    denials = if (denied.isEmpty()) 0 else permissionPrompt.denials + 1,
                    blocked = blocked.isNotEmpty(),
                    rationale = emptyList(),
                )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Indra is dark-only (see IndraTheme), so the bars are pinned to dark in every system
        // mode: SystemBarStyle.dark keeps the icons light regardless of the device theme.
        // Without this, a light-mode device painted dark status-bar icons over our dark surface.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // BottomNavBar draws its own color to the bottom edge; stop the platform adding
            // a translucent scrim behind the three-button navigation bar.
            window.isNavigationBarContrastEnforced = false
        }

        // Request permissions
        requestPermissionsIfNeeded()

        // Root is granted ahead of time in the manager app (Magisk v31 dropped the
        // on-demand prompt), so the most this can do is tell the user where to grant it.
        checkRootGrant()

        // First-install storage setup: prompt once on Android 11+ to grant All files
        // access so captures, wordlists and reports can live on shared storage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !settings.storagePromptShown) {
            settings.storagePromptShown = true
            showStoragePrompt = true
        }

        // Attach to (or start) the native daemon in the background
        lifecycleScope.launch(Dispatchers.IO) {
            coreManager.ensureDaemon()
        }

        refreshWallpaper()

        setContent {
            IndraTheme {
                // The user's app background, if any, painted behind everything. Read here rather
                // than in a ViewModel because it has to survive navigation: the background is the
                // window's, not a screen's, and re-reading it per screen would flash the plain
                // colour on every tab switch.
                val wallpaper by wallpaperState.collectAsStateWithLifecycle()
                WallpaperBackground(state = wallpaper) {
                    MainScaffold(
                        permissionPrompt = permissionPrompt,
                        onRequestPermissions = ::requestPermissionsIfNeeded,
                        onOpenAppSettings = ::openAppPermissionSettings,
                        terminalSession = terminalSession,
                        rootHint = rootHint,
                        onOpenRootManager = { rootManager.openRootManager() },
                        onDismissRootHint = { rootHint = null },
                    )
                }
                // Explain a denied permission before the system dialog asks again; dismissing
                // this is a real "not now" and nothing is re-requested behind the user's back.
                if (permissionPrompt.rationale.isNotEmpty()) {
                    PermissionRationaleDialog(
                        onConfirm = ::confirmRationalePermissions,
                        onDismiss = { permissionPrompt = permissionPrompt.copy(rationale = emptyList()) },
                    )
                }
                // One-time first-install prompt to grant All files access (Android 11+).
                if (showStoragePrompt) {
                    AlertDialog(
                        onDismissRequest = { showStoragePrompt = false },
                        title = { Text("Set up storage access") },
                        text = {
                            Text(
                                "Indra needs All files access to save captures, wordlists " +
                                    "and reports to shared storage. Grant it in Settings " +
                                    "→ Storage, or tap Set up to open the system page now.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showStoragePrompt = false
                                    openAllFilesAccessSettings(this@MainActivity)
                                },
                            ) {
                                Text("Set up")
                            }
                        },
                        dismissButton = { TextButton(onClick = { showStoragePrompt = false }) { Text("Not now") } },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Nothing used to call this, so the "Keep Wake Lock" setting did nothing at all.
        indraSystem.acquireLocks()
    }

    override fun onDestroy() {
        indraSystem.releaseLocks()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // The photo picker is a separate activity: this is where its result becomes visible.
        refreshWallpaper()
        // Returning from Android settings is the only way a blocked ("don't ask again")
        // permission can be granted, so the banner must not survive the fix that cleared it.
        // A partial grant keeps the banner: the remaining permission is still blocked.
        if (permissionPrompt.blocked && permissionManager.getMissingPermissions().isEmpty()) {
            permissionPrompt = PermissionPrompt()
        }
    }

    /** Reads the stored background off the main thread and publishes it to Compose. */
    private fun refreshWallpaper() {
        lifecycleScope.launch(Dispatchers.IO) {
            wallpaperState.value = wallpaperStore.state()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = permissionManager.getMissingPermissions()
        if (missing.isEmpty()) return
        val states =
            missing.associateWith {
                RuntimePermissions.state(
                    rationale = shouldShowRequestPermissionRationale(it),
                    alreadyAsked = it in askedPermissions,
                )
            }
        val blocked = missing.any { states[it] == RuntimePermissions.State.BLOCKED }
        val ask = missing.filterNot { states[it] == RuntimePermissions.State.BLOCKED }
        permissionPrompt =
            permissionPrompt.copy(
                blocked = blocked || permissionPrompt.blocked,
                denials = if (blocked) permissionPrompt.denials + 1 else permissionPrompt.denials,
            )
        if (ask.isEmpty()) return
        if (ask.any { states[it] == RuntimePermissions.State.RATIONALE }) {
            // Explain first: the platform only reports a rationale after the user already
            // refused once, so launching the dialog bare is how a denial becomes permanent.
            permissionPrompt = permissionPrompt.copy(rationale = ask)
        } else {
            launchPermissionRequest(ask)
        }
    }

    /** Launches the system dialog and remembers that it was shown (never that it was granted). */
    private fun launchPermissionRequest(permissions: List<String>) {
        askedPermissions += permissions
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun confirmRationalePermissions() {
        val pending = permissionPrompt.rationale
        permissionPrompt = permissionPrompt.copy(rationale = emptyList())
        if (pending.isNotEmpty()) launchPermissionRequest(pending)
    }

    /**
     * A permission denied with "don't ask again" can no longer raise a dialog, so the app's
     * own settings page is the only place it can be granted.
     */
    private fun openAppPermissionSettings() {
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    /**
     * Detects a refused root grant and surfaces it once as a snackbar with an "Open"
     * action. Nothing is requested automatically: the old code fired
     * `com.topjohnwu.magisk.action.REQUEST_ROOT`, an action that never existed, and
     * Magisk v31 refuses at once without showing any prompt to wait for.
     *
     * Off Main: `detectRoot()` execs `su` and waits up to 15 s for it.
     */
    private fun checkRootGrant() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Rootless mode is a deliberate choice (Settings → General); don't nag about it.
            val status = if (settings.rootlessMode) null else rootManager.detectRoot()
            val hint =
                status
                    ?.takeIf { it.suAccess == RootAccess.SuAccess.DENIED && it.managerPackage != null }
                    ?.let { rootManager.guidance(it) }
            withContext(Dispatchers.Main) { rootHint = hint }
        }
    }
}

@Composable
fun MainScaffold(
    permissionPrompt: PermissionPrompt = PermissionPrompt(),
    onRequestPermissions: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    terminalSession: TerminalSession? = null,
    rootHint: String? = null,
    onOpenRootManager: (() -> Unit)? = null,
    onDismissRootHint: () -> Unit = {},
) {
    val navigationState = rememberNavigationState(Screen.Home)
    val navigator = remember(navigationState) { Navigator(navigationState) }
    val snackbarHostState = remember { SnackbarHostState() }

    if (permissionPrompt.denials > 0) {
        // A blocked permission has no in-app fix left: its dialog will never appear again, so
        // the action must open Android settings instead of re-launching a request that the
        // platform answers with an immediate denial (the old Grant button looped forever).
        val blocked = permissionPrompt.blocked
        LaunchedEffect(permissionPrompt.denials, blocked) {
            when (
                snackbarHostState.showSnackbar(
                    message =
                        if (blocked) {
                            "Some permissions are blocked — allow them for Indra in Android settings."
                        } else {
                            "Some permissions were denied — Wi-Fi details and network discovery may not work."
                        },
                    actionLabel = if (blocked) "Settings" else "Grant",
                    duration = SnackbarDuration.Long,
                )
            ) {
                SnackbarResult.ActionPerformed ->
                    if (blocked) onOpenAppSettings() else onRequestPermissions()
                SnackbarResult.Dismissed -> Unit
            }
        }
    }

    // "Root was refused — enable Indra in the Magisk app's Superuser tab". The snackbar
    // only names the fix; the actual grant happens in the manager app (Magisk 31+ shows
    // no prompt, so an in-app retry loop could never succeed).
    if (rootHint != null) {
        LaunchedEffect(rootHint) {
            when (
                snackbarHostState.showSnackbar(
                    message = rootHint,
                    actionLabel = "Open",
                    duration = SnackbarDuration.Long,
                )
            ) {
                SnackbarResult.ActionPerformed -> onOpenRootManager?.invoke()
                SnackbarResult.Dismissed -> onDismissRootHint()
            }
        }
    }

    Scaffold(
        bottomBar = { BottomNavBar(navigationState, navigator::navigateToTab) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Every destination draws its own TopAppBar, and a TopAppBar already pads itself for
        // the status bar — the host padding for it as well stacked the inset twice and pushed
        // every title ~40dp down (measured on device: Home's title sat at y≈310 instead of
        // ≈190). The host owns only the bottom bar and the navigation-bar inset; the status
        // bar belongs to the screens.
        contentWindowInsets = WindowInsets.navigationBars,
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            IndraNavHost(
                navigationState = navigationState,
                navigator = navigator,
                modifier = Modifier.padding(paddingValues),
                terminalSession = terminalSession,
            )
            // The floating terminal sits above the navigation host but inside the bottom bar,
            // so it never covers the tabs it is meant to let you use.
            if (terminalSession != null) {
                TerminalBubbleHost(
                    session = terminalSession,
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

/**
 * What the runtime-permission pass wants the UI to show.
 *
 * [denials] counts rounds rather than storing a boolean: the same snackbar has to re-fire when
 * the user denies again (a boolean key would swallow the second denial into a silent dead end).
 * [blocked] means at least one permission was denied with "don't ask again", where only the
 * app's settings page can grant it. [rationale] is the pending set the explanation dialog is
 * asking about — the only state that authorises the next system dialog.
 */
data class PermissionPrompt(
    val denials: Int = 0,
    val blocked: Boolean = false,
    val rationale: List<String> = emptyList(),
)

/**
 * Explains why the next system dialog is worth accepting, before it appears.
 *
 * A dismissal is a real "not now": nothing is re-requested behind the user's back. Only
 * [onConfirm] launches the request, which is the three-state flow the platform expects
 * (check → rationale → dialog) rather than re-prompting blindly.
 */
@Composable
private fun PermissionRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions needed") },
        text = {
            Text(
                "Indra needs a few Android permissions to read the Wi-Fi network you are on " +
                    "(its name and scan results) and, on Android 13+, to find nearby devices. " +
                    "Without them network discovery and some tools cannot list anything.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
