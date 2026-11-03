package org.indra.android.core.system.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app background: the scrim levels and the format guard.
 *
 * The bug being pinned: there was no app background at all, and the obvious wrong way to add one is
 * to call `WallpaperManager.setBitmap` — which changes the *device* wallpaper from a pentesting
 * app's settings screen. This is the app's own background instead, and these are the two parts of
 * it that can be wrong without a device: how heavy the scrim is, and whether a picked file is
 * really an image.
 */
class WallpaperTest {
    @Test
    fun `no wallpaper means nothing is painted`() {
        assertFalse(WallpaperState().active)
        assertTrue(WallpaperState(path = "/data/x.jpg").active)
    }

    @Test
    fun `the default scrim is heavy enough to read text over`() {
        // A wallpaper is an image the app did not choose. If the default let it through at full
        // brightness, every label would be unreadable until the user found this setting.
        assertEquals(WallpaperDim.STRONG, WallpaperDim.DEFAULT)
        assertTrue("the default must dim", WallpaperDim.DEFAULT.alpha >= 0.8f)
    }

    @Test
    fun `the levels are ordered and in range`() {
        val alphas = WallpaperDim.entries.map { it.alpha }
        assertEquals("levels must ascend", alphas.sorted(), alphas)
        alphas.forEach { assertTrue("alpha $it out of range", it in 0f..1f) }
        assertEquals("the first level leaves the image untouched", 0f, WallpaperDim.NONE.alpha)
    }

    @Test
    fun `an unknown stored level falls back to the default`() {
        // The preference is a string key, so a downgrade or a hand-edited prefs file can hold
        // anything. It must not crash the app on launch.
        assertEquals(WallpaperDim.DEFAULT, WallpaperDim.fromStored(null))
        assertEquals(WallpaperDim.DEFAULT, WallpaperDim.fromStored(""))
        assertEquals(WallpaperDim.DEFAULT, WallpaperDim.fromStored("NONSENSE"))
        assertEquals(WallpaperDim.LIGHT, WallpaperDim.fromStored("light"))
        assertEquals(WallpaperDim.NONE, WallpaperDim.fromStored("NONE"))
    }

    @Test
    fun `real image headers are accepted`() {
        assertTrue(WallpaperFormat.looksLikeImage(jpeg()))
        assertTrue(WallpaperFormat.looksLikeImage(png()))
        assertTrue(WallpaperFormat.looksLikeImage(gif()))
        assertTrue(WallpaperFormat.looksLikeImage(webp()))
        assertTrue(WallpaperFormat.looksLikeImage(heif()))
    }

    @Test
    fun `a file that is not an image is rejected`() {
        // The picker hands back a URI for whatever the user chose, and the content resolver will
        // open a PDF or a zip just as happily. Decoding that later throws inside the composition.
        assertFalse(WallpaperFormat.looksLikeImage(ByteArray(0)))
        assertFalse(WallpaperFormat.looksLikeImage(ByteArray(4)))
        assertFalse(WallpaperFormat.looksLikeImage("%PDF-1.7\n".toByteArray()))
        assertFalse(WallpaperFormat.looksLikeImage("PK\u0003\u0004zipdata!".toByteArray()))
        assertFalse(WallpaperFormat.looksLikeImage(ByteArray(WallpaperFormat.MIN_HEADER)))
    }

    @Test
    fun `a truncated header is rejected rather than guessed`() {
        // Fewer bytes than the longest signature: a WEBP check would read past the end.
        assertFalse(WallpaperFormat.looksLikeImage(jpeg().copyOfRange(0, 3)))
    }

    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun gif() = "GIF89a______".toByteArray()

    private fun webp() = "RIFF____WEBPVP8 ".toByteArray()

    private fun heif() = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63)
}
