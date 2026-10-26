package org.indra.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.PermissionManager
import org.indra.android.core.system.RootManager
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

    // Counts denied attempts, not just "denied": a boolean key could not re-fire
    // the snackbar when the user taps Grant and denies again (silent dead end).
    private var permissionDenials by mutableIntStateOf(0)

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
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Request permissions
        requestPermissionsIfNeeded()

        // Request root access via Magisk when not rooted
        requestRootIfNeeded()

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

    private fun requestRootIfNeeded() {
        // detectRoot() shells out (`which su`, `su -c echo test`) with blocking waitFor().
        // On a fresh Magisk install it parks the Main thread on the grant dialog — a
        // guaranteed ANR at cold start. Detect off Main; requestRootAccess() only fires
        // a system intent (safe from any thread thanks to FLAG_ACTIVITY_NEW_TASK).
        lifecycleScope.launch(Dispatchers.IO) {
            val rootStatus = rootManager.detectRoot()
            if (!rootStatus.isRooted) {
                rootManager.requestRootAccess()
            }
        }
    }
}

@Composable
fun MainScaffold(
    permissionDenials: Int = 0,
    onRequestPermissions: () -> Unit = {},
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

    Scaffold(
        bottomBar = { BottomNavBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        IndraNavHost(
            navController = navController,
            modifier = Modifier.padding(paddingValues),
        )
    }
}
