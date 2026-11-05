package org.indra.android.core.system

/**
 * What Indra can actually do without root (the rootless feature).
 *
 * Rootless mode cannot run the native daemon: `indrad` and the bundled tools live in
 * app-private storage, and since Android 10 SELinux denies `untrusted_app` the execute
 * permission on `app_data_file`, so a rootless daemon start dies with exit 126 — which is
 * exactly what [DaemonStartFailure] explains. Everything served by a bundled binary therefore
 * needs root, and only the plugins implemented with platform APIs work here.
 *
 * This object is the single list of those plugins. A screen on it must not touch
 * `SmartToolBox` (no daemon gate, no `shell`/`raw` call) — otherwise it is listed here by
 * mistake and will fail on a real rootless device.
 *
 * Pure by design (no Android, no IO) so the membership rule is pinned by `RootlessTest`.
 */
object Rootless {
    /**
     * Plugins with a daemon-free implementation.
     *
     * - `port_scanner` — TCP connect scan over `java.net.Socket` ([PortScan]).
     * - `wifi_scanner` — `WifiManager`'s own scan table.
     * - `whisperpair` — BLE through the Android Bluetooth stack.
     * - `password_auditor` — pure computation over the bundled wordlist.
     * - `wifi_keygen` — pure computation over a BSSID ([WifiKeygen]); it is also the one entry
     *   here that stays available *with* root and no daemon, since it never asks for a handler.
     * - `gobuster` — an HTTP directory sweep in the app process ([DirBrute]/[DirBruteRunner]).
     *   The bundled `gobuster` binary still needs the root daemon; this is a second
     *   implementation of the same job, not the same binary, and it resolves hostnames through
     *   Android's resolver (the Go binary cannot, because Android has no `/etc/resolv.conf`).
     * - `terminal` — `/system/bin/sh` as the app's own uid ([LocalShell]). It runs no bundled
     *   binary and needs no privilege; the Alpine chroot inside it stays root-only, because
     *   `chroot(2)` and `mount(2)` need `CAP_SYS_ADMIN` ([TerminalTransport]).
     */
    val SUPPORTED_PLUGINS: Set<String> =
        setOf(
            "port_scanner",
            "wifi_scanner",
            "whisperpair",
            "password_auditor",
            "wifi_keygen",
            "gobuster",
            "terminal",
        )

    /** Whether [pluginId] has a working rootless implementation. */
    fun supports(pluginId: String): Boolean = pluginId in SUPPORTED_PLUGINS
}
