// Magic-byte signatures are data, not logic: every literal here is an offset and a byte value
// from a file-format spec, so the "magic number" rule has nothing useful to say about them.
@file:Suppress("MagicNumber")

package org.indra.android.core.system.wallpaper

/**
 * A user-chosen app background, and how much of it is allowed to show through.
 *
 * This is **not** the device wallpaper. Android has `WallpaperManager` for that, and an app that
 * sets the system wallpaper from its own settings screen is doing something the user did not ask
 * for and cannot easily undo. This is Indra's own background: an image the user picks in Settings,
 * painted behind the app's surfaces, stored in the app's private directory.
 *
 * The dim level exists because a wallpaper and a readable interface are in tension. Indra's theme
 * is dark and its text is light; a bright photo behind it makes every label unreadable. So the
 * image is always drawn under a scrim, and the user chooses how heavy that scrim is. [STRONG] is
 * the default: the image is a texture, not a picture.
 *
 * Pure by design (no Android, no IO) so the scrim maths and the format check are pinned by
 * `WallpaperTest`.
 */
enum class WallpaperDim(
    val label: String,
    /** Scrim alpha over the image: 0 = untouched, 1 = invisible. */
    val alpha: Float,
) {
    NONE("Off", 0f),
    LIGHT("Light", 0.45f),
    MEDIUM("Medium", 0.65f),
    STRONG("Strong", 0.82f),
    ;

    companion object {
        /**
         * The default scrim.
         *
         * [STRONG] rather than [MEDIUM]: with a wallpaper set, the app's own text has to stay
         * readable on top of an image the app did not choose. A user who wants more of the picture
         * can turn the scrim down, but the default must not produce an unreadable screen.
         */
        val DEFAULT: WallpaperDim = STRONG

        /** Parses a stored preference, falling back to [DEFAULT] for anything unknown. */
        fun fromStored(value: String?): WallpaperDim =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * What the app knows about the user's chosen background.
 *
 * [path] is `null` when no wallpaper is set, which is the only state in which the app paints its
 * plain theme background. A path that no longer exists is treated as "no wallpaper" by the store
 * rather than as an error: the file can be cleared by the system under storage pressure, and a
 * missing background must not stop the app from starting.
 */
data class WallpaperState(
    val path: String? = null,
    val dim: WallpaperDim = WallpaperDim.DEFAULT,
) {
    /** Whether anything should be painted behind the app. */
    val active: Boolean get() = path != null
}

/**
 * Guards the picked file before it is copied into the app's directory.
 *
 * The picker hands back a URI for whatever the user chose, and the content resolver will happily
 * open a file that is not an image at all (a PDF, a zip, a zero-byte placeholder left by a cloud
 * provider). Decoding that later throws inside the composition, so it is checked here, once, at the
 * point of copying.
 */
object WallpaperFormat {
    /**
     * Enough bytes for every signature below. WEBP is the longest: it needs index 11.
     */
    const val MIN_HEADER: Int = 12

    /**
     * One byte that must equal [value] at [offset] for the format to match.
     *
     * A table rather than a chain of comparisons: the signatures are constants from a file-format
     * spec, and expressing them as data keeps the check a lookup instead of a 26-branch `when`.
     */
    private data class ByteAt(
        val offset: Int,
        val value: Int,
    )

    /** Every format the platform can decode, as the bytes that identify it. */
    private val SIGNATURES: List<List<ByteAt>> =
        listOf(
            // JPEG: FF D8 FF
            listOf(ByteAt(0, 0xFF), ByteAt(1, 0xD8), ByteAt(2, 0xFF)),
            // PNG: 89 50 4E 47
            listOf(ByteAt(0, 0x89), ByteAt(1, 0x50), ByteAt(2, 0x4E), ByteAt(3, 0x47)),
            // GIF: "GIF8"
            listOf(ByteAt(0, 0x47), ByteAt(1, 0x49), ByteAt(2, 0x46), ByteAt(3, 0x38)),
            // WEBP: "RIFF" .... "WEBP"
            listOf(
                ByteAt(0, 0x52),
                ByteAt(1, 0x49),
                ByteAt(2, 0x46),
                ByteAt(3, 0x46),
                ByteAt(8, 0x57),
                ByteAt(9, 0x45),
                ByteAt(10, 0x42),
                ByteAt(11, 0x50),
            ),
            // HEIF/AVIF: ...."ftyp"
            listOf(ByteAt(4, 0x66), ByteAt(5, 0x74), ByteAt(6, 0x79), ByteAt(7, 0x70)),
        )

    /**
     * Whether [header] starts like an image the platform can decode.
     *
     * Magic bytes rather than a MIME string: the MIME type comes from the provider and is a claim,
     * not a fact. Only the first few bytes are needed, so the caller can read a small prefix
     * instead of the whole file.
     */
    fun looksLikeImage(header: ByteArray): Boolean {
        // A short header cannot be checked: a signature that reads past the end would either throw
        // or, worse, match on padding.
        if (header.size < MIN_HEADER) return false
        return SIGNATURES.any { signature -> signature.all { header.at(it.offset) == it.value } }
    }

    /** Unsigned byte, so 0xFF does not read as -1. */
    private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF
}
