package org.indra.android.core.system

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import org.indra.android.core.client.IndraClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartToolBox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: IndraClient
) {
    private val gson = Gson()
    private val tools = java.util.concurrent.ConcurrentHashMap<String, SmartTool>()

    init {
        registerTool("nmap", "NMap", "Network scanner", "nmap")
        registerTool("masscan", "Masscan", "High-speed port scanner", "masscan")
        registerTool("hydra", "Hydra", "Login cracker", "hydra")
        registerTool("aircrack", "Aircrack", "WiFi WEP/WPA cracker", "aircrack-ng")
        registerTool("reaver", "Reaver", "WPS PIN cracker", "reaver")
        registerTool("hping3", "Hping3", "Packet crafter", "hping3")
        registerTool("nuclei", "Nuclei", "Vulnerability scanner", "nuclei")
        registerTool("sqlmap", "Sqlmap", "SQL injection tool", "sqlmap")
        registerTool("nikto", "Nikto", "Web server scanner", "nikto")
        registerTool("wifite", "Wifite", "Automated WiFi auditor", "wifite")
        registerTool("bettercap", "Bettercap", "Network attack framework", "bettercap")
        registerTool("responder", "Responder", "LLMNR/NBT-NS poisoner", "responder")
        registerTool("arpspoof", "ArpSpoof", "ARP spoofer", "arpspoof")
        registerTool("ettercap", "Ettercap", "MITM tool", "ettercap")
        registerTool("iptables", "IPTables", "Firewall rules", "iptables")
        registerTool("tcpdump", "TcpDump", "Packet capture", "tcpdump")
        registerTool("msf", "Msf", "Metasploit console", "msfconsole")
        registerTool("msfrpcd", "MsfRpcd", "Metasploit RPC daemon", "msfrpcd")
        registerTool("ruby", "Ruby", "Ruby interpreter", "ruby")
        registerTool("shell", "Shell", "Shell executor", "sh")
        registerTool("raw", "Raw", "Raw socket", "raw")
        registerTool("ip", "Ip", "IP utilities", "ip")
        registerTool("logcat", "Logcat", "Log viewer", "logcat")
        registerTool("fusemounts", "Fusemounts", "FUSE mounts", "fusemounts")
        registerTool("networkradar", "NetworkRadar", "Network auto-scanner", "networkradar")
        registerTool("traceroute", "Traceroute", "Network path tracer", "traceroute")
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

    // Convenience methods returning JSON results
    fun nmap(target: String, ports: String = "1-1000"): Flow<ToolResult> = execute("nmap", "-sV -p $ports $target")
    fun masscan(target: String, ports: String = "1-65535"): Flow<ToolResult> = execute("masscan", "-p $ports $target --rate=1000")
    fun hydra(target: String, service: String, user: String, passList: String = "/usr/share/wordlists/rockyou.txt"): Flow<ToolResult> = execute("hydra", "-l $user -P $passList $target $service")
    fun traceroute(target: String): Flow<ToolResult> = execute("traceroute", target)
    fun tcpdump(iface: String = "wlan0", filter: String = ""): Flow<ToolResult> = execute("tcpdump", "-i $iface $filter")
    fun nuclei(target: String, templates: String = ""): Flow<ToolResult> = execute("nuclei", "-u $target $templates")
    fun sqlmap(url: String): Flow<ToolResult> = execute("sqlmap", "-u $url --batch")
    fun nikto(target: String): Flow<ToolResult> = execute("nikto", "-h $target")
    fun aircrack(capture: String): Flow<ToolResult> = execute("aircrack", capture)
    fun hping3(target: String, args: String = ""): Flow<ToolResult> = execute("hping3", "$args $target")
    fun bettercap(args: String = ""): Flow<ToolResult> = execute("bettercap", args)
    fun responder(iface: String = "wlan0"): Flow<ToolResult> = execute("responder", "-I $iface")
    fun shell(command: String): Flow<ToolResult> = execute("shell", command)
    fun ip(args: String): Flow<ToolResult> = execute("ip", args)

    private fun execute(tool: String, args: String): Flow<ToolResult> = flow {
        val toolInstance = tools[tool]
        if (toolInstance == null) {
            emit(ToolResult(tool = tool, success = false, error = "Tool '$tool' not found", rawOutput = ""))
            return@flow
        }
        if (!toolInstance.isAvailable()) {
            emit(ToolResult(tool = tool, success = false, error = "Tool '$tool' is not available (native handler missing)", rawOutput = ""))
            return@flow
        }

        emit(ToolResult(tool = tool, success = true, status = "running", rawOutput = "Executing: $tool $args"))

        val result = toolInstance.execute(args)
        if (result.pid == -1) {
            emit(ToolResult(tool = tool, success = false, error = result.error, rawOutput = ""))
        } else {
            emit(ToolResult(
                tool = tool,
                success = true,
                status = "completed",
                pid = result.pid,
                rawOutput = result.output.ifBlank { "Process started (PID: ${result.pid})" }
            ))
        }
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
    private var available = false

    fun isAvailable(): Boolean = available

    fun refresh() {
        available = try {
            if (!IndraClient.isAvailable()) {
                false
            } else {
                val handlers = client.getHandlers()
                handlers?.contains(handler) ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun execute(args: String): ProcessResult {
        return try {
            val pid = client.startCommand(handler, args, null)
            if (pid == -1) {
                ProcessResult(pid = -1, error = "Failed to start $name")
            } else {
                ProcessResult(pid = pid, output = "")
            }
        } catch (e: Exception) {
            ProcessResult(pid = -1, error = e.message ?: "Unknown error")
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

data class ToolResult(
    val tool: String,
    val success: Boolean,
    val status: String = "",
    val pid: Int = -1,
    val rawOutput: String = "",
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
