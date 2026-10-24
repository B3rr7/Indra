package org.indra.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
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

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val denied = permissions.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                // Show rationale or handle denial
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    MainScaffold()
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = permissionManager.getMissingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestRootIfNeeded() {
        val rootStatus = rootManager.detectRoot()
        if (!rootStatus.isRooted) {
            rootManager.requestRootAccess()
        }
    }
}

@Composable
fun MainScaffold() {
    val navController = androidx.navigation.compose.rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

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
