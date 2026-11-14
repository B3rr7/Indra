package org.indra.android.core.system.msf

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.indra.android.core.data.DaemonState
import org.indra.android.core.data.MsfDaemon
import org.indra.android.core.data.MsfRpcClient
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.Logger
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.ToolRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that turns "the user wants to talk to Metasploit" into an authenticated
 * [MsfRpcClient], or into the sentence explaining why not.
 *
 * Three screens used to open their own client and repeat the same four preconditions (bundle
 * installed, chroot shell usable, `msfrpcd` answering, credentials accepted); each copy drifted,
 * and a client that was opened per call also re-authenticated per call. This class keeps one
 * client for the app's lifetime and drops it as soon as a call comes back empty, because the
 * usual cause is that `msfrpcd` was restarted underneath it.
 *
 * Every failure is a [Portal.Blocked] carrying copy the screen can show as-is: "not installed",
 * "chroot cannot start its own shell" and "msfrpcd failed to start" have three different fixes.
 */
@Singleton
class MsfRunner
    @Inject
    constructor(
        private val coreManager: CoreManager,
        private val toolBox: ToolRunner,
        private val daemon: MsfDaemon,
        private val settings: SettingsRepository,
    ) {
        /** Either a client that has already logged in, or the reason there is none. */
        sealed interface Portal {
            data class Ready(val client: MsfRpcClient) : Portal

            data class Blocked(val message: String) : Portal
        }

        private val mutex = Mutex()
        private var connected: MsfRpcClient? = null

        /** A live client, starting `msfrpcd` if it is not up yet. */
        suspend fun portal(): Portal = mutex.withLock { resolve() }

        /**
         * Forget the current client. Called when a call came back empty: a restarted `msfrpcd`
         * issues new tokens, so the next [portal] must log in again instead of retrying a dead
         * one.
         */
        suspend fun drop() {
            mutex.withLock {
                connected?.close()
                connected = null
            }
        }

        private suspend fun resolve(): Portal {
            var portal: Portal = Portal.Blocked(INSTALL_FIX)
            if (coreManager.isMsfInstalled()) {
                // A chroot whose own shell cannot start answers every call with a loader error;
                // name that instead of letting msfrpcd report a Ruby that was never found.
                val problem = toolBox.msfProblem(coreManager.msfPath().absolutePath)
                portal = if (problem != null) Portal.Blocked(problem) else started()
            }
            return portal
        }

        private suspend fun started(): Portal {
            daemon.start()
            val ready = daemon.state.value == DaemonState.READY
            var portal: Portal = Portal.Blocked("msfrpcd failed to start: ${daemon.logs.value.joinToString("\n")}")
            if (ready) {
                portal = connected?.let { Portal.Ready(it) } ?: login()
            } else {
                dropLocked()
            }
            return portal
        }

        private suspend fun login(): Portal {
            var portal: Portal = Portal.Blocked(LOGIN_FIX)
            val client = MsfRpcClient.create(host = HOST, port = settings.msfRpcPort)
            if (client == null) {
                Logger.w("MsfRunner: msfrpcd is up but refused the login")
            } else {
                connected = client
                portal = Portal.Ready(client)
            }
            return portal
        }

        private fun dropLocked() {
            connected?.close()
            connected = null
        }

        companion object {
            const val INSTALL_FIX =
                "Metasploit is not installed — install the Metasploit bundle in Settings → Bundle " +
                    "Updates, or paste a rootfs tarball URL on the card above."

            const val LOGIN_FIX =
                "msfrpcd is running but refused the login — stop and start it again from the card above."

            private const val HOST = "127.0.0.1"
        }
    }
