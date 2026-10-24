package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import java.io.File
import javax.inject.Inject

data class PacketForgerUiState(
    val target: String = "",
    val protocol: String = "tcp",
    val port: String = "80",
    val payload: String = "",
    val isLoading: Boolean = false,
    val result: String = "",
    val error: String? = null,
)

@HiltViewModel
class PacketForgerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PacketForgerUiState())
        val uiState: StateFlow<PacketForgerUiState> = _uiState.asStateFlow()

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun setProtocol(protocol: String) {
            _uiState.value = _uiState.value.copy(protocol = protocol)
        }

        fun updatePort(port: String) {
            _uiState.value = _uiState.value.copy(port = port)
        }

        fun updatePayload(payload: String) {
            _uiState.value = _uiState.value.copy(payload = payload)
        }

        /** Even-length hex string -> raw bytes; anything else -> UTF-8 bytes. */
        private fun payloadBytes(text: String): ByteArray {
            val t = text.trim()
            return if (t.isNotEmpty() && t.length % 2 == 0 && t.matches(Regex("^[0-9a-fA-F]+$"))) {
                ByteArray(t.length / 2) { i ->
                    ((Character.digit(t[2 * i], 16) shl 4) + Character.digit(t[2 * i + 1], 16)).toByte()
                }
            } else {
                t.toByteArray(Charsets.UTF_8)
            }
        }

        fun send() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, result = "")

                try {
                    val raw = toolBox.getTool("raw")
                    if (raw == null || !raw.isAvailable()) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = "Native core is not running (raw handler unavailable)",
                            )
                        return@launch
                    }

                    val state = _uiState.value
                    val args = StringBuilder()

                    // Mode flags: tcp (default, SYN), udp (-2), icmp echo (-1).
                    // -c 2 bounds the run: hping3 never exits on its own otherwise.
                    when (state.protocol) {
                        "icmp" -> args.append("-1")
                        "udp" -> {
                            args.append("-2")
                            if (state.port.isNotBlank()) args.append(" -p ").append(state.port)
                        }
                        else -> {
                            args.append("-S")
                            if (state.port.isNotBlank()) args.append(" -p ").append(state.port)
                        }
                    }
                    args.append(" -c 2")

                    val bytes = payloadBytes(state.payload)
                    var payloadFile: File? = null
                    if (bytes.isNotEmpty()) {
                        payloadFile =
                            File(context.cacheDir, "hping-payload.bin").also {
                                it.writeBytes(bytes)
                            }
                        args
                            .append(" -d ")
                            .append(bytes.size)
                            .append(" -E ")
                            .append(payloadFile.absolutePath)
                    }

                    // hping3() appends the target itself.
                    try {
                        toolBox.hping3(state.target, args.toString()).collect { result ->
                            if (result.success) {
                                _uiState.value =
                                    _uiState.value.copy(
                                        isLoading = false,
                                        result = result.rawOutput,
                                    )
                            } else {
                                _uiState.value =
                                    _uiState.value.copy(
                                        isLoading = false,
                                        error = result.error,
                                    )
                            }
                        }
                    } finally {
                        payloadFile?.delete() ?: true
                    }
                } catch (e: Exception) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = e.message,
                        )
                }
            }
        }
    }
