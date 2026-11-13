package org.indra.android.core.system

import android.content.Context
import android.os.Process
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
import org.indra.android.core.child.ChildKind
import org.indra.android.core.child.ChildProcess
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.child.ProcessEvent
import org.indra.android.core.client.KotlinDaemonClient
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.engine.ChrootEngine
import org.indra.android.core.engine.ChrootScript
import org.indra.android.core.engine.EnginePackages
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
        private val settings: SettingsRepository,
    ) {
        companion object {
            /** Shell exit codes 129..159 mean "terminated by signal (exit-128)". */
            private const val SHELL_SIGNAL_BASE = 128

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
                    ToolSpec("gobuster", "direct", "Directory/file brute-forcer", "gobuster"),
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
         * True while the socket is open and the credential was accepted.
         *
         * Deliberately **not** influenced by whether the last command started: one refused
         * start (`raw` handler missing, tool rejected by the daemon) is not a dead daemon, and
         * folding it in here was the F4 trap — `startFailed` latched with no reset path, so
         * after a single refusal every card read "daemon not running" while `indrad` was
         * healthy, and no liveness transition ever fired to correct it. A refused start is
         * reported by the run's own `ToolResult`, which names the real reason.
         *
         * [daemonConnected] carries the same state as a flow, for screens that must repaint
         * when the daemon attaches or dies.
         */
        fun isDaemonConnected(): Boolean =
            runCatching { client.isConnected() && client.isAuthenticated() }.getOrDefault(false)

        /** Observable form of [isDaemonConnected], republished on every transition. */
        val daemonConnected: StateFlow<Boolean> = client.daemonConnected

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
            execute(
                "nmap",
                "-sS -sV -v -p ${shellQuote(ports)} ${shellQuote(target)}",
                // A real scan is minutes, not seconds: `-sV` fingerprints every open port and a
                // wide range is normal. The 120 s default cut it off mid-scan and rendered
                // "nmap timed out or stopped" while the daemon child kept running — measured on
                // device for `-sS -sV -p 1-65535`, which needs far longer than the one-shot
                // budget. Scans get the scan budget.
                timeoutMs = SCAN_TIMEOUT_MS,
            )

        /** XML output: the handler's XML parser is the only one reporting service versions. */
        fun nmapXml(
            target: String,
            ports: String = "1-1000",
            withOs: Boolean = false,
        ): Flow<ToolResult> =
            execute(
                "nmap",
                "-sV ${if (withOs) "-O " else ""}-v -oX - -p ${shellQuote(ports)} ${shellQuote(target)}",
                // Same reason as [nmap]: the XML path is the Inspector's, and `-O` plus `-sV`
                // over a wide range is a multi-minute run.
                timeoutMs = SCAN_TIMEOUT_MS,
            )

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
        ): Flow<ToolResult> =
            when (val chroot = ensureChroot()) {
                null ->
                    Nuclei.templatesDir(coreManager.coreDir, coreManager.alpinePath())?.let { dir ->
                        // The chroot sees the core dir at its own mount point, so the same
                        // directory has a different path here. Both runners resolve it through
                        // `Nuclei.templatesDir`, so a refresh is visible to whichever one runs.
                        val inside = ChrootScript.CORE_MOUNT + dir.absolutePath.removePrefix(coreManager.coreDir.absolutePath)
                        val binary = "/" + ChrootScript.CORE_MOUNT.trimStart('/') + "/tools/nuclei/nuclei"
                        // `-silent` swallowed *everything* but findings, and a full template set
                        // takes minutes on a phone — the console sat on "Waiting for output…" for
                        // five of them, which reads exactly like a hang. `-stats` prints nuclei's
                        // own progress on stderr (which the raw handler forwards) and
                        // `parseFindings` matches `[severity]` case-sensitively, so the `[INF]`
                        // banner/stats lines never become findings.
                        execInChroot(
                            chrootEngine.wrap(
                                "$binary -nc -stats -si 10 -t ${shellQuote(inside)} -u ${shellQuote(target)} $templates",
                                // Same split as the refresh: the template set lives at the core
                                // mount, the run's own config stays in the rootfs (see
                                // [Nuclei.HOST_CONFIG_DIR]).
                                mapOf(
                                    "HOME" to ChrootScript.CORE_MOUNT,
                                    "XDG_CONFIG_HOME" to "/root/.config",
                                    "XDG_CACHE_HOME" to "/root/.cache",
                                ),
                            ),
                            label = "nuclei",
                            timeoutMs = SCAN_TIMEOUT_MS,
                        )
                    } ?: failure("nuclei", Nuclei.NO_TEMPLATES_FIX)
                else -> failure("nuclei", chroot)
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
         * `apk add` inside the installed chroot — the one-tap fix for a missing `iw`/`ip`.
         *
         * The chroot is prepared first ([ensureChroot] writes the `resolv.conf` `apk` needs) and
         * every name is checked against [EnginePackages] here as well as at the call site: they
         * are interpolated into a root shell, so a name that did not come from that set is
         * refused rather than quoted and hoped for.
         */
        suspend fun installPackages(packages: List<String>): Flow<ToolResult> {
            // Checked before `ensureChroot()` so a refused name cannot trigger any work at all.
            val rejection = packageRejection(packages) ?: ensureChroot()
            if (rejection != null) return failure("apk", rejection)
            val args = packages.joinToString(" ") { shellQuote(it) }
            return execInChroot(
                chrootEngine.wrapShell("apk add --no-cache $args"),
                label = "apk add",
                timeoutMs = SCAN_TIMEOUT_MS,
            )
        }

        /** Why [packages] must not be installed, or null when the request is one this app allows. */
        private fun packageRejection(packages: List<String>): String? {
            val rejected = EnginePackages.firstRejected(packages)
            return when {
                rejected != null -> "Refusing to install `$rejected`: not a package this app installs."
                packages.isEmpty() -> "No packages were requested."
                else -> null
            }
        }

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
         * works and `-update-templates` can reach GitHub.
         *
         * `HOME` is the core mount, not `/root`, and that one word is the whole fix: nuclei writes
         * its templates to `$HOME/nuclei-templates`, while the scan runs on the **host** (its
         * `HOME` is the core dir) and reads the same directory there. With `HOME=/root` the
         * download landed in `/root/nuclei-templates` — inside the rootfs, invisible to every
         * scan — so a refresh could succeed and the next scan still say "no templates provided".
         * `XDG_CACHE_HOME` and `XDG_CONFIG_HOME` stay in the rootfs because they are transient:
         * with `HOME` pointing at the core mount, nuclei would otherwise write
         * `/indra/.config/nuclei/.templates-config.json`, and the **host** run reads that very
         * file (same disk) and then trusts its `/indra/nuclei-templates` record — a path that does
         * not exist outside the chroot. Device evidence: a scan with the full set on disk stopped
         * at `nuclei-templates are not installed, installing...` and tried to re-download it.
         * [Nuclei.HOST_CONFIG_DIR] gives the host run its own config home so neither can poison
         * the other.
         *
         * Two further device findings are folded in here:
         *
         * 1. `-update-templates` **exits 0 when the download failed** (a timed-out body write is
         *    reported as a `WRN`, which nuclei only prints with `-v`). So `-v` is passed, and the
         *    directory is checked afterwards: an exit 0 that left nothing behind is reported as a
         *    failure naming the reason in the console, never as "templates updated".
         * 2. The guest is root with the daemon's `077` umask, so the tree landed root-owned `700`
         *    — unreadable by the app, which makes `Nuclei.templatesDir` (and so the
         *    `NUCLEI_TEMPLATES` capability and the host scan's `-t`) disagree with reality. The
         *    same script hands the directory back to this app's uid.
         */
        suspend fun nucleiUpdateTemplates(): Flow<ToolResult> {
            ensureChroot()?.let { return failure("nuclei", it) }
            val binary = "/" + ChrootScript.CORE_MOUNT.trimStart('/') + "/tools/nuclei/nuclei"
            val dir = ChrootScript.CORE_MOUNT + "/" + Nuclei.TEMPLATES_DIR
            // `XDG_CONFIG_HOME` is a fresh rootfs dir, so nuclei's optional ignore list is
            // missing and it prints two `[ERR]` lines before doing any work.
            val ignore = shellQuote("/root/.config/nuclei/" + Nuclei.IGNORE_FILE)
            val script =
                "mkdir -p /root/.config/nuclei; [ -s $ignore ] || echo '{}' > $ignore; " +
                    "$binary -update-templates -v; rc=\$?; " +
                    "if [ -d ${shellQuote(dir)} ]; then chmod -R a+rX ${shellQuote(dir)}; " +
                    "chown -R ${Process.myUid()}:${Process.myUid()} ${shellQuote(dir)}; fi; exit \$rc"
            return flow {
                var terminal: ToolResult? = null
                execInChroot(
                    chrootEngine.wrap(
                        script,
                        mapOf(
                            "HOME" to ChrootScript.CORE_MOUNT,
                            "XDG_CONFIG_HOME" to "/root/.config",
                            "XDG_CACHE_HOME" to "/root/.cache",
                        ),
                    ),
                    label = "nuclei update-templates",
                    timeoutMs = SCAN_TIMEOUT_MS,
                ).collect { r ->
                    if (r.isStreaming) emit(r) else terminal = r
                }
                val done = terminal ?: return@flow
                val installed =
                    withContext(Dispatchers.IO) {
                        Nuclei.templatesDir(coreManager.coreDir, coreManager.alpinePath()) != null
                    }
                emit(
                    if (done.success && !installed) {
                        done.copy(
                            success = false,
                            status = STATUS_FAILED,
                            error = Nuclei.NO_TEMPLATES_DOWNLOADED,
                        )
                    } else {
                        done
                    },
                )
            }
        }

        /**
         * Runs gobuster through a direct `su -c`, not the Alpine chroot.
         *
         * It cannot run in the chroot at all: the artifact is a Go binary built for Android, so
         * its ELF interpreter is `/system/bin/linker64` → `/apex/com.android.runtime/bin/linker64`,
         * and inside the musl rootfs the kernel answers `not found` (device-verified — a run that
         * used to be reported as a resolver failure was really the binary never starting).
         *
         * Running it on the host means it has no resolver (see [GoResolvers]), so the screen
         * resolves the target itself and passes an address plus a `Host:` header
         * ([HostRewrite]). Absolute paths from the VM (`<core>/wordlists/…`) are already valid
         * here, so nothing is translated.
         */
        suspend fun gobuster(args: String): Flow<ToolResult> =
            runDirect("gobuster", "gobuster $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)

        fun hping3(
            target: String,
            args: String = "",
        ): Flow<ToolResult> =
            rawCommand(
                "hping3",
                "${binary("hping3")} $args ${shellQuote(target)}",
                timeoutMs = SCAN_TIMEOUT_MS,
            )

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
            } finally {
                // The registry is what makes this run visible to the long-run notification and
                // to stopAllRunning(); leaving an entry behind would report a phantom tool.
                state.pid?.let { childProcessManager.forget(ChildKind.DIRECT, it) }
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
                is SystemProcessEvent.Started -> {
                    state.pid = event.pid
                    // Track before the pid reaches the screen: a Stop press that arrives with
                    // the first result must find the entry (ProcessManager emits this before
                    // any output line).
                    childProcessManager.track(
                        ChildProcess(
                            id = event.pid,
                            command = label,
                            handler = label,
                            kind = ChildKind.DIRECT,
                        ),
                    )
                }
                is SystemProcessEvent.StdoutLine -> {
                    val line = event.line
                    when {
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
         *
         * Two things masscan cannot work out on Android are supplied explicitly, because both
         * are one question the kernel already answers: the interface ([CaptureIface]) and the
         * Ethernet destination ([CaptureRoute]). Without the second it reports `resolving
         * router 0.0.0.0 with ARP` and sends nothing — see [CaptureRoute] for why the target's
         * own MAC, and not the gateway's, is the right frame destination here.
         */
        fun masscanDirect(
            target: String,
            args: String = "",
            iface: String = "",
        ): Flow<ToolResult> =
            flow {
                val plan =
                    withContext(Dispatchers.IO) {
                        CaptureIface.plan(iface, args) { networkHelper.captureInterface() }
                    }
                if (!plan.namesInterface) {
                    emit(noScannableInterface())
                    return@flow
                }
                val route =
                    withContext(Dispatchers.IO) {
                        masscanRoute(target, args, plan.iface)
                    }
                val command =
                    listOf("masscan", plan.prefix.trim(), args.trim(), route.trim(), shellQuote(target))
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                emitAll(runDirect("masscan", command, timeoutMs = SCAN_TIMEOUT_MS))
            }

        /**
         * `--router-mac`/`--router-ip` for [target], or "" when the caller already named one
         * or the kernel knows neither the target nor a gateway on [iface].
         *
         * The neighbour table is read through `su` (a direct run has no daemon, and the app uid
         * cannot read the table itself); it is one short command, and its output is consumed
         * here rather than shown, so the console stays the scan's own transcript.
         *
         * One ICMP echo is sent first only when the table does not know a single-host target —
         * an idle entry ages out, and the fallback hop then reports `0 found` for a host that
         * answers (device-verified; see [CaptureRoute.needsWarm]).
         */
        private suspend fun masscanRoute(
            target: String,
            args: String,
            iface: String?,
        ): String {
            if (CaptureRoute.namesNextHop(args)) return ""
            val gateway = gatewayOn(iface)
            return nextHopFlags(target, gateway, iface)
        }

        /** The neighbour table's answer, re-read once after warming a single-host target. */
        private suspend fun nextHopFlags(
            target: String,
            gateway: String?,
            iface: String?,
        ): String {
            val first = CaptureRoute.hop(target, gateway, neighborMacs(iface)::get)
            if (!CaptureRoute.needsWarm(first, target)) return CaptureRoute.flags(first)
            warmNeighbor(target)
            return CaptureRoute.flags(CaptureRoute.hop(target, gateway, neighborMacs(iface)::get))
        }        /**
         * Populates the kernel's neighbour entry for [target] with one echo request.
         *
         * `ping` is the reliable way to make the kernel resolve an address (an unanswered ARP
         * leaves the entry `FAILED`, which reads as "unknown" and falls back cleanly). The
         * command runs as root through [runDirect], so the typed target is quoted.
         */
        private suspend fun warmNeighbor(target: String) {
            runCatching { runDirect("neigh", "ping -c 1 -W 1 ${shellQuote(target)}", NEIGHBOR_PROBE_TIMEOUT_MS).last() }
        }

        /** The gateway on the scan interface, or null when the phone *is* the router there. */
        private fun gatewayOn(iface: String?): String? =
            runCatching { networkHelper.gatewayFor(iface) }.getOrNull()

        /** `ip neigh show` on [iface] as root, as address → MAC. Empty when the probe failed. */
        private suspend fun neighborMacs(iface: String?): Map<String, String> =
            runCatching {
                val probe =
                    runDirect("neigh", CaptureRoute.neighCommand(iface), NEIGHBOR_PROBE_TIMEOUT_MS)
                        .last()
                if (!probe.success) {
                    emptyMap()
                } else {
                    NeighborTable.parseEntries(probe.rawOutput)
                        .filter { it.mac.isNotEmpty() }
                        .associate { it.ip to it.mac }
                }
            }.getOrElse { emptyMap() }

        /**
         * masscan with no interface at all would only print `FAIL: could not determine default
         * interface`, so the run is refused with the reason and what would work instead.
         */
        private fun noScannableInterface(): ToolResult =
            ToolResult(
                tool = "masscan",
                success = false,
                status = STATUS_FAILED,
                error =
                    "No suitable network interface for masscan (need Wi-Fi, Ethernet, or " +
                        "USB tethering). Cellular interfaces (rmnet*) are not supported by libpcap.",
                rawOutput = "",
            )

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
         *
         * nuclei needs a writable HOME for `~/.config/nuclei`; the daemon's HOME is `/`
         * (read-only), so it is set to the core dir explicitly — which is also where
         * [nucleiUpdateTemplates] writes the template set (see [Nuclei.templatesDir]). `-t` names
         * that directory outright instead of relying on nuclei's `$HOME/nuclei-templates`
         * default, because a run that quietly finds no templates reports the same "no templates
         * provided" line as a broken install.
         *
         * `XDG_CONFIG_HOME` keeps this run's config out of the one the chroot refresh may write:
         * nuclei trusts its recorded template directory over `-t`, and the guest's record names
         * `/indra/...`, a path that does not exist here. Sharing it made a scan with 11k templates
         * on disk stop and try to download them (see [Nuclei.HOST_CONFIG_DIR]).
         */
        suspend fun nucleiDirect(
            target: String,
            templates: String = "",
        ): Flow<ToolResult> {
            val dir = Nuclei.templatesDir(coreManager.coreDir, coreManager.alpinePath())
                ?: return failure("nuclei", Nuclei.NO_TEMPLATES_FIX)
            val home = shellQuote(context.filesDir.absolutePath)
            val configHome = shellQuote(File(context.filesDir, Nuclei.HOST_CONFIG_DIR).absolutePath)
            // A hostname only the device's own resolver knows used to fail with "no such host":
            // a static Go binary has no `/etc/resolv.conf` here and falls back to 127.0.0.1:53.
            // `-r` is nuclei's own way in, so the file the network's servers are written to is
            // passed only when there are any (see [GoResolvers]).
            val resolvers =
                withContext(Dispatchers.IO) {
                    GoResolvers.text(networkHelper.getDnsServers())
                        .takeIf { it.isNotEmpty() }
                        ?.let { text ->
                            val file = File(context.filesDir, GoResolvers.FILE_NAME)
                            runCatching { file.writeText(text) }.getOrNull()?.let { file.absolutePath }
                        }
                }
            val resolverFlag = resolvers?.let { " -r ${shellQuote(it)}" }.orEmpty()
            // nuclei opens `<config home>/nuclei/.nuclei-ignore` on every run and prints two
            // `[ERR]` lines when it is unusable — device-verified at the top of every scan:
            // "Could not read nuclei-ignore file" when absent, then "Could not parse …: EOF"
            // for a blank file, because the format is a YAML mapping (`{}` is valid). The file
            // is created here rather than by the app because the first run (and the guest
            // refresh) leave the config dir root-owned: the app cannot write into it, and
            // `createNewFile` there failed with "Permission denied" **before the scan started**.
            val configNuclei = shellQuote(File(context.filesDir, "${Nuclei.HOST_CONFIG_DIR}/nuclei").absolutePath)
            val ignore = shellQuote(File(context.filesDir, "${Nuclei.HOST_CONFIG_DIR}/nuclei/${Nuclei.IGNORE_FILE}").absolutePath)
            return shellDirect(
                // Absolute paths: `runDirect` gives the script only the tool dirs on PATH, so a
                // bare `mkdir`/`touch` is not found (device-verified: "inaccessible or not found").
                // `{}` and not an empty file: the list is decoded as YAML into a struct, so a blank
                // file is `Could not parse nuclei-ignore file: EOF` twice (also device-verified).
                "/system/bin/mkdir -p $configNuclei; [ -s $ignore ] || echo '{}' > $ignore; " +
                    "HOME=$home XDG_CONFIG_HOME=$configHome nuclei -nc -stats -si 10$resolverFlag " +
                    "-t ${shellQuote(dir.absolutePath)} -u ${shellQuote(target)} $templates",
                label = "nuclei",
                timeoutMs = SCAN_TIMEOUT_MS,
            )
        }

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

        /** Kills a running child started by [execute] or [runDirect]. */
        fun stop(pid: Int) {
            if (pid <= 0) return
            // Track which stops were ours, so a signal death is not reported as a crash. A
            // direct run never reports one, so only a daemon child id belongs in this set.
            if (!childProcessManager.isDirect(pid)) stoppedPids += pid
            // The registry owns the routing: a direct run is an OS pid that needs a `kill(2)`
            // tree walk, everything else is a daemon child id. Sending a direct pid to the
            // daemon used to leave the tool running (and could signal an unrelated child).
            childProcessManager.stop(pid)
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
         * version flags and returns the first non-blank, escape-free line that looks
         * like a version, or null when nothing responds. Used by the Tools screen to
         * annotate each card — see [ToolVersion] for the two ways the obvious version
         * of this went wrong (ANSI escapes in the card, the command echo winning).
         */
        suspend fun versionOf(binary: String): String? {
            for (flag in listOf("--version", "-V", "-v", "-version", "--help", "-h")) {
                try {
                    val result =
                        rawProbe("version", "${binary(binary)} $flag", 10_000L).last()
                    ToolVersion.parse(result.rawOutput)?.let { return it }
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

                // Register it so stopAllRunning() (OTA binary swap) has something to stop,
                // *before* the first result is emitted: a screen that presses Stop the moment
                // it sees a pid must find the entry, or the tool keeps running.
                childProcessManager.track(ChildProcess(id = pid, command = args, handler = tool))

                send(
                    ToolResult(
                        tool = label,
                        success = true,
                        status = STATUS_RUNNING,
                        pid = pid,
                        rawOutput = "Executing: $label $args",
                    ),
                )

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
                                                // This hint is about *rootless mode*: the daemon runs the
                                                // tool as root, so on a rooted device "disable rootless
                                                // mode" is advice about a mode that is already off — it was
                                                // appended for every permission denial, which read as a
                                                // misconfiguration the user could not fix.
                                                if (settings.rootlessMode) {
                                                    output.appendLine(
                                                        "[hint] Rootless mode is on — this feature needs root. " +
                                                            "Turn rootless mode off in Settings.",
                                                    )
                                                }
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
                    childProcessManager.forget(ChildKind.DAEMON, pid)
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
            client.startCommand(handler, args, null)
        } catch (e: Exception) {
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

