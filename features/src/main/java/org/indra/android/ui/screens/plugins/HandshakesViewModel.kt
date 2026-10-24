package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class HandshakeFile(
    val name: String,
    val path: String,
    val size: Long,
)

data class HandshakesUiState(
    val files: List<HandshakeFile> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class HandshakesViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HandshakesUiState())
        val uiState: StateFlow<HandshakesUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                val dir = File(context.filesDir, "captures")
                val list =
                    dir
                        .listFiles()
                        ?.filter { it.isFile && (it.name.endsWith(".cap") || it.name.endsWith(".pcap")) }
                        ?.map { HandshakeFile(it.name, it.absolutePath, it.length()) } ?: emptyList()
                _uiState.value = HandshakesUiState(files = list.sortedByDescending { it.size })
            }
        }

        fun delete(path: String) {
            viewModelScope.launch {
                File(path).delete()
                refresh()
            }
        }

        fun rename(
            oldPath: String,
            newName: String,
        ) {
            viewModelScope.launch {
                val f = File(oldPath)
                val target = File(f.parentFile, newName)
                if (f.renameTo(target)) {
                    refresh()
                } else {
                    _uiState.value = _uiState.value.copy(error = "Rename failed")
                }
            }
        }
    }
