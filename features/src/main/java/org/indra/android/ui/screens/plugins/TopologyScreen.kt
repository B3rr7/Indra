package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopologyScreen(
    onNavigateBack: () -> Unit,
    viewModel: TopologyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Topology") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Visual map of hosts and connections. Add nodes via the Network Radar / Port Scanner results.", style = MaterialTheme.typography.bodySmall)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val nodeMap = uiState.nodes.associateBy { it.id }
                // Draw edges
                uiState.edges.forEach { e ->
                    val a = nodeMap[e.from]
                    val b = nodeMap[e.to]
                    if (a != null && b != null) {
                        drawLine(
                            color = Color.Gray,
                            start = Offset(a.x, a.y),
                            end = Offset(b.x, b.y),
                            strokeWidth = 2f
                        )
                    }
                }
                // Draw nodes
                uiState.nodes.forEach { n ->
                    drawCircle(color = Color(0xFF448AFF), radius = 20f, center = Offset(n.x, n.y))
                    val measured = textMeasurer.measure(n.label)
                    drawText(measured, topLeft = Offset(n.x - measured.size.width / 2f, n.y + 24f))
                }
            }
        }
    }
}