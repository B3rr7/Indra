package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuckyScriptScreen(
    onNavigateBack: () -> Unit,
    viewModel: DuckyScriptViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DuckyScript") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            OutlinedTextField(
                value = uiState.source,
                onValueChange = { viewModel.updateSource(it) },
                label = { Text("DuckyScript source") },
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.parse() }, modifier = Modifier.weight(1f)) { Text("Parse") }
                Button(onClick = { viewModel.run() }, modifier = Modifier.weight(1f)) { Text(if (uiState.status == "Running…") "Running…" else "Run") }
            }
            Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            if (uiState.summary.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(uiState.summary, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}