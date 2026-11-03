package org.indra.android.core.system

/**
 * The native core's lifecycle as Settings presents it: exactly one state, therefore exactly
 * one action.
 *
 * The card used to draw Install *and* Start *and* Stop side by side, choosing between Start and
 * Stop from one boolean (`daemonRunning`) and always offering Install. That pair of booleans can
 * describe impossible situations, and the card showed them: "Start" on a device where nothing had
 * ever been extracted (the tap failed with "Core is not installed"), and "Install" next to a
 * running daemon (the tap silently stopped it, re-extracted, and restarted it). One state makes
 * the wrong pair unrepresentable, and the button copy lives here instead of in the composable so
 * it can be checked without a device.
 *
 * Pure by design (no Android, no Compose): derived in [of], pinned by `NativeCoreStateTest`.
 */
sealed interface NativeCoreState {
    /** The one lifecycle step this state needs, or `null` when there is no step to take. */
    val action: NativeCoreAction?

    /** Button copy for [action] — the button always says what the tap will do. */
    val buttonLabel: String

    /** Status pill copy: what the core is doing right now. */
    val statusLabel: String

    /** Nothing extracted yet: only an install can help. */
    object NotInstalled : NativeCoreState {
        override val action: NativeCoreAction = NativeCoreAction.INSTALL
        override val buttonLabel: String = "Install Core"
        override val statusLabel: String = "Not installed"
    }

    /** Core on disk, `indrad` not listening: start it. */
    object InstalledStopped : NativeCoreState {
        override val action: NativeCoreAction = NativeCoreAction.START
        override val buttonLabel: String = "Start Daemon"
        override val statusLabel: String = "Stopped"
    }

    /** `indrad` listening and this client authenticated: stop it. */
    object Running : NativeCoreState {
        override val action: NativeCoreAction = NativeCoreAction.STOP
        override val buttonLabel: String = "Stop Daemon"
        override val statusLabel: String = "Running"
    }

    /**
     * Rootless mode is on, so there is no daemon to start — ever — and no step to offer.
     *
     * `indrad` lives in app storage and Android 10+ denies `untrusted_app` execute on
     * `app_data_file`, so a rootless start is a guaranteed exit 126 (see [DaemonStartFailure]).
     * The card used to keep offering "Start Daemon" here and every tap produced a well-worded
     * dead end.
     *
     * [action] is `null` rather than [NativeCoreAction.INSTALL]: the four rootless plugins read
     * their data from app assets and platform APIs, never from the extracted core, so installing
     * it changes nothing for them. Offering a button that does nothing useful is the same class
     * of bug as offering one that cannot work.
     */
    object Rootless : NativeCoreState {
        override val action: NativeCoreAction? = null
        override val buttonLabel: String = ""
        override val statusLabel: String = "Rootless"
    }

    companion object {
        /**
         * Derives the state from the two probes Settings already performs on `Dispatchers.IO`:
         * the `VERSION` file in the core directory and [CoreManager.isDaemonRunning].
         *
         * Precedence is deliberate: an unextracted core wins over the socket check. A socket
         * without a `VERSION` file is a hand-pushed or half-extracted daemon, and re-installing
         * is the only step that puts the app and the daemon on the same build — offering "Stop"
         * there would leave the user with nothing to start afterwards.
         */
        fun of(
            installed: Boolean,
            running: Boolean,
            rootless: Boolean = false,
        ): NativeCoreState =
            when {
                rootless -> Rootless
                !installed -> NotInstalled
                running -> Running
                else -> InstalledStopped
            }
    }
}

/**
 * What the single lifecycle button does. [busyLabel] is the copy shown while the action runs, so
 * "the button is disabled and says Installing…" needs no second state flag in the UI.
 */
enum class NativeCoreAction(
    val busyLabel: String,
) {
    INSTALL("Installing core…"),
    START("Starting daemon…"),
    STOP("Stopping daemon…"),
}
