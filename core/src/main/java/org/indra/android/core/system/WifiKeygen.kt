package org.indra.android.core.system

/**
 * Offline WPA key candidates derived from a BSSID and, optionally, an ESSID.
 *
 * Two genuinely different things live here and the UI keeps them apart:
 *
 *  - [wpsChecksum] is arithmetic. The eighth digit of a WPS PIN is a checksum over the first
 *    seven, so a PIN that ends with this digit passes the router's format check. That part is
 *    correct by construction and is the only place [Confidence.HIGH] appears.
 *  - Everything else is a *pattern guess*: a router that shipped a default key built from its
 *    MAC or from its SSID. No vendor publishes those derivations, so a guess is offered as a
 *    key to try — it is never claimed to be the key. Guesses are therefore POSSIBLE or LOW, and
 *    the copy on the screen has to say so.
 */
object WifiKeygen {

    /** One candidate key, plus how much the derivation itself is worth trusting. */
    data class Result(
        val algorithm: String,
        val key: String,
        val confidence: Confidence = Confidence.POSSIBLE,
    )

    enum class Confidence {
        /** Derived arithmetic — the value is correct for the input, not a guess. */
        HIGH,

        /** A documented default *shape* (e.g. "the MAC's last eight hex digits"). */
        POSSIBLE,

        /** A long shot worth one try. */
        LOW,
    }

    /** A BSSID is six octets; anything else is not a MAC and cannot be derived from. */
    private const val MAC_HEX_LENGTH = 12
    private const val MAC_HEX_SUFFIX = 8
    private const val MAC_HEX_SHORT = 6
    private const val MAC_HEX_TAIL = 4

    private const val WPS_PIN_DIGITS = 7
    private const val WPS_MODULUS = 10
    private const val WPS_CHECKSUM_WEIGHT = 3

    /**
     * Every candidate worth trying for [bssid], most promising first.
     *
     * [essid] is optional: SSID-shaped candidates are only produced when it is given, because a
     * blank SSID would otherwise yield an empty key that costs a hammering attempt for nothing.
     * Returns an empty list for input that is not a 12-digit hex MAC.
     */
    fun generateCandidates(bssid: String, essid: String? = null): List<Result> {
        val mac = bssid.filter { it.isLetterOrDigit() }.uppercase()
        if (mac.length != MAC_HEX_LENGTH) return emptyList()
        val tail = mac.takeLast(MAC_HEX_SUFFIX)

        val macCandidates = mutableListOf<Result>()
        macCandidates += Result("MAC, last $MAC_HEX_SUFFIX hex digits", tail)
        macCandidates += Result("MAC, last $MAC_HEX_SHORT hex digits", mac.takeLast(MAC_HEX_SHORT), Confidence.LOW)
        macCandidates += Result("MAC, lowercase", mac.lowercase(), Confidence.LOW)
        macCandidates += Result("\"password\" + MAC tail", "password$tail", Confidence.LOW)
        wpsPinFrom(mac)?.let { macCandidates += Result("WPS PIN from MAC", it, Confidence.HIGH) }

        val ssid = essid?.trim()
        val ssidCandidates =
            if (ssid.isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(
                    Result("SSID as key", ssid, Confidence.LOW),
                    Result("SSID + MAC tail", "$ssid$tail", Confidence.LOW),
                    Result("SSID + \"-\" + MAC tail", "$ssid-$tail", Confidence.LOW),
                    Result("\"password\" + MAC tail($MAC_HEX_TAIL)", "password${mac.takeLast(MAC_HEX_TAIL)}", Confidence.LOW),
                )
            }

        return (macCandidates + ssidCandidates).sortedBy { it.confidence.ordinal }
    }

    /**
     * The eighth WPS PIN digit for a seven-digit prefix, or null when [pin] is not seven digits.
     *
     * The last digit of a WPS PIN is a checksum so that a typo is caught before the router burns
     * an attempt; this reproduces it exactly (even-indexed digits weighted by three, odd-indexed
     * plain, summed and negated mod ten).
     */
    fun wpsChecksum(pin: String): Char? {
        if (pin.length != WPS_PIN_DIGITS || pin.any { !it.isDigit() }) return null
        var accum = 0
        pin.forEachIndexed { index, digit ->
            val value = digit - '0'
            accum += if (index % 2 == 0) WPS_CHECKSUM_WEIGHT * value else value
        }
        return '0' + (WPS_MODULUS - accum % WPS_MODULUS) % WPS_MODULUS
    }

    /**
     * A complete eight-digit WPS PIN built from the MAC's last seven decimal digits.
     *
     * Null when those seven characters are not all digits — a hex tail like `A1B2C3D` has no
     * decimal reading, and inventing one would print a PIN the router rejects.
     */
    private fun wpsPinFrom(mac: String): String? {
        val prefix = mac.takeLast(WPS_PIN_DIGITS)
        val checksum = wpsChecksum(prefix) ?: return null
        return prefix + checksum
    }
}
