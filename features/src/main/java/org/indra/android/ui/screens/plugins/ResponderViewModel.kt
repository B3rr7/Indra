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

data class ResponderUiState(
    val isRunning: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ResponderViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ResponderUiState())
        val uiState: StateFlow<ResponderUiState> = _uiState.asStateFlow()

        private var job: Job? = null

        fun start() {
            if (job?.isActive == true) return
            _uiState.value = _uiState.value.copy(isRunning = true, error = null, log = emptyList())
            job =
                viewModelScope.launch(Dispatchers.IO) {
                    var socket: DatagramSocket? = null
                    try {
                        socket = DatagramSocket(5355, InetAddress.getByName("0.0.0.0"))
                        socket.broadcast = true
                        val buf = ByteArray(512)
                        val ip = getLocalIp()
                        while (isActive) {
                            val pkt = DatagramPacket(buf, buf.size)
                            try {
                                socket.receive(pkt)
                            } catch (e: Exception) {
                                if (!isActive) break
                                continue
                            }
                            val data = pkt.data.copyOf(pkt.length)
                            val qname = parseLlmnrQuestion(data)
                            if (qname != null) {
                                withContext(Dispatchers.Main) {
                                    _uiState.value =
                                        _uiState.value.copy(log = _uiState.value.log + "Query: $qname from ${pkt.address.hostAddress}")
                                }
                                // Build response
                                val resp = buildLlmnrResponse(data, ip)
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
            job?.cancel()
            job = null
            _uiState.value = _uiState.value.copy(isRunning = false)
        }

        private fun getLocalIp(): ByteArray {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            return byteArrayOf(
                (ipInt and 0xff).toByte(),
                ((ipInt shr 8) and 0xff).toByte(),
                ((ipInt shr 16) and 0xff).toByte(),
                ((ipInt shr 24) and 0xff).toByte(),
            )
        }

        private fun parseLlmnrQuestion(data: ByteArray): String? {
            if (data.size < 12) return null
            val qdcount = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
            if (qdcount < 1) return null
            var idx = 12
            val labels = mutableListOf<String>()
            while (idx < data.size) {
                val len = data[idx].toInt() and 0xff
                if (len == 0) {
                    idx++
                    break
                }
                if (len > 63 || idx + 1 + len > data.size) return null
                labels.add(String(data, idx + 1, len))
                idx += 1 + len
            }
            // skip QTYPE (2) + QCLASS (2)
            return labels.joinToString(".")
        }

        private fun buildLlmnrResponse(
            query: ByteArray,
            ip: ByteArray,
        ): ByteArray? {
            if (query.size < 12) return null
            val id = query.copyOfRange(0, 2)
            // Flags: response (0x8000) | authoritative (0x0400)
            val flags = byteArrayOf(0x80.toByte(), 0x00.toByte())
            // QDCOUNT = 1, ANCOUNT = 1
            val counts = byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
            // Question section: copy from query (QNAME + QTYPE + QCLASS)
            // Find end of question
            var idx = 12
            while (idx < query.size && query[idx].toInt() != 0) {
                val len = query[idx].toInt() and 0xff
                if (len > 63) return null
                idx += 1 + len
            }
            idx++ // null terminator
            idx += 4 // QTYPE + QCLASS
            if (idx > query.size) return null
            val question = query.copyOfRange(12, idx)

            // Answer: pointer to question name (0xC00C), type A (1), class IN (1), TTL, rdlength 4, rdata IP
            val answer = ByteArray(16)
            answer[0] = 0xC0.toByte()
            answer[1] = 0x0C.toByte() // pointer
            answer[2] = 0x00
            answer[3] = 0x01 // type A
            answer[4] = 0x00
            answer[5] = 0x01 // class IN
            // TTL = 30
            answer[6] = 0x00
            answer[7] = 0x00
            answer[8] = 0x00
            answer[9] = 0x1E
            answer[10] = 0x00
            answer[11] = 0x04 // rdlength
            answer[12] = ip[0]
            answer[13] = ip[1]
            answer[14] = ip[2]
            answer[15] = ip[3]

            val resp = ByteArray(12 + question.size + answer.size)
            System.arraycopy(id, 0, resp, 0, 2)
            System.arraycopy(flags, 0, resp, 2, 2)
            System.arraycopy(counts, 0, resp, 4, 8)
            System.arraycopy(question, 0, resp, 12, question.size)
            System.arraycopy(answer, 0, resp, 12 + question.size, answer.size)
            return resp
        }
    }
