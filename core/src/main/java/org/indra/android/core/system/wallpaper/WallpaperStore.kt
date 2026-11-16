package org.indra.android.core.system.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        /**
         * The current background, as Compose state.
         *
         * The store owns this rather than the activity that paints it, because the picker, the
         * Remove button and the dim chips are three different writers, and none of them is the
         * window: the activity used to read the background in `onResume`, which runs *before* the
         * photo picker hands its result back — so a pick appeared to do nothing until the app was
         * backgrounded and resumed. Every write here publishes, and the window just collects.
         *
         * It starts at "no background": reading the real one is a preference read plus a file
         * stat, and neither belongs on Main. `snapshot()` corrects it on an IO thread.
         */
        private val _state = MutableStateFlow(WallpaperState())
        val state: StateFlow<WallpaperState> = _state.asStateFlow()

        /**
         * Reads the current wallpaper off disk and publishes it to [state].
         *
         * IO by contract: callers run this on `Dispatchers.IO`. Every write path returns through
         * here, so there is exactly one place that decides what the UI should draw.
         */
        fun snapshot(): WallpaperState {
            val dim = WallpaperDim.fromStored(settings.wallpaperDim)
            // A path is only reported when the file is really there: the system can clear
            // app-private files under storage pressure, and a stale path would make the UI try to
            // decode a file that is gone.
            val present = file.exists() && file.length() > 0
            return WallpaperState(path = file.absolutePath.takeIf { present }, dim = dim)
                .also { _state.value = it }
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
                    // The picked stream is opened **once** and copied to a local file; everything
                    // after that reads the file.
                    //
                    // This is not tidiness. A picker grant is not guaranteed to survive a second
                    // `openInputStream` — the media provider can hand out a single descriptor and
                    // refuse the next — and this used to open the stream three times (header,
                    // bounds, decode). The result was a pick that had arrived perfectly well
                    // failing on the second open with "Could not open that file.", an error about
                    // the provider that read as an error about the image. One open cannot have
                    // that failure mode, and decoding a local file is faster than a second trip
                    // through the content resolver anyway.
                    val source = File(context.filesDir, "$FILE_NAME.source")
                    try {
                        val input =
                            context.contentResolver.openInputStream(uri)
                                ?: error(
                                    "Could not open that file. It may have been moved or deleted " +
                                        "since it was picked.",
                                )
                        input.use { stream -> source.outputStream().use { out -> stream.copyTo(out) } }

                        val header = readHeader(source)
                        if (!WallpaperFormat.looksLikeImage(header)) {
                            error("That file is not an image Indra can use. Pick a JPEG, PNG, WEBP or HEIF photo.")
                        }
                        // Decode bounds first, so a huge photo is downscaled before it is allocated.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(source.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            error("That image could not be read — it may be corrupt.")
                        }

                        val options =
                            BitmapFactory.Options().apply {
                                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                            }
                        val bitmap =
                            BitmapFactory.decodeFile(source.absolutePath, options)
                                ?: error("Could not decode that image.")

                        // Write to a temp file and rename, so a failure halfway through cannot
                        // leave a half-written image where the UI expects a valid one.
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
                    } finally {
                        // A picked photo can be tens of megabytes, so the copy goes even when the
                        // decode above threw.
                        source.delete()
                    }
                    Logger.i("App wallpaper set (${file.length() / BYTES_PER_KB} KB)")
                    snapshot()
                }.onFailure { Logger.w("Could not set the app wallpaper: ${it.message}") }
            }

        /** Removes the background. The app falls back to its plain theme colour. */
        suspend fun clear(): WallpaperState =
            withContext(Dispatchers.IO) {
                // Logged because this is the one write that leaves no trace otherwise: a
                // background that is simply gone is indistinguishable from one that was never
                // set, which made an accidental Remove look like the file vanishing on its own.
                if (file.delete()) Logger.i("App wallpaper removed") else Logger.i("No app wallpaper to remove")
                snapshot()
            }

        /** Changes how heavily the image is dimmed, without touching the image. */
        suspend fun setDim(dim: WallpaperDim): WallpaperState =
            withContext(Dispatchers.IO) {
                settings.wallpaperDim = dim.name
                snapshot()
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

        /** The first [WallpaperFormat.MIN_HEADER] bytes of [file], for the format check. */
        private fun readHeader(file: File): ByteArray {
            val buffer = ByteArray(WallpaperFormat.MIN_HEADER)
            val read =
                file.inputStream().use { input ->
                    var total = 0
                    while (total < buffer.size) {
                        val n = input.read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
            return if (read == buffer.size) buffer else ByteArray(0)
        }
    }
