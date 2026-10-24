package org.indra.android.core.system

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.child.ProcessEvent
import org.indra.android.core.client.IndraClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartToolBox
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: IndraClient,
        private val settings: SettingsRepository,
    ) {
        companion object {
            /** Tools that never emit ChildEnd (long-running daemons) are bounded by this. */
            private const val TOOL_TIMEOUT_MS = 120_000L

            /** Packet capture / sniffers / scanners run until the user stops them. */
            private const val LONG_RUNNING_TIMEOUT_MS = 30 * 60_000L

            private const val WORDLIST_PATH = "wordlists/common-passwords.txt"

            /** Where tcpdump writes .cap files when the Packet Capture screen's
             *  save-to-file switch is on; WiFi Audit prefill's from here too. */
            const val CAPTURE_PATH = "captures/last.cap"

            /**
             * Single-quotes a value for the shell that executes raw-handler command
             * strings (POSIX `'"'"'` escaping, same rule as `CoreManager.shellQuote`).
             * Every user-typed field interpolated into a command must go through this.
             */
            fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
                    "nmap", "hydra", "ettercap", "tcpdump", "arpspoof",
                    "fusemounts", "network-radar", "msfrpcd", "raw", "blind",
                )

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
                    ToolSpec("masscan", "raw", "High-speed port scanner", "masscan"),
                    ToolSpec("hydra", "hydra", "Login cracker", "hydra"),
                    ToolSpec("aircrack-ng", "raw", "WiFi WEP/WPA cracker", "aircrack-ng"),
                    ToolSpec("reaver", "raw", "WPS PIN cracker", "reaver"),
                    ToolSpec("hping3", "raw", "Packet crafter", "hping3"),
                    ToolSpec("nuclei", "raw", "Vulnerability scanner", "nuclei"),
                    ToolSpec("bettercap", "raw", "Network attack framework", "bettercap"),
                    ToolSpec("arpspoof", "raw", "ARP spoofer", "arpspoof"),
                    ToolSpec("tcpdump", "raw", "Packet capture", "tcpdump"),
                    ToolSpec("ettercap", "ettercap", "MITM tool", "ettercap"),
                    ToolSpec("fusemounts", "fusemounts", "FUSE mounts", "fusemounts"),
                    ToolSpec("network-radar", "network-radar", "Network auto-scanner", "network-radar"),
                    ToolSpec("msfrpcd", "msfrpcd", "Metasploit RPC daemon", "msfrpcd"),
                    ToolSpec("shell", "raw", "Shell executor", "sh"),
                    ToolSpec("raw", "raw", "Raw socket", "raw"),
                    ToolSpec("blind", "blind", "Blind shell handler", "sh"),
                )
        }

        private val gson = Gson()
        private val tools = java.util.concurrent.ConcurrentHashMap<String, SmartTool>()

        init {
            TOOL_SPECS.forEach { spec ->
                tools[spec.name] = SmartTool(spec.name, spec.handler, spec.description, spec.binary, client, gson)
            }
        }

        fun getTool(name: String): SmartTool? = tools[name]

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
        ): Flow<ToolResult> {
            val scanType = if (settings.rootlessMode) "-sT" else "-sS"
            return execute("nmap", "$scanType -sV -v -p ${shellQuote(ports)} ${shellQuote(target)}")
        }

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
        fun traceroute(target: String): Flow<ToolResult> = rawCommand("traceroute", "${binary("nmap")} -sn --traceroute -v -n ${shellQuote(target)}")

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
        fun bettercap(args: String = ""): Flow<ToolResult> =
            rawCommand("bettercap", "${binary("bettercap")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)

        fun nuclei(
            target: String,
            templates: String = "",
        ): Flow<ToolResult> = rawCommand("nuclei", "${binary("nuclei")} -u ${shellQuote(target)} $templates")

        fun aircrack(args: String): Flow<ToolResult> =
            rawCommand("aircrack", "${binary("aircrack-ng")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)

        fun hping3(
            target: String,
            args: String = "",
        ): Flow<ToolResult> = rawCommand("hping3", "${binary("hping3")} $args ${shellQuote(target)}")

        fun masscan(
            target: String,
            args: String = "",
        ): Flow<ToolResult> = rawCommand("masscan", "${binary("masscan")} $args ${shellQuote(target)}")

        fun reaver(
            iface: String,
            bssid: String,
            args: String = "",
        ): Flow<ToolResult> = rawCommand("reaver", "${binary("reaver")} -i ${shellQuote(iface)} -b ${shellQuote(bssid)} $args")


        fun shell(command: String): Flow<ToolResult> = execute("shell", command)

        /** Path (relative to the core dir, the daemon's cwd) of a bundled tool binary. */
        private fun binary(name: String): String = "./tools/$name/$name"

        /** Bundled password list used when a screen does not supply one. */
        fun defaultWordlist(): String = File(context.filesDir, WORDLIST_PATH).absolutePath

        /** Absolute path of the capture file Packet Capture saves to (and WiFi
         *  Audit can be prefilled with). */
        fun capturePath(): String = File(context.filesDir, CAPTURE_PATH).absolutePath

        /** Kills a running child started by [execute]. */
        fun stop(pid: Int) {
            if (pid <= 0) return
            runCatching { client.kill(pid, 9) }
                .onFailure { Logger.w("kill($pid) failed: ${it.message}") }
        }

        private fun rawCommand(
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
        ): Flow<ToolResult> = execute("raw", command, label, timeoutMs)

        private fun execute(
            tool: String,
            args: String,
            label: String = tool,
            timeoutMs: Long = TOOL_TIMEOUT_MS,
        ): Flow<ToolResult> =
            channelFlow {
                val toolInstance = tools[tool]
                if (toolInstance == null) {
                    send(ToolResult(tool = label, success = false, error = "Tool '$label' not found", rawOutput = ""))
                    return@channelFlow
                }
                if (!toolInstance.isAvailable()) {
                    send(
                        ToolResult(
                            tool = label,
                            success = false,
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
                    send(ToolResult(tool = label, success = false, error = "Failed to start $label", rawOutput = ""))
                    return@channelFlow
                }

                send(
                    ToolResult(
                        tool = label,
                        success = true,
                        status = "running",
                        pid = pid,
                        rawOutput = "Executing: $label $args",
                    ),
                )

                val output = StringBuilder()
                val ports = mutableListOf<ToolPort>()
                val osMatches = mutableListOf<ToolOs>()

                var finished: Boolean? = null
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
                                                    status = "running",
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
                                                    status = "running",
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

                                    is ProcessEvent.Ended -> if (event.processId == pid) done = true

                                    is ProcessEvent.Crashed -> if (event.processId == pid) done = false
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

                send(
                    ToolResult(
                        tool = label,
                        success = finished == true,
                        status = if (finished == true) "completed" else "timeout",
                        pid = pid,
                        rawOutput = rawOutput,
                        ports = ports.toList(),
                        osMatches = osMatches.toList(),
                        error = if (finished == true) null else "$label timed out or stopped",
                    ),
                )
            }.flowOn(Dispatchers.IO)
    }

class SmartTool(
    val name: String,
    private val handler: String,
    val description: String,
    val binary: String,
    private val client: IndraClient,
    private val gson: Gson,
) {
    @Volatile
    private var available = false

    @Volatile
    private var refreshed = false

    fun isAvailable(): Boolean {
        if (!refreshed || !available) refresh()
        return available
    }

    fun refresh() {
        available =
            try {
                if (!IndraClient.isAvailable()) {
                    false
                } else {
                    client.getHandlers()?.contains(handler) ?: false
                }
            } catch (e: Throwable) {
                Logger.w("refresh '$handler' failed: ${e.message}")
                false
            }
        refreshed = true
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
            ProcessResult(pid = -1, error = "Failed to start $name")
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
    val status: String = "",
    val pid: Int = -1,
    val rawOutput: String = "",
    val ports: List<ToolPort> = emptyList(),
    val osMatches: List<ToolOs> = emptyList(),
    val parsedData: Map<String, Any>? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): String =
        com.google.gson
            .Gson()
            .toJson(this)

    companion object {
        fun fromJson(json: String): ToolResult? =
            try {
                com.google.gson
                    .Gson()
                    .fromJson(json, ToolResult::class.java)
            } catch (e: Exception) {
                null
            }
    }
}
