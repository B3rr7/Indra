package org.indra.android.core.system.handshake

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The WPA/WPA2 passphrase checks the handshake is tested against, computed in-process.
 *
 * Why this exists at all: the previous path shelled out to `hashcat` inside the Alpine chroot,
 * and `hcxpcapngtool` before it. Neither is obtainable on this device — Alpine packages no
 * `hcxpcapngtool`, so `Capability.HCXTOOLS` and `Capability.HASHCAT` were permanently
 * unsatisfiable and the Crack button could never work. Both are gone; a WPA2 passphrase check is
 * 4096 HMAC-SHA1 operations, which any phone can do without an engine.
 *
 * The three derivations are the whole standard:
 * - `PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32)`
 * - `PTK = PRF-512(PMK, "Pairwise key expansion", min/max ap‖sta‖anonce‖snonce)`
 * - `PMKID = HMAC-SHA1-128(PMK, "PMK Name" ‖ ap ‖ sta)`
 *
 * PBKDF2 is written out rather than delegated to `SecretKeyFactory`, for two reasons: the JCA
 * PBKDF2WithHmacSHA1 implementation allocates a new `Mac` and key spec for every candidate, which
 * dominates at this round count, and it rejects PBEKeySpec awkwardly around non-ASCII passphrases.
 * Here one `Mac` is initialised per candidate and reused for all 8192 inner operations, and the
 * passphrase's UTF-8 encoding is ours to state.
 *
 * Pure: no I/O, no Android types, no coroutines. Safe to call from any thread.
 */
object WpaKey {
    const val PMK_BYTES = 32
    const val PTK_BYTES = 64

    private const val HMAC = "HmacSHA1"
    private const val KCK_BYTES = 16
    private const val SHA1_BYTES = 20
    private const val PMKID_BYTES = 16
    private const val PBKDF2_ROUNDS = 4096
    private const val PRF_LABEL = "Pairwise key expansion"
    private const val PMKID_LABEL = "PMK Name"

    /** A 64-hex-digit wordlist line is a raw PSK, not a passphrase (aircrack's rule too). */
    private const val PSK_HEX_DIGITS = 64
    private const val HEX_RADIX = 16

    private const val BYTE_BITS = 8
    private const val INT_BITS = 32

    /**
     * True when [candidate] derives the handshake's MIC, or its PMKID.
     *
     * Both checks share one PMK, so a capture that carries a PMKID and a MIC is not two derivations.
     */
    fun test(
        handshake: EapolHandshake.Handshake,
        ssid: String,
        candidate: String,
    ): Boolean {
        if (candidate.isEmpty()) return false
        val pmk = pmkOf(candidate, ssid)
        return pmkidMatches(handshake, pmk) || micMatches(handshake, pmk)
    }

    /**
     * The PMK for one candidate: a 64-hex-digit line is used as the raw key, anything else is run
     * through PBKDF2.
     *
     * That rule is not a convenience — `wpa_passphrase` and every WPA2 wordlist in circulation put
     * a PSK in a list as 64 hex digits, and hashing those as an ASCII passphrase would silently
     * miss the key.
     */
    fun pmkOf(
        candidate: String,
        ssid: String,
    ): ByteArray {
        if (candidate.length == PSK_HEX_DIGITS && candidate.all { it.isHexDigit() }) {
            return ByteArray(PSK_HEX_DIGITS / 2) { index ->
                candidate.substring(2 * index, 2 * index + 2).toInt(HEX_RADIX).toByte()
            }
        }
        return pbkdf2(candidate.toByteArray(Charsets.UTF_8), ssid.toByteArray(Charsets.UTF_8))
    }

    /** `PBKDF2-HMAC-SHA1(password, ssid, 4096)`, the first 32 bytes of one 4096-round block pair. */
    fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(password, HMAC))
        val out = ByteArray(PMK_BYTES)
        var written = 0
        var blockIndex = 0
        val u = ByteArray(SHA1_BYTES)
        val accumulator = ByteArray(SHA1_BYTES)
        while (written < PMK_BYTES) {
            blockIndex++
            mac.update(salt)
            // INT(blockIndex) is four big-endian bytes: `salt ‖ 0x01` is a different message from
            // `salt ‖ 0x00000001`, and only the latter is PBKDF2.
            for (shift in (INT_BITS - BYTE_BITS) downTo 0 step BYTE_BITS) {
                mac.update((blockIndex ushr shift).toByte())
            }
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, accumulator, 0, SHA1_BYTES)
            repeat(PBKDF2_ROUNDS - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until SHA1_BYTES) {
                    accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            val take = minOf(SHA1_BYTES, PMK_BYTES - written)
            System.arraycopy(accumulator, 0, out, written, take)
            written += take
        }
        return out
    }

    /** `PRF-512(PMK, "Pairwise key expansion", …)` — the full 64-byte PTK. */
    @Suppress("ArrayPrimitive")
    fun ptk(
        pmk: ByteArray,
        anonce: ByteArray,
        snonce: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
    ): ByteArray {
        val context = ByteArray(apMac.size + staMac.size + anonce.size + snonce.size)
        var offset = 0
        offset = writeOrdered(context, offset, apMac, staMac)
        writeOrdered(context, offset, anonce, snonce)
        return prf(pmk, PRF_LABEL, context, PTK_BYTES)
    }

    /** `HMAC-SHA1-128(PMK, "PMK Name" ‖ ap ‖ sta)` — the PMKID, for the no-client attack. */
    @Suppress("ArrayPrimitive")
    fun pmkid(
        pmk: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(pmk, HMAC))
        mac.update(PMKID_LABEL.toByteArray(Charsets.UTF_8))
        mac.update(apMac)
        mac.update(staMac)
        return mac.doFinal().copyOf(PMKID_BYTES)
    }

    /** True when [pmk] reproduces the handshake's MIC. Compares in constant time. */
    @Suppress("ReturnCount")
    fun micMatches(
        handshake: EapolHandshake.Handshake,
        pmk: ByteArray,
    ): Boolean {
        // One guard per missing piece: a handshake without both nonces, the frame or the MIC
        // cannot be checked at all, and each line names which part is absent.
        val anonce = handshake.anonce ?: return false
        val snonce = handshake.snonce ?: return false
        val eapol = handshake.eapol ?: return false
        val expected = handshake.mic ?: return false
        val kck = ptk(pmk, anonce, snonce, handshake.apMac, handshake.staMac).copyOf(KCK_BYTES)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(kck, HMAC))
        val actual = mac.doFinal(eapol).copyOf(expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    /** True when [pmk] reproduces the PMKID the AP advertised. Compares in constant time. */
    fun pmkidMatches(
        handshake: EapolHandshake.Handshake,
        pmk: ByteArray,
    ): Boolean {
        val expected = handshake.pmkid
        val addressed = handshake.apMac.isNotEmpty() && handshake.staMac.isNotEmpty()
        if (expected == null || !addressed) return false
        return MessageDigest.isEqual(expected, pmkid(pmk, handshake.apMac, handshake.staMac))
    }

    /** One PRF block per 20 bytes of output, each `HMAC(key, label ‖ 0x00 ‖ context ‖ counter)`. */
    private fun prf(
        key: ByteArray,
        label: String,
        context: ByteArray,
        outBytes: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        val labelBytes = label.toByteArray(Charsets.UTF_8)
        val blocks = (outBytes + SHA1_BYTES - 1) / SHA1_BYTES
        val out = ByteArray(blocks * SHA1_BYTES)
        val block = ByteArray(SHA1_BYTES)
        for (index in 0 until blocks) {
            mac.update(labelBytes)
            mac.update(0)
            mac.update(context)
            mac.update(index.toByte())
            mac.doFinal(block, 0)
            System.arraycopy(block, 0, out, index * SHA1_BYTES, SHA1_BYTES)
        }
        return out.copyOf(outBytes)
    }

    /**
     * Writes [first] and [second] in the byte order the PTK context requires: the numerically
     * smaller of each pair first. The comparison is unsigned — a MAC byte above 0x7f is larger
     * than one below it, and a signed comparison would order a real BSSID backwards.
     */
    @Suppress("ArrayPrimitive")
    private fun writeOrdered(
        target: ByteArray,
        offset: Int,
        first: ByteArray,
        second: ByteArray,
    ): Int {
        val low = if (compareUnsigned(first, second) <= 0) first else second
        val high = if (low === first) second else first
        System.arraycopy(low, 0, target, offset, low.size)
        System.arraycopy(high, 0, target, offset + low.size, high.size)
        return offset + low.size + high.size
    }
}

@Suppress("ArrayPrimitive")
private fun compareUnsigned(
    a: ByteArray,
    b: ByteArray,
): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
        val difference = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Unsigned byte mask; file scope because both [WpaKey] and its ordering helper need it. */
private const val BYTE_MASK = 0xFF
