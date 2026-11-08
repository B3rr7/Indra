package org.indra.android.core.system

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.indra.android.core.child.ChildProcess
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.child.ProcessEvent
import org.indra.android.core.client.KotlinDaemonClient
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.engine.ChrootEngine
import org.indra.android.core.engine.ChrootScript
import org.indra.android.core.engine.Hashcat
import org.indra.android.core.system.ProcessEvent as SystemProcessEvent
import org.indra.android.core.system.ProcessManager
import org.indra.android.core.system.RootManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long a daemon-handler probe answer is trusted before it is re-read from the daemon.
 *
 * Long enough that a screen cannot hammer the socket (one probe per press at worst), short
 * enough that starting the core while a screen is open is noticed on the next action.
 */
private const val PROBE_TTL_NANOS = 1_000_000_000L

@Singleton
class SmartToolBox
    @Inject
    @Suppress("LongParameterList")
    constructor(
        @ApplicationContext private val context: Context,
        private val client: KotlinDaemonClient,
        private val coreManager: CoreManager,
        private val childProcessManager: ChildProcessManager,
        private val processManager: ProcessManager,
        private val rootManager: RootManager,
        private val chrootEngine: ChrootEngine,
        private val networkHelper: NetworkHelper,
    ) {
        companion object {
            /** Shell exit codes 129..159 mean "terminated by signal (exit-128)". */
            private const val SHELL_SIGNAL_BASE = 128

            /** Marker [ProcessManager] appends after the command so the real pid is known. */
            private const val PID_LINE = "INDRA_PID="

            /** Exit-status echo [runDirect] appends; `$!` is empty for a foreground command. */
            private const val EXIT_LINE = "INDRA_EXIT="

            /** Tools that never emit ChildEnd (long-running daemons) are bounded by this. */
            private const val TOOL_TIMEOUT_MS = 120_000L

            /** Sniffers, spoofers and crackers run until the user stops them. */
            private const val LONG_RUNNING_TIMEOUT_MS = 30 * 60_000L

            /**
             * Bounded scans (nuclei, masscan, traceroute, crafted packets). These finish on
             * their own but routinely outlive the 120 s default — a masscan of a /16, or a
             * nuclei template run, used to be killed mid-scan and reported as a timeout.
             */
            private const val SCAN_TIMEOUT_MS = 15 * 60_000L

            /** Longest tool output line carried into `ToolResult.error` as the reason. */
            private const val MAX_REASON_CHARS = 200

            /** `ip neigh show` returns immediately; a missing daemon must not hang Home. */
            private const val NEIGHBOR_PROBE_TIMEOUT_MS = 10_000L

            /** Chroot preparation: a handful of mkdir/grep/mount calls. */
            private const val CHROOT_SETUP_TIMEOUT_MS = 30_000L

            private const val WORDLIST_PATH = "wordlists/common-passwords.txt"

            /** Where tcpdump writes .cap files when the Packet Capture screen's
             *  save-to-file switch is on; WiFi Audit prefill's from here too. */
            const val CAPTURE_PATH = "captures/last.cap"

            /**
             * Single-quotes a value for the shell that executes `shell()` command strings
             * (POSIX `'"'"'` escaping, same rule as `CoreManager.shellQuote`).
             * Every user-typed field interpolated into a command must go through this.
             *
             * This quoting is for `sh` *inside* the script. It is not how the script is
             * handed to the daemon — see [encodeArg]. Rules live in [ShellText].
             */
            fun shellQuote(value: String): String = ShellText.quote(value)

            /**
             * Encodes one argument for the daemon's tokenizer, which is *not* a POSIX shell —
             * see [ShellText.encodeArg] for the rules and the bug they prevent.
             */
            fun encodeArg(value: String): String = ShellText.encodeArg(value)

            /**
             * Status values carried by [ToolResult].
             *
             * - [STATUS_RUNNING]: one streamed line (or the initial "Executing: …" echo).
             * - [STATUS_COMPLETED]: final summary of a child that terminated.
             * - [STATUS_TIMEOUT]: final summary of a child that was killed or timed out.
             * - [STATUS_FAILED]: the command never ran (no handler, handler unavailable,
             *   start rejected). Always final, always `success = false`.
             */
            const val STATUS_RUNNING = "running"
            const val STATUS_COMPLETED = "completed"
            const val STATUS_TIMEOUT = "timeout"
            const val STATUS_FAILED = "failed"

            /** Android's toybox shell. The daemon `execvp()`s its argv directly, so a real
             *  shell only happens when it is asked for explicitly — see [shell]. */
            const val SH_BINARY = "/system/bin/sh"

            /** One row per registered tool. The table is the single source of
             *  truth: a name may appear only once — repeated registrations
             *  silently kept the LAST entry, which is how several tools ended
             *  up bound to a dead handler. */
            data class ToolSpec(
                val name: String,
                val handler: String,
                val description: String,
                val binary: String,
            )

            /** Handler ids the native core daemon registers (`loadHandlers()`),
             *  kept here so the registry can be validated in a unit test. */
            val DAEMON_HANDLERS: Set<String> =
                setOf(
                    "nmap", "ettercap", "tcpdump", "arpspoof",
                    "fusemounts", "network-radar", "msfrpcd", "raw", "blind",
                )

            /** Tools that run via direct `su -c` (bypass daemon). Their handlers are pseudo-handlers. */
            val DIRECT_HANDLERS: Set<String> =
                setOf("direct")

            /**
             * Whether [name]'s registry entry binds to the direct-`su` pseudo-handler.
             *
             * The daemon will never report a handler called `direct`, so a card gated on
             * `SmartTool.isAvailable()` can only ever dim one of these — which is how Masscan,
             * Login Cracker, Packet Forger, Credential Tester and WiFi Audit ended up saying
             * "not in native core" with the binary in the core and root granted. Their gate is
             * the bundled binary plus root; see [PluginAvailability].
             */
            fun isDirectTool(name: String): Boolean =
                TOOL_SPECS.firstOrNull { it.name == name }?.handler in DIRECT_HANDLERS

            /**
             * Registry, one spec per name. Tools whose real handler exists in
             * the daemon bind to it; every other shipped binary executes
             * through the universal `raw` handler (output streamed verbatim;
             * only nmap's handler also emits structured Port/Os events).
             *
             * Names are keyed by `Plugin.requires`, OTA updates and the
             * Settings "Bundled tools" card — do not rename without checking
             * `PluginManager`.
             */
            val TOOL_SPECS: List<ToolSpec> =
                listOf(
                    ToolSpec("nmap", "nmap", "Network scanner", "nmap"),
                    ToolSpec("masscan", "direct", "High-speed port scanner", "masscan"),
                    ToolSpec("hydra", "direct", "Login cracker", "hydra"),
                    ToolSpec("aircrack-ng", "direct", "WiFi WEP/WPA cracker", "aircrack-ng"),
                    ToolSpec("reaver", "direct", "WPS PIN cracker", "reaver"),
                    ToolSpec("hping3", "direct", "Packet crafter", "hping3"),
                    ToolSpec("nuclei", "direct", "Vulnerability scanner", "nuclei"),
                    ToolSpec("gobuster", "raw", "Directory/file brute-forcer", "gobuster"),
                    ToolSpec("bettercap", "direct", "Network attack framework", "bettercap"),
                    ToolSpec("arpspoof", "direct", "ARP spoofer", "arpspoof"),
                    ToolSpec("tcpdump", "direct", "Packet capture", "tcpdump"),
                    ToolSpec("ettercap", "ettercap", "MITM tool", "ettercap"),
                    ToolSpec("fusemounts", "fusemounts", "FUSE mounts", "fusemounts"),
                    ToolSpec("network-radar", "network-radar", "Network auto-scanner", "network-radar"),
                    ToolSpec("msfrpcd", "msfrpcd", "Metasploit RPC daemon", "msfrpcd"),
                    ToolSpec("shell", "raw", "Shell executor", "sh"),
                    ToolSpec("raw", "raw", "Raw socket", "raw"),
                    ToolSpec("blind", "blind", "Blind shell handler", "sh"),
                )

            /**
             * The `tools/<binary>` directory of every bundled tool, absolute.
             *
             * Pure so the rule is unit-tested: a direct run that cannot find its binary fails
             * with a bare `inaccessible or not found` on a rooted device, which is exactly what
             * `hydra: inaccessible or not found` was until the direct path carried this.
             */
            fun directToolDirs(coreDir: File): List<String> =
                TOOL_SPECS
                    .map { File(coreDir, "tools/${it.binary}").absolutePath }
                    .distinct()
        }

        private val gson = Gson()
        private val tools = java.util.concurrent.ConcurrentHashMap<String, SmartTool>()

        /**
         * Pids whose death we caused through [stop]. The daemon reports a signal death
         * without saying which signal, and Stop sends SIGKILL, so without this a
         * user-pressed Stop on a tool read as a crash.
         */
        private val stoppedPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        /**
         * The `resolv.conf` currently written into the chroot, or null when the chroot has not
         * been prepared in this process. Comparing the *content* rather than a boolean is what
         * makes a network switch (different DNS servers) re-write the file.
         */
        private val chrootDns = java.util.concurrent.atomic.AtomicReference<String?>(null)

        /** Serialises chroot preparation: two screens opening at once must not mount twice. */
        private val chrootMutex = Mutex()

        init {
            TOOL_SPECS.forEach { spec ->
                tools[spec.name] = SmartTool(spec.name, spec.handler, spec.description, spec.binary, client, gson)
            }
        }

        fun getTool(name: String): SmartTool? = tools[name]

        /**
         * Asks for a runnable tool **off the main thread**, with the reason attached when it
         * is not runnable.
         *
         * Every plugin screen used to write its own
         * `val t = toolBox.getTool(x); if (t == null || !t.isAvailable()) …`, which (a) ran
         * the JNI handler probe on Main — viewModelScope launches there — and (b) invented
         * its own failure copy, so "daemon down" and "handler missing" were reported with
         * six different sentences. Use this instead:
         *
         * ```
         * val gate = toolBox.requireTool("raw")
         * if (gate !is ToolHandle.Ready) return fail(gate.message)
         * val runner = gate.tool
         * ```
         */
        suspend fun requireTool(name: String): ToolHandle =
            withContext(Dispatchers.IO) {
                // A stale client-side flag must not masquerade as a dead daemon: re-attach
                // once before believing it (cheap no-op when the socket is gone).
                if (!isDaemonConnected()) coreManager.reconnect()
                val instance = getTool(name)
                when {
                    // Liveness is checked first and separately: when the daemon dies, the
                    // handler list stays cached inside the native client, so the capability
                    // probe below would report "available" and the run would fail with a bare
                    // "Failed to start nmap" instead of the daemon-down explanation.
                    !isDaemonConnected() -> ToolHandle.DaemonDown
                    instance == null -> ToolHandle.Missing(name)
                    instance.isAvailable() -> ToolHandle.Ready(instance)
                    // The daemon answered but does not know this handler: the core is old or
                    // incomplete. (This is a capability answer, so the 1 s probe cache is fine.)
                    else -> ToolHandle.Missing(name)
                }
            }

        /**
         * True when the daemon is connected, authenticated **and has not just refused a
         * command** — not merely when the JNI libraries loaded. This is what separates
         * "start the daemon" from "the core has no such handler"; reporting one as the other
         * sends the user to the wrong fix.
         */
        fun isDaemonConnected(): Boolean =
            runCatching {
                !client.hasStartFailed() &&
                    client.isConnected() &&
                    client.isAuthenticated()
            }.getOrDefault(false)

        /**
         * Asks for a tool that runs via direct `su -c` (bypasses daemon).
         *
         * Checks root access instead of daemon liveness. Returns a ToolHandle with the
         * appropriate message so the UI can show the same pattern as [requireTool].
         */
        suspend fun requireDirectTool(name: String): ToolHandle =
            withContext(Dispatchers.IO) {
                // refreshRootStatus() probes exactly like detectRoot() and also warms the cache
                // the Home/Tools card gate reads, so a card and the press cannot disagree.
                val status = rootManager.refreshRootStatus()
                if (!status.isRooted) {
                    val msg = rootManager.guidance(status)
                        ?: "Root access required. Enable root in Settings or grant in your root manager."
                    ToolHandle.RootRequired(msg)
                } else {
                    val tool = getTool(name)
                    when {
                        tool == null -> ToolHandle.Missing(name)
                        // A direct run never asks the daemon, so the binary on disk is the whole
                        // answer. Without this a core that never extracted `hydra` reached the
                        // gate as Ready and failed later with `hydra: inaccessible or not found`.
                        isDirectTool(name) && toolBinaryPath(name) == null -> ToolHandle.Missing(name)
                        else -> ToolHandle.Ready(tool)
                    }
                }
            }

        /**
         * The bundled binary for [name], or null when the core has not extracted it.
         *
         * [binary] returns the daemon-relative `./tools/<name>/<name>`; this resolves the same
         * path against the core directory for callers that need to know the file is really
         * there. The direct-`su` gate does, because those tools have no daemon handler to ask.
         */
        fun toolBinaryPath(name: String): File? =
            TOOL_SPECS
                .firstOrNull { it.name == name }
                ?.let { File(coreManager.coreDir, "tools/${it.binary}/${it.binary}") }
                ?.takeIf { it.exists() }

        /**
         * Whether [name] is present on this device, for a caller reporting presence rather than
         * gating a run (the Settings "Bundled Tools" list).
         *
         * A direct-`su` tool has no daemon handler, so `isAvailable()` is permanently false for
         * it and that list called a bundled `hydra`/`masscan`/`hping3`/`aircrack-ng` "not
         * bundled". For those, the binary on disk is the answer.
         *
         * Known limitations, unchanged here: a handler-backed entry ("raw", "shell", "blind",
         * "msfrpcd" have no binary of their own) still reports through the daemon's handler list,
         * so it reads "not bundled" while the daemon is stopped — it is, the core just is not
         * running; and the separately-installed artifacts (nuclei, bettercap) report whether the
         * `raw` handler exists, not whether the artifact is installed.
         */
        fun isToolBundled(name: String): Boolean =
            if (isDirectTool(name)) toolBinaryPath(name) != null else getTool(name)?.isAvailable() == true

        fun getAvailableTools(): List<SmartTool> = tools.values.filter { it.isAvailable() }

        fun getAllTools(): List<SmartTool> = tools.values.toList()

        fun reload() {
            tools.values.forEach { it.refresh() }
        }

        // Convenience methods returning JSON results.
        //
        // Only nmap's handler maps output to structured events (Port/Hop/Os). Every
        // other bundled handler drops lines it does not recognise, so those tools are
        // executed through the daemon's "raw" handler, which streams the real process
        // output back line by line.
        //
        // -v makes nmap print "Discovered open port ...", which the native nmap
        // handler turns into Port events (its parser ignores plain table lines).
        fun nmap(
            target: String,
            ports: String = "1-1000",
        ): Flow<ToolResult> =
            // Always `-sS`: this runs through the root daemon, so CAP_NET_RAW is there. The old
            // `if (settings.rootlessMode) "-sT"` branch was unreachable — rootless mode has no
            // daemon to spawn nmap at all, and the rootless Port Scanner returns before it ever
            // calls this (it uses `PortScan`'s connect scan instead). A silent `-sT` here only
            // suggested a rootless nmap path that does not exist, and it was the only reader of
            // the injected `settings`, so both are gone.
            execute("nmap", "-sS -sV -v -p ${shellQuote(ports)} ${shellQuote(target)}")

        /** XML output: the handler's XML parser is the only one reporting service versions. */
        fun nmapXml(
            target: String,
            ports: String = "1-1000",
            withOs: Boolean = false,
        ): Flow<ToolResult> = execute("nmap", "-sV ${if (withOs) "-O " else ""}-v -oX - -p ${shellQuote(ports)} ${shellQuote(target)}")

        /**
         * Wordlist login crack. [port] is optional — hydra's `service target`
         * two-arg form has no way to carry a port, so it is passed via `-s`.
         */
        fun hydra(
            target: String,
            service: String,
            user: String,
            passList: String = defaultWordlist(),
            port: String? = null,
        ): Flow<ToolResult> =
            rawCommand(
                "hydra",
                buildString {
                    append("${binary("hydra")} -V -l ${shellQuote(user)}")
                    port?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" -s ${shellQuote(it)}") }
                    append(" -P ${shellQuote(passList)} ${shellQuote(target)} ${shellQuote(service)}")
                },
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /** Tests a single credential pair (`-p`) instead of a whole wordlist. */
        fun hydraCredential(
            target: String,
            service: String,
            user: String,
            password: String,
            port: String? = null,
        ): Flow<ToolResult> =
            rawCommand(
                "hydra",
                buildString {
                    append("${binary("hydra")} -V -l ${shellQuote(user)}")
                    port?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" -s ${shellQuote(it)}") }
                    append(" -p ${shellQuote(password)} ${shellQuote(target)} ${shellQuote(service)}")
                },
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /** nmap's traceroute: its hop table is the only traceroute available on-device. */
        fun traceroute(target: String): Flow<ToolResult> =
            rawCommand(
                "traceroute",
                "${binary("nmap")} -sn --traceroute -v -n ${shellQuote(target)}",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        fun tcpdump(
            iface: String = "wlan0",
            filter: String = "",
            count: Int = 0,
            outFile: String? = null,
        ): Flow<ToolResult> =
            rawCommand(
                "tcpdump",
                buildString {
                    append(binary("tcpdump")).append(" -i ").append(shellQuote(iface))
                    if (outFile != null) {
                        // pcap file output; packet lines are suppressed by -w,
                        // tcpdump prints its own "packets captured" summary
                        append(" -w ").append(shellQuote(outFile))
                    } else {
                        append(" -nn -l")
                    }
                    if (count > 0) append(" -c ").append(count)
                    if (filter.isNotBlank()) append(' ').append(shellQuote(filter))
                },
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        fun networkRadar(iface: String = "wlan0"): Flow<ToolResult> =
            rawCommand(
                "network-radar",
                "${binary("network-radar")} ${shellQuote(iface)}",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        fun arpspoof(
            iface: String,
            target: String,
            gateway: String,
        ): Flow<ToolResult> =
            rawCommand(
                "arpspoof",
                "${binary("arpspoof")} -i ${shellQuote(iface)} -t ${shellQuote(target)} ${shellQuote(gateway)}",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        fun ettercap(args: String): Flow<ToolResult> =
            rawCommand("ettercap", "${binary("ettercap")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)
        /**
         * Bettercap is an optional artifact installed by [CoreManager.installBettercap] into
         * `<core>/bettercap/`, not a bundled `./tools/<name>` binary — so it runs from its
         * real install path. The old `./tools/bettercap/bettercap` never existed, which made
         * the screen fail even after a successful install.
         */
        fun bettercap(args: String = ""): Flow<ToolResult> =
            rawCommand(
                "bettercap",
                "${coreManager.bettercapPath().absolutePath} $args",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * Runs nuclei.
         *
         * `-silent -nc` are load-bearing, not cosmetic: `-silent` drops the banner/diagnostic
         * lines so stdout is one line per finding, and `-nc` keeps ANSI colour codes out of it.
         * [Nuclei.parseFindings] reads those lines, and the severity tags are what tells a finding
         * apart from a log message.
         *
         * It runs inside the Alpine chroot for one reason: nuclei creates `~/.config/nuclei`
         * before it reads a single template, and the daemon's `HOME` is `/` (read-only), so
         * every run died with `mkdir /.config: read-only file system` (`-nc` does not skip the
         * directory). The engine gives it a writable `HOME` and the only `resolv.conf` a Go
         * resolver can see on Android.
         */
        suspend fun nuclei(
            target: String,
            templates: String = "",
        ): Flow<ToolResult> {
            ensureChroot()?.let { return failure("nuclei", it) }
            val binary = "/" + ChrootScript.CORE_MOUNT.trimStart('/') + "/tools/nuclei/nuclei"
            // `-silent` swallowed *everything* but findings, and a full template set takes minutes
            // on a phone — the console sat on "Waiting for output…" for five of them, which reads
            // exactly like a hang. `-stats` prints nuclei's own progress on stderr (which the raw
            // handler forwards) and `parseFindings` matches `[severity]` case-sensitively, so the
            // `[INF]` banner/stats lines never become findings.
            return execInChroot(
                chrootEngine.wrap("$binary -nc -stats -si 10 -u ${shellQuote(target)} $templates"),
                label = "nuclei",
                timeoutMs = SCAN_TIMEOUT_MS,
            )
        }

        fun aircrack(args: String): Flow<ToolResult> =
            rawCommand("aircrack", "${binary("aircrack-ng")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)

        /**
         * Runs the Metasploit chroot's self-check and returns the fix when it cannot start, else
         * null. The screen calls this before building the search command so the failure is the
         * loader/missing-console sentence instead of `chroot: exec msfconsole: No such file`.
         */
        suspend fun msfProblem(root: String): String? {
            val result =
                withContext(Dispatchers.IO) {
                    shell(MsfLaunch.probe(root), label = "msf probe", timeoutMs = CHROOT_SETUP_TIMEOUT_MS).last()
                }
            return MsfLaunch.explain(result.rawOutput)
        }

        /**
         * Switches [iface] into monitor (or back to managed) mode, preferring the chroot.
         *
         * The ROM ships no `iw`, which is what made this a dead end (`exit 127`, and the interface
         * was left down before the `;`-separated restore was introduced). Alpine can install it, so
         * when the chroot has both `iw` and `ip` the same script runs inside it; otherwise the host
         * attempt is still made, because a vendor ROM that does ship `iw` should keep working.
         * Either way [MonitorMode.script] restores the link.
         */
        @Suppress("ReturnCount")
        suspend fun monitorMode(
            iface: String,
            monitor: Boolean,
        ): Flow<ToolResult> {
            val script = MonitorMode.script(iface, monitor)
            // `capability()`, not `chrootEngine.capability()`: the engine answers by stat-ing
            // files in the rootfs (`isAlpineInstalled` → `File.canExecute`), and this screen
            // calls monitorMode from a `viewModelScope.launch` *before* any tool starts, so the
            // direct call was a Main-thread disk read (7 × `DiskReadViolation` on device).
            val inChroot = capability(Capability.IW) == CapabilityState.Available
            if (inChroot) {
                ensureChroot()?.let { return failure("monitor mode", it) }
                return shell(
                    chrootEngine.wrapShell(script),
                    label = "monitor mode",
                    timeoutMs = CHROOT_SETUP_TIMEOUT_MS,
                )
            }
            return shell(script, label = "monitor mode", timeoutMs = CHROOT_SETUP_TIMEOUT_MS)
        }

        /**
         * Asks the engine whether one capability is present, off the main thread.
         *
         * Screens used to gate on "is the chroot installed?", which is true for a rootfs without
         * hashcat, iw or templates. [CapabilityState] carries the fix, so the screen can say which
         * `apk add` line is missing instead of a generic "not installed".
         */
        suspend fun capability(capability: Capability): CapabilityState =
            withContext(Dispatchers.IO) { chrootEngine.capability(capability) }

        /**
         * Cracks a captured WPA handshake with hashcat inside the chroot.
         *
         * Both paths must be reachable from inside the chroot, so [capturePath] has to live in the
         * core directory or the app cache (the VM copies a shared-storage capture into the cache
         * first rather than handing hashcat a path that cannot exist there). The verdict is
         * [Hashcat.interpret] on the transcript — hashcat exits 1 on "exhausted", which is a
         * completed run, not a failure.
         */
        @Suppress("ReturnCount")
        suspend fun crackHandshake(
            capturePath: String,
            wordlistPath: String? = null,
        ): Flow<ToolResult> {
            ensureChroot()?.let { return failure("hashcat", it) }
            val capture =
                chrootEngine.translate(capturePath)
                    ?: return failure(
                        "hashcat",
                        "$capturePath is not reachable inside the Alpine chroot (it must be in the app cache " +
                            "or the core directory). Copy it into the Indra folder and try again.",
                    )
            val wordlistHost = wordlistPath?.takeIf { it.isNotBlank() } ?: defaultWordlist()
            val wordlist =
                chrootEngine.translate(wordlistHost)
                    ?: return failure(
                        "hashcat",
                        "$wordlistHost is not reachable inside the Alpine chroot. Use a wordlist from the " +
                            "Indra folder (the bundled list is ${defaultWordlist()}).",
                    )
            return shell(
                chrootEngine.wrapShell(Hashcat.script(capture, wordlist)),
                label = "hashcat",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )
        }

        /**
         * Downloads/refreshes the nuclei template repository inside the chroot.
         *
         * The daemon cannot resolve DNS (no `/etc/resolv.conf` on Android), so this used to be
         * impossible; the engine now writes one from `ConnectivityManager`, so the Go resolver
         * works and `-update-templates` can reach GitHub. Templates land in `/root/nuclei-templates`
         * (HOME=/root), which `ChrootEngine` already checks for the `NUCLEI_TEMPLATES` capability.
         */
        suspend fun nucleiUpdateTemplates(): Flow<ToolResult> {
            ensureChroot()?.let { return failure("nuclei", it) }
            val binary = "/" + ChrootScript.CORE_MOUNT.trimStart('/') + "/tools/nuclei/nuclei"
            return execInChroot(
                chrootEngine.wrap("$binary -update-templates", mapOf("HOME" to "/root", "XDG_CACHE_HOME" to "/root/.cache")),
                label = "nuclei update-templates",
                timeoutMs = SCAN_TIMEOUT_MS,
            )
        }

        suspend fun gobuster(args: String): Flow<ToolResult> {
            ensureChroot()?.let { return failure("gobuster", it) }
            // The VM passes absolute host paths (wordlists, output files); inside the chroot the
            // core directory has a different path, so translate them rather than passing a path
            // that cannot exist there.
            val translated =
                args
                    .replace(coreManager.coreDir.absolutePath, ChrootScript.CORE_MOUNT)
                    .replace(context.cacheDir.absolutePath, "/data/user/0/${context.packageName}/cache")
            val binary = "/" + ChrootScript.CORE_MOUNT.trimStart('/') + "/tools/gobuster/gobuster"
            return execInChroot(
                chrootEngine.wrap("$binary $translated"),
                label = "gobuster",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )
        }

        fun hping3(
            target: String,
            args: String = "",
        ): Flow<ToolResult> =
            rawCommand(
                "hping3",
                "${binary("hping3")} $args ${shellQuote(target)}",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        fun masscan(
            target: String,
            args: String = "",
            iface: String = "",
        ): Flow<ToolResult> =
            flow {
                // masscan cannot work out an interface on Android, and the one Android calls
                // active is frequently cellular, which libpcap cannot scan — see [CaptureIface].
                // Detection reads the kernel's interface list, so it stays off Main.
                val resolved =
                    withContext(Dispatchers.IO) {
                        CaptureIface.masscanArgs(iface, args) { networkHelper.captureInterface() }
                    }
                // If no -e flag was added (empty resolved prefix), masscan will fail with
                // "could not determine default interface". Fail early with a clear message.
                if (!resolved.trimStart().startsWith("-e")) {
                    emit(
                        ToolResult(
                            tool = "masscan",
                            success = false,
                            status = STATUS_FAILED,
                            error =
                                "No suitable network interface for masscan (need Wi-Fi, Ethernet, or " +
                                    "USB tethering). Cellular interfaces (rmnet*) are not supported by libpcap.",
                            rawOutput = "",
                        ),
                    )
                    return@flow
                }
                emitAll(rawCommand("masscan", "${binary("masscan")} $resolved ${shellQuote(target)}", timeoutMs = SCAN_TIMEOUT_MS))
            }

        fun reaver(
            iface: String,
            bssid: String,
            args: String = "",
        ): Flow<ToolResult> =
            rawCommand(
                "reaver",
                "${binary("reaver")} -i ${shellQuote(iface)} -b ${shellQuote(bssid)} $args",
                // WPS PIN attacks run for hours; the screen's Stop button ends it.
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )


        /**
         * Runs a command directly via `su -c` using [ProcessManager], bypassing the daemon.
         *
         * This is for tools that don't need the daemon's structured events (only nmap's handler
         * provides Port/Os events). Running via direct `su` means:
         * - Works even when the daemon is down
         * - No daemon restart needed for binary updates (OTA)
         * - Process tree is killed on cancellation (TERM then KILL)
         * - stderr and stdout are streamed live
         *
         * The command runs as root, so user-typed fields MUST be [shellQuote]d.
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "UnusedParameter")
        /**
         * PATH prefix for a direct `su` run: every bundled tool's own directory.
         *
         * `su -c` starts in `/` with the system PATH, so a bare `hydra`/`nmap`/`hping3` is not
         * found even though the core ships it — on device that surfaced as
         * `/system/bin/sh: hydra: inaccessible or not found` while the daemon path worked. The
         * daemon never had the problem because it `chdir()`s into the core and execs
         * `./tools/<name>/<name>`; direct runs get the equivalent through PATH + [coreDir].
         */
        private fun directSearchPath(): String = directToolDirs(coreManager.coreDir).joinToString(":")

        /** Mutable state of one [runDirect] run, kept out of the function so detekt can read it. */
        private class DirectState {
            val output = StringBuilder()
            var pid: Int? = null
            var exitValue: Int? = null
            var finished = false
        }

        private fun runDirect(
            label: String,
            command: String,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
            secrets: Collection<String> = emptyList(),
        ): Flow<ToolResult> = channelFlow {
            val state = DirectState()
            val events = processManager.execute(
                // `su -c` has neither the daemon's cwd nor its library path. The bundled
                // hydra/nmap link against the core's own libssl/libcrypto, so without
                // LD_LIBRARY_PATH they die in the linker ("cannot locate symbol
                // PKCS12_SAFEBAG_free") — and `ProcessManager`'s `$!` marker is empty for a
                // foreground command, so the exit status is captured here instead.
                command = "{ $command; }; echo $EXIT_LINE\$?",
                env = mapOf("LD_LIBRARY_PATH" to File(coreManager.coreDir, "lib").absolutePath),
                toolsDir = directSearchPath(),
                workDir = coreManager.coreDir.absolutePath,
                asSu = true,
                secrets = secrets,
            )

            send(directLine(label, "Executing: $label $command", -1))

            try {
                // Let ProcessManager's own flow finish rather than closing the channel the
                // moment the exit marker arrives: closing first cancels its collector, so its
                // `exited` flag is still false and `awaitClose` fires a tree-kill `su` for a
                // tool that already exited (observed for every traceroute on device).
                val completed =
                    withTimeoutOrNull(timeoutMs) {
                        events.collect { event -> directStep(label, event, state) { send(it) } }
                    }
                // A null result means the bound elapsed; ProcessManager.awaitClose kills the
                // process tree when its collector goes away.
                send(directOutcome(label, state, completed == null, timeoutMs))
                close()
            } catch (e: Exception) {
                if (!state.finished) {
                    send(
                        ToolResult(
                            tool = label,
                            success = false,
                            status = STATUS_FAILED,
                            pid = state.pid ?: -1,
                            rawOutput = state.output.toString().trim(),
                            error = "Direct execution failed: ${e.message}",
                        ),
                    )
                    close()
                }
            }
        }

        /** Folds one process event into [state], emitting live lines as they arrive. */
        private suspend fun directStep(
            label: String,
            event: SystemProcessEvent,
            state: DirectState,
            emit: suspend (ToolResult) -> Unit,
        ) {
            when (event) {
                is SystemProcessEvent.StdoutLine -> {
                    val line = event.line
                    when {
                        state.pid == null && line.startsWith(PID_LINE) ->
                            state.pid = line.substringAfter(PID_LINE).toIntOrNull()
                        line.startsWith(EXIT_LINE) -> {
                            state.exitValue = line.substringAfter(EXIT_LINE).toIntOrNull()
                            state.finished = true
                        }
                        else -> {
                            state.output.appendLine(line)
                            emit(directLine(label, line, state.pid ?: -1))
                        }
                    }
                }
                is SystemProcessEvent.StderrLine -> {
                    state.output.appendLine(event.line)
                    emit(directLine(label, event.line, state.pid ?: -1))
                }
                // The shell's own status is always 0 (the exit echo ran last), so it is only a
                // fallback for a command that never reached the echo.
                is SystemProcessEvent.Exited ->
                    if (state.exitValue == null) {
                        state.exitValue = event.code
                        state.finished = true
                    }
                is SystemProcessEvent.Killed -> {
                    state.exitValue = SHELL_SIGNAL_BASE + event.signal
                    state.finished = true
                }
            }
        }

        /** Final emission: the tool's own exit status, or the bound if it never exited. */
        private fun directOutcome(
            label: String,
            state: DirectState,
            timedOut: Boolean,
            timeoutMs: Long,
        ): ToolResult {
            val text = state.output.toString().trim()
            val pid = state.pid ?: -1
            return when {
                timedOut -> directTimeout(label, text, timeoutMs, pid)
                state.finished -> directFinal(label, text, state.exitValue, pid)
                else ->
                    ToolResult(
                        tool = label,
                        success = false,
                        status = STATUS_FAILED,
                        pid = pid,
                        rawOutput = text,
                        error = "$label ended without reporting an exit status",
                    )
            }
        }

        private fun directLine(
            label: String,
            text: String,
            pid: Int,
        ): ToolResult =
            ToolResult(
                tool = label,
                success = true,
                status = STATUS_RUNNING,
                pid = pid,
                rawOutput = text,
            )

        private fun directFinal(
            label: String,
            output: String,
            exitValue: Int?,
            pid: Int,
        ): ToolResult {
            val success = exitValue == 0 || exitValue == 1
            return ToolResult(
                tool = label,
                success = success,
                status = if (success) STATUS_COMPLETED else STATUS_TIMEOUT,
                pid = pid,
                rawOutput = output,
                exitValue = exitValue,
                error = if (success) null else "Exited with code $exitValue${reasonSuffix(output)}",
            )
        }

        private fun directTimeout(
            label: String,
            output: String,
            timeoutMs: Long,
            pid: Int,
        ): ToolResult =
            ToolResult(
                tool = label,
                success = false,
                status = STATUS_TIMEOUT,
                pid = pid,
                rawOutput = output,
                error = "$label timed out after ${timeoutMs / 1000}s",
            )

        // ---- Direct su tools (bypass daemon) ---------------------------------

        /**
         * Wordlist login crack via direct su (bypasses daemon).
         */
        fun hydraDirect(
            target: String,
            service: String,
            user: String,
            passList: String? = null,
            port: String? = null,
        ): Flow<ToolResult> =
            flow {
                // Resolving the bundled wordlist touches filesDir; as a default argument it did
                // that on the caller's (Main) thread — a StrictMode violation caught on device.
                val list = passList ?: withContext(Dispatchers.IO) { defaultWordlist() }
                emitAll(
                    runDirect(
                        "hydra",
                        buildString {
                            append("hydra -V -l ${shellQuote(user)}")
                            port?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" -s ${shellQuote(it)}") }
                            append(" -P ${shellQuote(list)} ${shellQuote(target)} ${shellQuote(service)}")
                        },
                        timeoutMs = LONG_RUNNING_TIMEOUT_MS,
                        secrets = listOf(user, list),
                    ),
                )
            }

        /**
         * Tests a single credential pair (`-p`) instead of a whole wordlist.
         */
        fun hydraCredentialDirect(
            target: String,
            service: String,
            user: String,
            password: String,
            port: String? = null,
        ): Flow<ToolResult> =
            runDirect(
                "hydra",
                buildString {
                    append("hydra -V -l ${shellQuote(user)}")
                    port?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" -s ${shellQuote(it)}") }
                    append(" -p ${shellQuote(password)} ${shellQuote(target)} ${shellQuote(service)}")
                },
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
                secrets = listOf(user, password),
            )

        /**
         * nmap's traceroute via direct su.
         */
        fun tracerouteDirect(target: String): Flow<ToolResult> =
            runDirect(
                "traceroute",
                "nmap -sn --traceroute -v -n ${shellQuote(target)}",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        /**
         * Packet crafting via hping3 direct su.
         */
        fun hping3Direct(
            target: String,
            args: String = "",
        ): Flow<ToolResult> =
            runDirect(
                "hping3",
                "hping3 $args ${shellQuote(target)}",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        /**
         * High-speed port scanner via direct su.
         */
        fun masscanDirect(
            target: String,
            args: String = "",
            iface: String = "",
        ): Flow<ToolResult> =
            flow {
                val resolved =
                    withContext(Dispatchers.IO) {
                        CaptureIface.masscanArgs(iface, args) { networkHelper.captureInterface() }
                    }
                if (!resolved.trimStart().startsWith("-e")) {
                    emit(
                        ToolResult(
                            tool = "masscan",
                            success = false,
                            status = STATUS_FAILED,
                            error =
                                "No suitable network interface for masscan (need Wi-Fi, Ethernet, or " +
                                    "USB tethering). Cellular interfaces (rmnet*) are not supported by libpcap.",
                            rawOutput = "",
                        ),
                    )
                    return@flow
                }
                emitAll(
                    runDirect(
                        "masscan",
                        "masscan $resolved ${shellQuote(target)}",
                        timeoutMs = SCAN_TIMEOUT_MS,
                    ),
                )
            }

        /**
         * WiFi WEP/WPA cracker via direct su.
         */
        fun aircrackDirect(args: String): Flow<ToolResult> =
            runDirect(
                "aircrack",
                "aircrack-ng $args",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * ARP spoofing via direct su. Needs root (raw sockets).
         */
        fun arpspoofDirect(
            iface: String,
            target: String,
            gateway: String,
        ): Flow<ToolResult> =
            runDirect(
                "arpspoof",
                "arpspoof -i ${shellQuote(iface)} -t ${shellQuote(target)} ${shellQuote(gateway)}",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * Packet capture via direct su. Needs root (raw sockets).
         */
        fun tcpdumpDirect(
            iface: String = "wlan0",
            filter: String = "",
            count: Int = 0,
            outFile: String? = null,
        ): Flow<ToolResult> =
            runDirect(
                "tcpdump",
                buildString {
                    append("tcpdump -i ${shellQuote(iface)}")
                    if (outFile != null) {
                        append(" -w ${shellQuote(outFile)}")
                    } else {
                        append(" -nn -l")
                    }
                    if (count > 0) append(" -c $count")
                    if (filter.isNotBlank()) append(' ').append(shellQuote(filter))
                },
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * WPS PIN cracking via direct su. Needs root (raw sockets).
         */
        fun reaverDirect(
            iface: String,
            bssid: String,
            args: String = "",
        ): Flow<ToolResult> =
            runDirect(
                "reaver",
                "reaver -i ${shellQuote(iface)} -b ${shellQuote(bssid)} $args",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * Network interception via direct su. Needs root.
         */
        fun ettercapDirect(args: String): Flow<ToolResult> =
            runDirect(
                "ettercap",
                "ettercap $args",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * Bettercap via direct su. Needs root.
         */
        fun bettercapDirect(args: String): Flow<ToolResult> =
            runDirect(
                "bettercap",
                "bettercap $args",
                timeoutMs = LONG_RUNNING_TIMEOUT_MS,
            )

        /**
         * Vulnerability scanning via direct su.
         * nuclei needs a writable HOME for ~/.config/nuclei; the daemon's HOME is /
         * (read-only), so set it explicitly.
         */
        suspend fun nucleiDirect(
            target: String,
            templates: String = "",
        ): Flow<ToolResult> =
            shellDirect(
                "HOME=${shellQuote(context.filesDir.absolutePath)} nuclei -nc -stats -si 10 -u ${shellQuote(target)} $templates",
                label = "nuclei",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        /**
         * Runs a POSIX shell script through `/system/bin/sh -c`.
         *
         * The daemon does NOT use a shell: it tokenizes the command string itself and
         * `execvp()`s the resulting argv (see `cSploitClient/command.c` + `cSploitDaemon/
         * command.c`). Handing it `a && b > f` therefore passes `&&` and `>` to `a` as
         * literal arguments. Wrapping the script in sh -c is what makes `&&`, `|`,
         * redirection and globbing work.
         *
         * The script is encoded by [encodeArg] so that the daemon's tokenizer (which is
         * not a shell) hands it to `sh -c` as one argument — so anything interpolated
         * into [command] that came from user input MUST be [shellQuote]d by the caller.
         * Without that, this is a root shell (which it already is, since the daemon runs
         * as root).
         */
        fun shell(
            command: String,
            label: String = "shell",
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> {
            // A leading escaped space/tab/quote/backslash would be dropped by parse_cmd
            // (it only opens an argument on a plain char); a newline is plain and inert.
            val script = if (ShellText.startsWithDroppedChar(command)) "\n$command" else command
            return execute("shell", "$SH_BINARY -c ${encodeArg(script)}", label, timeoutMs)
        }

        /**
         * Runs a POSIX shell script through direct `su -c` (no daemon).
         * Used by tools that have been migrated off the daemon.
         */
        fun shellDirect(
            command: String,
            label: String = "shell",
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> =
            runDirect(label, command, timeoutMs)

        /** Path (relative to the core dir, the daemon's cwd) of a bundled tool binary. */
        private fun binary(name: String): String = "./tools/$name/$name"

        /**
         * Runs ONE guest command so the daemon's child *becomes* the tool.
         *
         * `shell()` hands the daemon a `/system/bin/sh -c` wrapper, and that shell **forks** the
         * guest command instead of exec'ing it (mksh does, even for a single simple command). So
         * `stop(pid)` killed the wrapper and left the real tool running as **root**, with no
         * timeout applying to it any more — verified on device: after Stop, `ps` showed
         * `nuclei` reparented to init at 700 MB, still scanning.
         *
         * `exec` collapses that chain (`sh` → `chroot` → `env` all exec), so the pid the daemon
         * reports is the tool's own pid and killing it ends the run. Only for single-command
         * lines: `exec a; b` never runs `b`, so multi-step scripts stay on [shell].
         */
        private fun execInChroot(
            line: String,
            label: String,
            timeoutMs: Long,
        ): Flow<ToolResult> = shell("exec $line", label = label, timeoutMs = timeoutMs)

        /** Bundled password list used when a screen does not supply one. */
        fun defaultWordlist(): String = File(context.filesDir, WORDLIST_PATH).absolutePath

        /** Bundled directory list used by Gobuster when a screen does not supply one. */
        fun defaultDirWordlist(): String = File(context.filesDir, "wordlists/common-dirs.txt").absolutePath

        /**
         * Absolute path of the capture file Packet Capture saves to (and WiFi Audit can be
         * prefilled with). It lives in the user-visible workspace (`/sdcard/Indra/captures`)
         * rather than the app's private directory, so a capture can be pulled off the phone
         * or fed to aircrack-ng without root and without adb.
         */
        fun capturePath(): String = File(IndraWorkspace.dir(IndraWorkspace.CAPTURES), "last.cap").absolutePath

        /**
         * Creates the whole `/sdcard/Indra` tree through the daemon (root), because the app
         * process cannot create directories there itself without "All files access".
         */
        suspend fun ensureWorkspaceDirs(): Boolean {
            val paths = IndraWorkspace.allDirectories().joinToString(" ") { shellQuote(it.absolutePath) }
            return runCatching {
                val result = shell("mkdir -p $paths && echo created").last()
                result.success && result.rawOutput.contains("created")
            }.getOrElse { false }
        }

        /**
         * Creates the capture directory through the daemon (root), because the app process
         * cannot write to `/sdcard` itself without "All files access". Returns true when the
         * directory exists afterwards.
         */
        suspend fun ensureCaptureDir(): Boolean {
            val dir = IndraWorkspace.dir(IndraWorkspace.CAPTURES)
            val quoted = shellQuote(dir.absolutePath)
            return runCatching {
                val result = shellDirect("mkdir -p $quoted && [ -d $quoted ] && echo present").last()
                result.success && result.rawOutput.contains("present")
            }.getOrElse { false }
        }

        /** Kills a running child started by [execute]. */
        fun stop(pid: Int) {
            if (pid <= 0) return
            stoppedPids += pid
            runCatching { client.kill(pid, 9) }
                .onFailure { Logger.w("kill($pid) failed: ${it.message}") }
            childProcessManager.forget(pid)
        }

        /**
         * Hosts the kernel has actually seen on the link (its neighbour table), or `null`
         * when the probe could not run — the distinction matters, because "0 targets" and
         * "we could not look" must not render the same. See [NeighborTable] for parsing.
         */
        suspend fun neighborHosts(exclude: Set<String> = emptySet()): List<String>? =
            runCatching {
                val result = command("ip neigh", "ip neigh show", NEIGHBOR_PROBE_TIMEOUT_MS).last()
                if (result.success) NeighborTable.parse(result.rawOutput, exclude) else null
            }.getOrElse { null }

        /**
         * Same probe as [neighborHosts], but keeps the link-layer address and can be
         * narrowed to one interface.
         *
         * Network Radar falls back to this when its active sweep cannot run: the kernel
         * already knows which hosts answered on the link, so "the sweep crashed" does
         * not have to mean "no hosts". `null` still means the probe itself failed.
         */
        suspend fun neighborEntries(
            iface: String? = null,
            exclude: Set<String> = emptySet(),
        ): List<NeighborTable.Entry>? =
            runCatching {
                val dev = iface?.takeIf { NeighborTable.isValidIface(it) }?.let { " dev $it" }.orEmpty()
                val probe = command("ip neigh", "ip neigh show$dev", NEIGHBOR_PROBE_TIMEOUT_MS).last()
                if (probe.success) NeighborTable.parseEntries(probe.rawOutput, exclude) else null
            }.getOrElse { null }

        private fun rawCommand(
            label: String,
            command: String,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> = execute("raw", command, label, timeoutMs)

        /** One emission saying the tool never ran, with the reason — the `failed` contract. */
        private fun failure(
            label: String,
            message: String,
        ): Flow<ToolResult> =
            flow {
                emit(
                    ToolResult(
                        tool = label,
                        success = false,
                        status = STATUS_FAILED,
                        error = message,
                        rawOutput = "",
                    ),
                )
            }

        /**
         * Runs [command] inside the Alpine chroot (real Linux userspace). Requires the chroot
         * engine to be installed.
         *
         * The chroot command line is POSIX shell text (quoted `env` values, a quoted script), so
         * it must go through [shell] — which hands it to `/system/bin/sh -c` as one token. Passing
         * it to the `raw` handler instead lets the **daemon's** tokenizer split it, and that
         * tokenizer ends an argument at its first closing quote: `… busybox sh -c '<script>'`
         * arrived truncated at the first quote *inside* the script, so the Terminal's trailing
         * `printf … "$__indra_st" "$PWD"` ran with no arguments (`sh: usage: printf FORMAT …`) and
         * the exit status/cwd were lost. Found on device with `cat /etc/alpine-release`.
         */
        suspend fun chroot(
            command: String,
            label: String = "alpine",
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> {
            ensureChroot()?.let { return failure(label, it) }
            return shell(chrootEngine.wrapShell(command), label = label, timeoutMs = timeoutMs)
        }

        /**
         * Makes the chroot usable for this session: creates the directory tree, mounts the
         * missing namespaces and writes `resolv.conf`, then reports which mounts failed.
         *
         * Returns null when the chroot is ready, or the message to show the user.
         *
         * Idempotent and cheap after the first call: the mounts are checked against
         * `/proc/mounts` rather than re-created (the old per-run `mount --bind … || true`
         * stacked a new mount on every nuclei invocation and never unmounted any), and the
         * script only re-runs when the network's DNS servers change.
         */
        @Suppress("ReturnCount")
        suspend fun ensureChroot(): String? =
            // Everything below runs off Main: `capability()` stats files in the rootfs and
            // `setupScript()`/`requiredTargets()` read `context.cacheDir` (which creates the
            // directory), so calling them from a `viewModelScope.launch` was a Main-thread disk
            // read — 10 `DiskReadViolation`s on device when Packet Capture enabled monitor mode.
            withContext(Dispatchers.IO) {
                val notInstalled =
                    (chrootEngine.capability(Capability.GNU_TOOLS) as? CapabilityState.EngineNotInstalled)
                        ?.fix
                if (notInstalled != null) return@withContext notInstalled

                val dns = chrootEngine.dnsText()
                if (dns.isBlank()) {
                    return@withContext "No DNS servers available on the active network. " +
                        "Connect to a network with DNS, or configure a custom resolver. " +
                        "Go tools (nuclei, gobuster) require a working resolver."
                }
                if (chrootDns.get() == dns) return@withContext null

                chrootMutex.withLock {
                    if (chrootDns.get() == dns) return@withLock null
                    val result =
                        runCatching {
                            shell(chrootEngine.setupScript(dns), label = "chroot setup", timeoutMs = CHROOT_SETUP_TIMEOUT_MS).last()
                        }.getOrElse {
                            return@withLock "Could not prepare the Alpine chroot: ${it.message}"
                        }
                    if (!result.success) {
                        return@withLock "Could not prepare the Alpine chroot" + reasonSuffix(result.rawOutput).ifEmpty {
                            " (setup exited with code 1)"
                        }
                    }
                    val failed = ChrootScript.failures(result.rawOutput)
                    val fatal = failed.filter { it in chrootEngine.requiredTargets() }
                    if (fatal.isNotEmpty()) {
                        Logger.w("Chroot mounts failed: ${fatal.joinToString(", ")}")
                        val message =
                            "The Alpine chroot could not mount ${fatal.joinToString(", ")} — reinstall it in Settings → Native Core."
                        return@withLock message
                    }
                    failed.forEach { Logger.w("Chroot mount $it unavailable; the environment still runs without it") }
                    chrootDns.set(dns)
                    null
                }
            }

        /** Forces the next [ensureChroot] to re-run (after installing or uninstalling the rootfs). */
        fun resetChrootState() {
            chrootDns.set(null)
        }

        /**
         * Runs one command as an argument vector through the daemon's raw handler.
         *
         * Unlike [shell] this does NOT involve a shell: the daemon tokenizes the string and
         * `execvp()`s it, so `;`, `|`, `>` and `&&` are ordinary arguments. Use it for
         * "arguments for one binary" fields, where the user should not be able to chain a
         * second command by typing a separator.
         */
        fun command(
            label: String,
            command: String,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> = execute("raw", command, label, timeoutMs)

        /**
         * Public wrapper running an arbitrary command through the daemon's raw handler.
         * Used by OTA version probes (`<binary> --version`) outside tool screens.
         */
        fun rawProbe(
            label: String,
            command: String,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> = command(label, command, timeoutMs)

        /**
         * Best-effort version string for a tool binary. Tries a handful of common
         * version flags and returns the first non-blank line of the first one that
         * produces output, or null when nothing responds. Used by the Tools screen
         * to annotate each card.
         */
        suspend fun versionOf(binary: String): String? {
            val versionRegex = Regex("\\d+\\.\\d+")
            for (flag in listOf("--version", "-V", "-v", "-version", "--help", "-h")) {
                try {
                    val result =
                        rawProbe("version", "${binary(binary)} $flag", 10_000L).last()
                    if (result.success) {
                        val line =
                            result.rawOutput.lineSequence()
                                .firstOrNull { versionRegex.containsMatchIn(it) }
                                ?.trim()
                        if (!line.isNullOrBlank()) return line
                    }
                    val line =
                        result.rawOutput.lineSequence()
                            .firstOrNull { versionRegex.containsMatchIn(it) }
                            ?.trim()
                    if (!line.isNullOrBlank()) return line
                } catch (e: Exception) {
                    Logger.w("versionOf($binary, $flag) failed: ${e.message}")
                }
            }
            return null
        }

        /**
         * `": <reason>"` for a failed child, taken from its last non-blank output line, or
         * `""` when the tool printed nothing. Truncated so a chatty tool cannot push a
         * multi-kilobyte line into a snackbar.
         */
        private fun reasonSuffix(rawOutput: String): String =
            rawOutput
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() }
                ?.let { if (it.length > MAX_REASON_CHARS) it.take(MAX_REASON_CHARS) + "…" else it }
                ?.let { ": $it" }
                ?: ""

        private fun execute(
            tool: String,
            args: String,
            label: String = tool,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> =
            channelFlow {
                val toolInstance = tools[tool]
                if (toolInstance == null) {
                    send(
                        ToolResult(
                            tool = label,
                            success = false,
                            status = STATUS_FAILED,
                            error = "Tool '$label' not found",
                            rawOutput = "",
                        ),
                    )
                    return@channelFlow
                }
                if (!toolInstance.isAvailable()) {
                    send(
                        ToolResult(
                            tool = label,
                            success = false,
                            status = STATUS_FAILED,
                            error = "Tool '$label' is not available (native handler missing)",
                            rawOutput = "",
                        ),
                    )
                    return@channelFlow
                }

                // Subscribe before starting the child so the first events are not lost.
                val events = Channel<ProcessEvent>(Channel.UNLIMITED)
                val pump =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        ChildProcessManager.events.collect { events.send(it) }
                    }

                val pid = toolInstance.start(args)
                if (pid == -1) {
                    pump.cancel()
                    send(
                        ToolResult(
                            tool = label,
                            success = false,
                            status = STATUS_FAILED,
                            // A refused start almost always means the daemon died underneath us
                            // (its native `connected` flag cannot see that), so name the fix
                            // instead of only the symptom.
                            error = "Failed to start $label — the daemon is not answering; start it in Settings → Native Core",
                            rawOutput = "",
                        ),
                    )
                    return@channelFlow
                }

                send(
                    ToolResult(
                        tool = label,
                        success = true,
                        status = STATUS_RUNNING,
                        pid = pid,
                        rawOutput = "Executing: $label $args",
                    ),
                )

                // Register it so stopAllRunning() (OTA binary swap) has something to stop.
                // Nothing ever called track() before this, which made that call a no-op.
                childProcessManager.track(ChildProcess(id = pid, command = args, handler = tool))

                val output = StringBuilder()
                val ports = mutableListOf<ToolPort>()
                val osMatches = mutableListOf<ToolOs>()

                var finished: Boolean? = null
                var crashed = false
                var exitValue: Int? = null
                try {
                    finished =
                        withTimeoutOrNull(timeoutMs) {
                            var done: Boolean? = null
                            while (done == null) {
                                when (val event = events.receive()) {
                                    is ProcessEvent.Output ->
                                        if (event.processId == pid) {
                                            output.appendLine(event.line)
                                            if (event.line.contains("Permission denied", ignoreCase = true) ||
                                                event.line.contains("Operation not permitted", ignoreCase = true) ||
                                                event.line.contains("EPERM", ignoreCase = true)
                                            ) {
                                                output.appendLine("[hint] This feature requires root. Disable rootless mode in Settings or run as root.")
                                            }
                                            send(
                                                ToolResult(
                                                    tool = label,
                                                    success = true,
                                                    status = STATUS_RUNNING,
                                                    pid = pid,
                                                    rawOutput = event.line,
                                                    ports = ports.toList(),
                                                    osMatches = osMatches.toList(),
                                                ),
                                            )
                                        }

                                    is ProcessEvent.Error ->
                                        if (event.processId == pid) {
                                            // Surface stderr live as well: tools like hydra report
                                            // "could not connect" and progress messages there.
                                            output.appendLine(event.line)
                                            send(
                                                ToolResult(
                                                    tool = label,
                                                    success = true,
                                                    status = STATUS_RUNNING,
                                                    pid = pid,
                                                    rawOutput = event.line,
                                                    ports = ports.toList(),
                                                    osMatches = osMatches.toList(),
                                                ),
                                            )
                                        }

                                    is ProcessEvent.PortFound ->
                                        if (event.processId == pid) {
                                            ports += ToolPort(event.port, event.protocol, event.service, event.version)
                                        }

                                    is ProcessEvent.OsFound ->
                                        if (event.processId == pid) {
                                            osMatches += ToolOs(event.accuracy, event.name, event.type)
                                        }

                                    is ProcessEvent.Message ->
                                        if (event.processId == pid) {
                                            output.appendLine(listOfNotNull(event.title, event.body).joinToString(": "))
                                        }

                                    is ProcessEvent.Ended ->
                                        if (event.processId == pid) {
                                            exitValue = event.exitValue
                                            done = true
                                        }

                                    is ProcessEvent.Crashed ->
                                        if (event.processId == pid) {
                                            // Died on a signal. SIGSEGV in a bundled tool says
                                            // nothing about a timeout, so report a crash — but
                                            // not when the signal was our own Stop (SIGKILL).
                                            crashed = !stoppedPids.remove(pid)
                                            done = false
                                        }
                                }
                            }
                            done
                        }
                } finally {
                    // Also runs when the collector cancels (user pressed Stop).
                    pump.cancel()
                    if (finished != true) {
                        Logger.w("$label (pid $pid) did not finish; killing it")
                        stop(pid)
                    }
                    childProcessManager.forget(pid)
                    // Do not let a finished execution leave a stale "we killed it" mark
                    // that would mislabel a later crash of the same pid.
                    stoppedPids.remove(pid)
                }

                val rawOutput =
                    output.toString().trim().ifEmpty {
                        if (ports.isEmpty()) {
                            ""
                        } else {
                            ports.joinToString("\n") { port ->
                                "open ${port.port}/${port.protocol ?: "tcp"} ${port.service.orEmpty()}".trim()
                            }
                        }
                    }

                val exitCode = exitValue
                // The daemon execvp()s the argv vector directly (no shell), so tools report
                // failures through the exit status: `ip` rejecting a malformed MAC, hydra on
                // an unknown module, grep finding nothing, msfconsole usage errors. Treating
                // "the process ended" as success made every one of those look like a pass.
                val succeeded = finished == true && (exitCode ?: 0) == 0

                send(
                    ToolResult(
                        tool = label,
                        success = succeeded,
                        status = if (finished == true) STATUS_COMPLETED else STATUS_TIMEOUT,
                        pid = pid,
                        rawOutput = rawOutput,
                        ports = ports.toList(),
                        osMatches = osMatches.toList(),
                        exitValue = exitCode,
                        error =
                            when {
                                crashed -> "$label crashed (killed by a signal)"
                                finished != true -> "$label timed out or stopped"
                                succeeded -> null
                                // "shell exited with code 255" alone told the user a step failed
                                // but never why (the reason is the tool's own last line: `ip: …
                                // No such device`). Screens show `error` in place of output, so
                                // carry the reason along instead of dropping it.
                                else -> "$label exited with code $exitCode" + reasonSuffix(rawOutput)
                            },
                    ),
                )
            }.flowOn(Dispatchers.IO)
    }

class SmartTool(
    val name: String,
    private val handler: String,
    val description: String,
    val binary: String,
    private val client: KotlinDaemonClient,
    private val gson: Gson,
) {
    /** Last probe result; only [toJson] reads it directly. */
    @Volatile
    private var available = false

    /**
     * Rate-limits the daemon probe: a cached answer is trusted for [PROBE_TTL_NANOS].
     *
     * This used to be a plain "refreshed" flag, and [isAvailable] re-probed on every call
     * whenever the answer was `false` — so a screen with a stopped daemon hammered the
     * socket on each Run press. [refresh] still forces a real probe.
     */
    private val availability = TtlProbe(PROBE_TTL_NANOS, probe = ::probeAvailability)

    fun isAvailable(): Boolean = availability.get().also { available = it }

    /** Forces a probe, ignoring the TTL. Used by [SmartToolBox.reload] after a core install. */
    fun refresh() {
        available = availability.refresh()
    }

    private fun probeAvailability(): Boolean =
        try {
            client.getHandlers()?.contains(handler) ?: false
        } catch (e: Throwable) {
            Logger.w("refresh '$handler' failed: ${e.message}")
            false
        }

    /** Starts the handler and returns the child pid, or -1 on failure. */
    fun start(args: String): Int =
        try {
            val pid = client.startCommand(handler, args, null)
            // The native `connected` flag cannot see a dead daemon, so a refused start is
            // what tells the rest of the app (cards, gates) to say "daemon not running".
            client.noteStartResult(pid != -1)
            pid
        } catch (e: Exception) {
            client.noteStartResult(false)
            Logger.e("Failed to start $name", e)
            -1
        }

    fun execute(args: String): ProcessResult {
        val pid = start(args)
        return if (pid == -1) {
            ProcessResult(pid = -1, error = "Failed to start $name — the daemon is not answering; start it in Settings → Native Core")
        } else {
            ProcessResult(pid = pid, output = "Process started (PID: $pid)")
        }
    }

    fun toJson(): String =
        gson.toJson(
            mapOf(
                "name" to name,
                "handler" to handler,
                "description" to description,
                "binary" to binary,
                "available" to available,
            ),
        )
}

data class ToolPort(
    val port: Int,
    val protocol: String? = null,
    val service: String? = null,
    val version: String? = null,
)

/** OS guess reported by the nmap handler's `<osclass` parser (Os events). */
data class ToolOs(
    val accuracy: Short,
    val name: String? = null,
    val type: String? = null,
)

data class ToolResult(
    val tool: String,
    val success: Boolean,
    /**
     * One of `SmartToolBox.STATUS_*`. A collector that renders output must append
     * [rawOutput] only while this is `running` — the final emission carries the whole
     * transcript, so appending it unconditionally duplicates everything.
     */
    val status: String = "",
    val pid: Int = -1,
    val rawOutput: String = "",
    val ports: List<ToolPort> = emptyList(),
    val osMatches: List<ToolOs> = emptyList(),
    /** Exit status of the child on the final emission; null while running or if unknown. */
    val exitValue: Int? = null,
    val parsedData: Map<String, Any>? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * True for the per-line emissions a child produces while it runs.
 *
 * The final emission ([SmartToolBox.STATUS_COMPLETED], [SmartToolBox.STATUS_TIMEOUT]) and
 * the never-ran emissions ([SmartToolBox.STATUS_FAILED]) carry the whole transcript in
 * [ToolResult.rawOutput], so collectors must not treat them as "one more line".
 */
val ToolResult.isStreaming: Boolean
    get() = status == SmartToolBox.STATUS_RUNNING

/**
 * The line to append to a scrolling transcript, or null when there is nothing new to show:
 * the initial "Executing: …" echo, blank lines and every terminal emission.
 */
val ToolResult.streamedLine: String?
    get() {
        if (!isStreaming) return null
        return rawOutput.takeIf { it.isNotBlank() && !it.startsWith("Executing:") }
    }

/**
 * Outcome of [SmartToolBox.requireTool].
 *
 * [Ready] carries a tool that can be started; the failure states carry user-facing copy so
 * the plugin screens stop inventing their own (they had six different sentences for the
 * same two conditions). `message` is empty only for [Ready].
 */
sealed interface ToolHandle {
    val message: String

    data class Ready(
        val tool: SmartTool,
    ) : ToolHandle {
        override val message: String get() = ""
    }

    /** Native libraries failed to load, or the daemon is not connected/authenticated. */
    data object DaemonDown : ToolHandle {
        override val message: String
            get() = "Native daemon is not running — install the core and start it in Settings → Native Core."
    }

    /** Root access is required but not granted. */
    data class RootRequired(
        override val message: String,
    ) : ToolHandle

    /** Daemon is reachable, but this handler/binary is not part of the installed core. */
    data class Missing(
        val name: String,
    ) : ToolHandle {
        override val message: String
            get() = "'$name' is not available — install or update the native core in Settings."
    }
}

