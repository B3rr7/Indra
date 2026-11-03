package org.indra.android.core.system.terminal

/**
 * Which shell a typed terminal command is handed to.
 *
 * The Terminal used to have exactly one answer — the daemon's `shell` handler — because the
 * daemon was the only thing that could run a script. That made the whole screen unavailable in
 * rootless mode, where there is no daemon at all (see `Rootless`), even though an app can start
 * `/system/bin/sh` for itself without any privilege.
 *
 * Three transports, and the difference matters to the user:
 * - [DAEMON] — the root daemon. Sees the whole device, runs the bundled tools, needs root.
 * - [LOCAL] — `/system/bin/sh` as this app's own uid. No privilege, so it sees toybox and the
 *   app's own files and nothing else. This is what rootless mode gets.
 * - [CHROOT] — the Alpine guest, entered with `chroot(2)` and `mount(2)`. Both need
 *   `CAP_SYS_ADMIN`, so this is a root-only transport no matter what mode the user picked.
 *
 * Pure by design (no Android, no IO) so the decision is pinned by `TerminalTransportTest`.
 */
enum class TerminalTransport {
    DAEMON,
    LOCAL,
    CHROOT,
    ;

    companion object {
        /**
         * The transport for this configuration, or `null` when the request cannot be honoured.
         *
         * `chroot = true` in rootless mode is the one impossible combination: entering the guest
         * needs `CAP_SYS_ADMIN` and a uid-0 namespace, neither of which an app uid has. Returning
         * `null` rather than silently falling back to [LOCAL] is deliberate — a command the user
         * asked to run inside Alpine must not quietly run outside it, because the two see
         * different filesystems and different binaries.
         */
        fun of(
            rootless: Boolean,
            chroot: Boolean,
        ): TerminalTransport? =
            when {
                chroot && rootless -> null
                chroot -> CHROOT
                rootless -> LOCAL
                else -> DAEMON
            }
    }
}
