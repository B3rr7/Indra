package org.indra.android.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the install half of a [CapabilityFix] so a screen can offer the fix it needs.
 *
 * Before this, the only install button was in Settings — the capability sentence on Handshakes
 * or Wifite said what was missing and then left the user to find the screen that could add it.
 *
 * One singleton holds the state, so two screens that need the same fix see the same progress
 * instead of racing two installs into the same rootfs (see [EngineInstallStateMachine]).
 * The work itself stays in [CoreManager] / [SmartToolBox]; this class only decides *which* and
 * remembers how it went.
 */
@Singleton
class EngineInstaller
    @Inject
    constructor(
        private val coreManager: CoreManager,
        private val toolBox: SmartToolBox,
    ) {
        private val machine = EngineInstallStateMachine()

        /** The one install state every screen renders. */
        val state: StateFlow<EngineInstallState> = machine.state

        /**
         * Starts the background install for [fix].
         *
         * A no-op for [CapabilityFix.None] (nothing in-app can fix it) and while another install
         * is in flight (that caller observes the same [state]).
         */
        fun run(fix: CapabilityFix) {
            when (fix) {
                is CapabilityFix.None -> Unit
                is CapabilityFix.InstallEngine -> runEngineInstall()
                is CapabilityFix.RunApk -> runApk(fix.packages)
            }
        }

        private fun runEngineInstall() {
            // Off Main: `installAlpine` unpacks an archive and touches the disk.
            scope.launch {
                machine.run("Installing the Alpine environment") { report ->
                    coreManager.installAlpine { progress -> report(progress) }
                }
            }
        }

        private fun runApk(packages: List<String>) {
            // A second guard behind `CapabilityFix.of`: the names go into a root shell.
            EnginePackages.firstRejected(packages)?.let { rejected ->
                scope.launch {
                    machine.run("Installing $rejected") {
                        Result.failure(IllegalArgumentException("`$rejected` is not a package this app installs"))
                    }
                }
                return
            }
            scope.launch {
                machine.run("Installing ${packages.joinToString(" + ")}") { report ->
                    report(null)
                    val last = toolBox.installPackages(packages).last()
                    if (last.success) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException(last.error ?: "apk add did not finish"))
                    }
                }
            }
        }

        /** Clears a finished/failed state so a screen can forget the card. */
        fun reset() = machine.reset()

        // App-lifetime on purpose: an install must survive the screen that started it (the user
        // can navigate away, and the rootfs is a shared app resource, not a screen's job).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
