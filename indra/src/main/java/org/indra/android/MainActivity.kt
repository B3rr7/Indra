package org.indra.android

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.PermissionManager
import org.indra.android.core.system.RootAccess
import org.indra.android.core.system.RootManager
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.terminal.TerminalBubbleHost
import org.indra.android.ui.navigation.BottomNavBar
import org.indra.android.ui.navigation.IndraNavHost
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

    // Counts denied attempts, not just "denied": a boolean key could not re-fire
    // the snackbar when the user taps Grant and denies again (silent dead end).
    private var permissionDenials by mutableIntStateOf(0)

    // Root hint for the snackbar: set once at startup when `su` exists but Indra is not
    // granted. Magisk v31 has no on-demand prompt, so this is the only in-app signal.
    private var rootHint by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val denied = permissions.filterValues { !it }.keys
            // Denial rationale: a snackbar tells the user what broke and offers a retry.
            permissionDenials = if (denied.isEmpty()) 0 else permissionDenials + 1
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

        // Attach to (or start) the native daemon in the background
        lifecycleScope.launch(Dispatchers.IO) {
            coreManager.ensureDaemon()
        }

        setContent {
            IndraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScaffold(
                        permissionDenials = permissionDenials,
                        onRequestPermissions = ::requestPermissionsIfNeeded,
                        terminalSession = terminalSession,
                        rootHint = rootHint,
                        onOpenRootManager = { rootManager.openRootManager() },
                        onDismissRootHint = { rootHint = null },
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

    private fun requestPermissionsIfNeeded() {
        val missing = permissionManager.getMissingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
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
    permissionDenials: Int = 0,
    onRequestPermissions: () -> Unit = {},
    terminalSession: TerminalSession? = null,
    rootHint: String? = null,
    onOpenRootManager: (() -> Unit)? = null,
    onDismissRootHint: () -> Unit = {},
) {
    val navController = androidx.navigation.compose.rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    if (permissionDenials > 0) {
        val message =
            "Some permissions were denied — Wi-Fi details and network discovery may not work."
        LaunchedEffect(permissionDenials) {
            when (
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Grant",
                    duration = SnackbarDuration.Long,
                )
            ) {
                SnackbarResult.ActionPerformed -> onRequestPermissions()
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
        bottomBar = { BottomNavBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            IndraNavHost(
                navController = navController,
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
