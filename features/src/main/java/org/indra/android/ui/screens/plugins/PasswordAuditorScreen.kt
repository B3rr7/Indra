package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordAuditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: PasswordAuditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password Auditor") },
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
                value = uiState.password,
                onValueChange = { viewModel.updatePassword(it) },
                label = { Text("Password to audit") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = { viewModel.audit() }, modifier = Modifier.fillMaxWidth()) {
                Text("Audit")
            }
            uiState.result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Verdict: ${r.verdict}", style = MaterialTheme.typography.titleMedium)
                        Text("Length: ${r.length}")
                        Text("Entropy: ${"%.1f".format(r.entropyBits)} bits")
                        Text("Uppercase: ${r.hasUpper}  Lowercase: ${r.hasLower}  Digit: ${r.hasDigit}  Symbol: ${r.hasSymbol}")
                        Text("In common-password dictionary: ${r.inDictionary}")
                        Text("Score: ${r.score}/4")
                    }
                }
            }
        }
    }
}