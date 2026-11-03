package org.indra.android.core.system.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.indra.android.core.system.Logger
import org.indra.android.core.system.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's app background and hands it back to the UI.
 *
 * The picked image is **copied into the app's private directory** rather than referenced by URI.
 * A `content://` URI from the photo picker is a grant that can be revoked (the user clears the
 * provider's cache, the app is reinstalled, the grant expires after a reboot on some providers),
 * and a background that silently disappears is worse than one that was never set. The copy is also
 * downscaled: a 12 MP photo decoded at full size is ~48 MB of bitmap, which is an OOM on a phone
 * that is already running a daemon and a terminal.
 *
 * The file is a single fixed name, so setting a new wallpaper replaces the old one instead of
 * accumulating copies.
 */
@Singleton
class WallpaperStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
    ) {
        companion object {
            /** Fixed name: one wallpaper at a time, and a new pick overwrites it. */
            private const val FILE_NAME = "app-wallpaper.img"

            /**
             * Longest edge the stored image is allowed to keep.
             *
             * A phone screen is at most ~1440 px wide, and the image is drawn behind a scrim, so
             * detail past this is invisible. 2048 leaves room for a tall screen in portrait
             * without keeping a full-resolution photo in memory.
             */
            private const val MAX_EDGE = 2048

            /** JPEG quality for the re-encode. The scrim hides compression artefacts. */
            private const val QUALITY = 88

            /** Bytes in a kilobyte, for the size in the log line. */
            private const val BYTES_PER_KB = 1024
        }

        private val file: File get() = File(context.filesDir, FILE_NAME)

        /** The current wallpaper, as the UI should draw it. */
        fun state(): WallpaperState {
            val dim = WallpaperDim.fromStored(settings.wallpaperDim)
            // A path is only reported when the file is really there: the system can clear
            // app-private files under storage pressure, and a stale path would make the UI try to
            // decode a file that is gone.
            val present = file.exists() && file.length() > 0
            return WallpaperState(path = file.absolutePath.takeIf { present }, dim = dim)
        }

        /**
         * Copies [uri] into the app directory and makes it the background.
         *
         * Returns the new state, or a failure whose message is safe to show the user. Every failure
         * path leaves the previous wallpaper in place: a pick that cannot be decoded must not
         * destroy the background the user already had.
         */
        suspend fun set(uri: Uri): Result<WallpaperState> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val header = readHeader(uri)
                    if (!WallpaperFormat.looksLikeImage(header)) {
                        error("That file is not an image Indra can use. Pick a JPEG, PNG, WEBP or HEIF photo.")
                    }
                    // Decode bounds first, so a huge photo is downscaled before it is allocated.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    } ?: error("Could not open that file.")
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        error("That image could not be read — it may be corrupt.")
                    }

                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                        }
                    val bitmap =
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        } ?: error("Could not decode that image.")

                    // Write to a temp file and rename, so a failure halfway through cannot leave a
                    // half-written image where the UI expects a valid one.
                    val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                    try {
                        tmp.outputStream().use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                                error("Could not save that image.")
                            }
                        }
                        if (!tmp.renameTo(file)) {
                            // renameTo fails across some filesystems; fall back to a copy.
                            tmp.copyTo(file, overwrite = true)
                            tmp.delete()
                        }
                    } finally {
                        bitmap.recycle()
                        if (tmp.exists()) tmp.delete()
                    }
                    Logger.i("App wallpaper set (${file.length() / BYTES_PER_KB} KB)")
                    state()
                }.onFailure { Logger.w("Could not set the app wallpaper: ${it.message}") }
            }

        /** Removes the background. The app falls back to its plain theme colour. */
        suspend fun clear(): WallpaperState =
            withContext(Dispatchers.IO) {
                file.delete()
                state()
            }

        /** Changes how heavily the image is dimmed, without touching the image. */
        fun setDim(dim: WallpaperDim): WallpaperState {
            settings.wallpaperDim = dim.name
            return state()
        }

        /**
         * The largest power-of-two sample size that still leaves the image above [MAX_EDGE].
         *
         * `inSampleSize` is a divisor, so 2 halves each dimension (a quarter of the pixels). The
         * loop stops as soon as the result would be smaller than the cap, which keeps the stored
         * image as detailed as the cap allows.
         */
        private fun sampleSizeFor(
            width: Int,
            height: Int,
        ): Int {
            var sample = 1
            var longest = maxOf(width, height)
            while (longest / 2 >= MAX_EDGE) {
                longest /= 2
                sample *= 2
            }
            return sample
        }

        /** The first [WallpaperFormat.MIN_HEADER] bytes, for the format check. */
        private fun readHeader(uri: Uri): ByteArray {
            val buffer = ByteArray(WallpaperFormat.MIN_HEADER)
            val read =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    var total = 0
                    while (total < buffer.size) {
                        val n = input.read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                } ?: 0
            return if (read == buffer.size) buffer else ByteArray(0)
        }
    }
