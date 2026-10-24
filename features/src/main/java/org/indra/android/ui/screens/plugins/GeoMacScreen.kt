package org.indra.android.ui.screens.plugins

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoMacScreen(
    onNavigateBack: () -> Unit,
    viewModel: GeoMacViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GeoMac") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Map
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(4.0)
                        controller.setCenter(GeoPoint(0.0, 0.0))
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
            )

            // Input
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(uiState.bssid, {
                    viewModel.updateBssid(it)
                }, label = { Text("BSSID") }, modifier = Modifier.weight(2f), singleLine = true)
                OutlinedTextField(
                    uiState.lat,
                    { viewModel.updateLat(it) },
                    label = { Text("Lat") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    uiState.lon,
                    { viewModel.updateLon(it) },
                    label = { Text("Lon") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Button(onClick = { viewModel.add() }, modifier = Modifier.fillMaxWidth()) { Text("Add marker") }

            // List
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(uiState.entries) { e ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.bssid, style = MaterialTheme.typography.bodyMedium)
                                Text("${e.lat}, ${e.lon}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.remove(e) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }

            // Export
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.exportKml() }, modifier = Modifier.weight(1f)) { Text("Export KML") }
                Button(onClick = { viewModel.exportCsv() }, modifier = Modifier.weight(1f)) { Text("Export CSV") }
            }

            uiState.exportPath?.let { path ->
                val file = File(path)
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(intent, "Share export"))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share ${file.name}")
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
