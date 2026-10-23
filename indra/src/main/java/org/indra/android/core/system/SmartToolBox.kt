package org.indra.android.core.system

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
class SmartToolBox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: IndraClient
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
    }

    private val gson = Gson()
    private val tools = java.util.concurrent.ConcurrentHashMap<String, SmartTool>()

    init {
        registerTool("nmap", "nmap", "Network scanner", "nmap")
        registerTool("masscan", "masscan", "High-speed port scanner", "masscan")
        registerTool("hydra", "hydra", "Login cracker", "hydra")
        registerTool("aircrack", "aircrack", "WiFi WEP/WPA cracker", "aircrack-ng")
        registerTool("reaver", "reaver", "WPS PIN cracker", "reaver")
        registerTool("hping3", "hping3", "Packet crafter", "hping3")
        registerTool("nuclei", "nuclei", "Vulnerability scanner", "nuclei")
        registerTool("sqlmap", "sqlmap", "SQL injection tool", "sqlmap")
        registerTool("nikto", "nikto", "Web server scanner", "nikto")
        registerTool("wifite", "wifite", "Automated WiFi auditor", "wifite")
        registerTool("bettercap", "bettercap", "Network attack framework", "bettercap")
        registerTool("responder", "responder", "LLMNR/NBT-NS poisoner", "responder")
        registerTool("arpspoof", "arpspoof", "ARP spoofer", "arpspoof")
        registerTool("ettercap", "ettercap", "MITM tool", "ettercap")
        registerTool("iptables", "iptables", "Firewall rules", "iptables")
        registerTool("tcpdump", "tcpdump", "Packet capture", "tcpdump")
        registerTool("msf", "msf", "Metasploit console", "msfconsole")
        registerTool("msfrpcd", "msfrpcd", "Metasploit RPC daemon", "msfrpcd")
        registerTool("ruby", "ruby", "Ruby interpreter", "ruby")
        registerTool("shell", "shell", "Shell executor", "sh")
        registerTool("raw", "raw", "Raw socket", "raw")
        registerTool("ip", "ip", "IP utilities", "ip")
        registerTool("logcat", "logcat", "Log viewer", "logcat")
        registerTool("fusemounts", "fusemounts", "FUSE mounts", "fusemounts")
        registerTool("networkradar", "network-radar", "Network auto-scanner", "network-radar")
        registerTool("traceroute", "traceroute", "Network path tracer", "traceroute")
        registerTool("blind", "blind", "Blind shell handler", "sh")
    }

    private fun registerTool(name: String, handler: String, description: String, binary: String) {
        tools[name] = SmartTool(name, handler, description, binary, client, gson)
    }

    fun getTool(name: String): SmartTool? = tools[name]

    fun getAvailableTools(): List<SmartTool> {
        return tools.values.filter { it.isAvailable() }
    }

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
    fun nmap(target: String, ports: String = "1-1000"): Flow<ToolResult> =
        execute("nmap", "-sV -v -p $ports $target")

    /** XML output: the handler's XML parser is the only one reporting service versions. */
    fun nmapXml(target: String, ports: String = "1-1000"): Flow<ToolResult> =
        execute("nmap", "-sV -v -oX - -p $ports $target")

    fun hydra(
        target: String,
        service: String,
        user: String,
        passList: String = defaultWordlist()
    ): Flow<ToolResult> =
        rawCommand("hydra", "${binary("hydra")} -V -l $user -P $passList $target $service")

    /** Tests a single credential pair (`-p`) instead of a whole wordlist. */
    fun hydraCredential(
        target: String,
        service: String,
        user: String,
        password: String
    ): Flow<ToolResult> =
        rawCommand("hydra", "${binary("hydra")} -V -l $user -p $password $target $service")

    /** nmap's traceroute: its hop table is the only traceroute available on-device. */
    fun traceroute(target: String): Flow<ToolResult> =
        rawCommand("traceroute", "${binary("nmap")} -sn --traceroute -v -n $target")

    fun tcpdump(iface: String = "wlan0", filter: String = "", count: Int = 0, outFile: String? = null): Flow<ToolResult> =
        rawCommand(
            "tcpdump",
            buildString {
                append(binary("tcpdump")).append(" -i ").append(iface)
                if (outFile != null) {
                    // pcap file output; packet lines are suppressed by -w,
                    // tcpdump prints its own "packets captured" summary
                    append(" -w ").append(outFile)
                } else {
                    append(" -nn -l")
                }
                if (count > 0) append(" -c ").append(count)
                if (filter.isNotBlank()) append(' ').append(filter)
            },
            timeoutMs = LONG_RUNNING_TIMEOUT_MS
        )

    fun networkRadar(iface: String = "wlan0"): Flow<ToolResult> =
        rawCommand(
            "network-radar",
            "${binary("network-radar")} $iface",
            timeoutMs = LONG_RUNNING_TIMEOUT_MS
        )

    fun arpspoof(iface: String, target: String, gateway: String): Flow<ToolResult> =
        rawCommand(
            "arpspoof",
            "${binary("arpspoof")} -i $iface -t $target $gateway",
            timeoutMs = LONG_RUNNING_TIMEOUT_MS
        )

    fun ettercap(args: String): Flow<ToolResult> =
        rawCommand("ettercap", "${binary("ettercap")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)
    fun nuclei(target: String, templates: String = ""): Flow<ToolResult> = execute("nuclei", "-u $target $templates")
    fun sqlmap(url: String): Flow<ToolResult> = execute("sqlmap", "-u $url --batch")
    fun nikto(target: String): Flow<ToolResult> = execute("nikto", "-h $target")
    fun aircrack(args: String): Flow<ToolResult> =
        rawCommand("aircrack", "${binary("aircrack-ng")} $args", timeoutMs = LONG_RUNNING_TIMEOUT_MS)
    fun hping3(target: String, args: String = ""): Flow<ToolResult> =
        rawCommand("hping3", "${binary("hping3")} $args $target")
    fun bettercap(args: String = ""): Flow<ToolResult> = execute("bettercap", args)
    fun responder(iface: String = "wlan0"): Flow<ToolResult> = execute("responder", "-I $iface")
    fun shell(command: String): Flow<ToolResult> = execute("shell", command)
    fun ip(args: String): Flow<ToolResult> = execute("ip", args)

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
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): Flow<ToolResult> = execute("raw", command, label, timeoutMs)

    /**
     * Public wrapper running an arbitrary command through the daemon's raw handler.
     * Used by OTA version probes (`<binary> --version`) outside tool screens.
     */
    fun rawProbe(
        label: String,
        command: String,
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): Flow<ToolResult> = execute("raw", command, label, timeoutMs)

    private fun execute(
        tool: String,
        args: String,
        label: String = tool,
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): Flow<ToolResult> = channelFlow {
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
                    rawOutput = ""
                )
            )
            return@channelFlow
        }

        // Subscribe before starting the child so the first events are not lost.
        val events = Channel<ProcessEvent>(Channel.UNLIMITED)
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
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
                rawOutput = "Executing: $label $args"
            )
        )

        val output = StringBuilder()
        val ports = mutableListOf<ToolPort>()

        var finished: Boolean? = null
        try {
            finished = withTimeoutOrNull(timeoutMs) {
                var done: Boolean? = null
                while (done == null) {
                    when (val event = events.receive()) {
                        is ProcessEvent.Output -> if (event.processId == pid) {
                            output.appendLine(event.line)
                            send(
                                ToolResult(
                                    tool = label,
                                    success = true,
                                    status = "running",
                                    pid = pid,
                                    rawOutput = event.line,
                                    ports = ports.toList()
                                )
                            )
                        }

                        is ProcessEvent.Error -> if (event.processId == pid) {
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
                                    ports = ports.toList()
                                )
                            )
                        }

                        is ProcessEvent.PortFound -> if (event.processId == pid) {
                            ports += ToolPort(event.port, event.protocol, event.service, event.version)
                        }

                        is ProcessEvent.Message -> if (event.processId == pid) {
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

        val rawOutput = output.toString().trim().ifEmpty {
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
                error = if (finished == true) null else "$label timed out or stopped"
            )
        )
    }.flowOn(Dispatchers.IO)
}

class SmartTool(
    val name: String,
    private val handler: String,
    val description: String,
    val binary: String,
    private val client: IndraClient,
    private val gson: Gson
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
        available = try {
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
    fun start(args: String): Int {
        return try {
            client.startCommand(handler, args, null)
        } catch (e: Exception) {
            Logger.e("Failed to start $name", e)
            -1
        }
    }

    fun execute(args: String): ProcessResult {
        val pid = start(args)
        return if (pid == -1) {
            ProcessResult(pid = -1, error = "Failed to start $name")
        } else {
            ProcessResult(pid = pid, output = "Process started (PID: $pid)")
        }
    }

    fun toJson(): String {
        return gson.toJson(mapOf(
            "name" to name,
            "handler" to handler,
            "description" to description,
            "binary" to binary,
            "available" to available
        ))
    }
}

data class ToolPort(
    val port: Int,
    val protocol: String? = null,
    val service: String? = null,
    val version: String? = null
)

data class ToolResult(
    val tool: String,
    val success: Boolean,
    val status: String = "",
    val pid: Int = -1,
    val rawOutput: String = "",
    val ports: List<ToolPort> = emptyList(),
    val parsedData: Map<String, Any>? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return com.google.gson.Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): ToolResult? {
            return try {
                com.google.gson.Gson().fromJson(json, ToolResult::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
