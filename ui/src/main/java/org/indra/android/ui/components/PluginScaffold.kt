package org.indra.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope

/**
 * The one plugin screen frame: back arrow, title, optional actions, and the content slot.
 *
 * Every plugin screen used to declare its own `Scaffold` + `TopAppBar` + `IconButton` +
 * `Icons.AutoMirrored.Filled.ArrowBack` (36 copies, drifted in padding and title style).
 * New screens use this; `3.2` migrates the rest.
 *
 * @param actions trailing `TopAppBar` actions, e.g. a Clear button.
 * @param content receives the scaffold's padding values; it owns its own layout so a
 *   screen that needs a `LazyColumn`/`weight(1f)` body is not forced into a Column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}
