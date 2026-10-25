package org.indra.android.core.system

/**
 * Boot-keyboard HID reports for the kernel's USB gadget interface (`/dev/hidg0`).
 *
 * Two things made the previous "type this text" path a no-op:
 *
 * - `printf '%s' 'text' > /dev/hidg0` writes ASCII. The gadget expects fixed 8-byte
 *   reports (`[modifier, reserved, key1..key6]`), so a host sees random keystrokes,
 *   not the text.
 * - `printf '\xHH'` is not portable to Android's toybox/mksh `printf`; `\0NNN`
 *   octal is the form both understand.
 *
 * This object is pure so the encoding can be unit-tested without a device. Whether a
 * gadget is actually bound to a UDC is a separate question — the caller still has to
 * deal with `/dev/hidg0` not existing ([DEVICE_MISSING_MESSAGE]).
 */
object HidReports {
    /** The gadget's keyboard endpoint. Only exists once a `hid.usbN` function is bound. */
    const val DEVICE = "/dev/hidg0"

    const val DEVICE_MISSING_MESSAGE = "hidg0 not available: no HID function bound to a UDC"

    /** Report length of the boot keyboard descriptor. */
    const val REPORT_SIZE = 8

    /** Left shift modifier bit. */
    const val MOD_SHIFT = 0x02

    /** Mask that turns a signed [Byte] into its unsigned value. */
    private const val BYTE_MASK = 0xff

    /** `printf`'s `\0NNN` escape: base 8, exactly three digits. */
    private const val OCTAL_RADIX = 8
    private const val OCTAL_DIGITS = 3

    fun pressReport(
        modifier: Int,
        keyCode: Int,
    ): ByteArray = byteArrayOf(modifier.toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0)

    fun releaseReport(): ByteArray = ByteArray(REPORT_SIZE)

    /**
     * (modifier, keycode) pairs for each character of [text], skipping anything the boot
     * keyboard cannot produce (non-ASCII, control characters other than \n, \t, \b).
     */
    fun keystrokes(text: String): List<Pair<Int, Int>> =
        text.mapNotNull { char -> usageOf(char)?.let { (mod, code) -> mod to code } }

    /**
     * A shell script that types [text] into [device]: one press report and one release
     * report per character.
     */
    fun typingScript(
        text: String,
        device: String = DEVICE,
    ): String =
        keystrokes(text).joinToString("; ") { (mod, code) ->
            writeCommand(pressReport(mod, code), device) + "; " + writeCommand(releaseReport(), device)
        }

    /** A shell `printf` that writes exactly [bytes] to [device] (octal escapes). */
    fun writeCommand(
        bytes: ByteArray,
        device: String = DEVICE,
    ): String {
        val octal =
            bytes.joinToString("") { byte ->
                val v = byte.toInt() and BYTE_MASK
                "\\0" + v.toString(OCTAL_RADIX).padStart(OCTAL_DIGITS, '0')
            }
        return "printf '$octal' > $device"
    }

    /**
     * `(modifier, keycode)` for one ASCII character, or null if the boot keyboard cannot
     * produce it.
     *
     * The literals are HID usage IDs from the HID Usage Tables, page 0x07. Giving each of
     * the ~60 keys a named constant would hide the correspondence to the spec, so the
     * table-shape rules are suppressed deliberately rather than worked around.
     */
    @Suppress("MagicNumber", "CyclomaticComplexMethod", "ReturnCount")
    private fun usageOf(char: Char): Pair<Int, Int>? {
        val lower = char.lowercaseChar()
        if (lower in 'a'..'z') {
            val code = 0x04 + (lower - 'a')
            return (if (char.isUpperCase()) MOD_SHIFT else 0) to code
        }
        if (char in '1'..'9') return 0 to (0x1E + (char - '1'))
        if (char == '0') return 0 to 0x27
        return when (char) {
            '\n' -> 0 to 0x28
            '\b' -> 0 to 0x2A
            '\t' -> 0 to 0x2B
            ' ' -> 0 to 0x2C
            '-' -> 0 to 0x2D
            '=' -> 0 to 0x2E
            '[' -> 0 to 0x2F
            ']' -> 0 to 0x30
            '\\' -> 0 to 0x31
            ';' -> 0 to 0x33
            '\'' -> 0 to 0x34
            '`' -> 0 to 0x35
            ',' -> 0 to 0x36
            '.' -> 0 to 0x37
            '/' -> 0 to 0x38
            // Shifted symbols: same key, with the modifier set.
            '!' -> MOD_SHIFT to 0x1E
            '@' -> MOD_SHIFT to 0x1F
            '#' -> MOD_SHIFT to 0x20
            '$' -> MOD_SHIFT to 0x21
            '%' -> MOD_SHIFT to 0x22
            '^' -> MOD_SHIFT to 0x23
            '&' -> MOD_SHIFT to 0x24
            '*' -> MOD_SHIFT to 0x25
            '(' -> MOD_SHIFT to 0x26
            ')' -> MOD_SHIFT to 0x27
            '_' -> MOD_SHIFT to 0x2D
            '+' -> MOD_SHIFT to 0x2E
            '{' -> MOD_SHIFT to 0x2F
            '}' -> MOD_SHIFT to 0x30
            '|' -> MOD_SHIFT to 0x31
            ':' -> MOD_SHIFT to 0x33
            '"' -> MOD_SHIFT to 0x34
            '~' -> MOD_SHIFT to 0x35
            '<' -> MOD_SHIFT to 0x36
            '>' -> MOD_SHIFT to 0x37
            '?' -> MOD_SHIFT to 0x38
            else -> null
        }
    }
}
