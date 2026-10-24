package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class TopologyNode(val id: String, val label: String, val x: Float, val y: Float)
data class TopologyEdge(val from: String, val to: String)

data class TopologyUiState(
    val nodes: List<TopologyNode> = emptyList(),
    val edges: List<TopologyEdge> = emptyList()
)

@HiltViewModel
class TopologyViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(TopologyUiState())
    val uiState: StateFlow<TopologyUiState> = _uiState.asStateFlow()

    fun setGraph(nodes: List<TopologyNode>, edges: List<TopologyEdge>) {
        _uiState.value = TopologyUiState(nodes, edges)
    }

    fun addNode(id: String, label: String, x: Float, y: Float) {
        _uiState.value = _uiState.value.copy(nodes = _uiState.value.nodes + TopologyNode(id, label, x, y))
    }

    fun addEdge(from: String, to: String) {
        _uiState.value = _uiState.value.copy(edges = _uiState.value.edges + TopologyEdge(from, to))
    }

    fun clear() { _uiState.value = TopologyUiState() }
}