package org.indra.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.indra.android.core.system.wallpaper.WallpaperDim
import org.indra.android.core.system.wallpaper.WallpaperState

/**
 * Paints the user's app background behind [content].
 *
 * The image is drawn with [ContentScale.Crop] so it fills any screen shape without letterboxing,
 * and under a scrim whose weight comes from [WallpaperState.dim]. The scrim is not decoration: the
 * app's text is light and its surfaces are translucent, so without it a bright photo makes every
 * label unreadable. See `WallpaperDim` for why the default is the heavy one.
 *
 * When no wallpaper is set this is just a themed background, so callers can wrap unconditionally
 * instead of branching on whether a wallpaper exists.
 */
@Composable
fun WallpaperBackground(
    state: WallpaperState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bitmap = rememberWallpaperBitmap(state.path)
    Box(modifier = modifier.fillMaxSize()) {
        // The theme colour is always painted first: it is what shows while the image decodes, and
        // what remains if the file turns out to be unreadable.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        )
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // The scrim. Drawn in the theme's own background colour rather than black, so the
            // image tints toward the app's palette instead of going grey.
            if (state.dim != WallpaperDim.NONE) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.background.copy(alpha = state.dim.alpha),
                            ),
                )
            }
        }
        content()
    }
}

/**
 * Decodes the wallpaper off the main thread, re-decoding only when [path] changes.
 *
 * Decoding on the composition thread would drop frames on every recomposition; keying the effect
 * on [path] means the image is read once per selection, not once per frame.
 */
@Composable
private fun rememberWallpaperBitmap(path: String?): ImageBitmap? {

    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap =
            if (path == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory
                            .decodeFile(path)
                            ?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    return bitmap
}
