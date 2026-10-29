package org.indra.android.core.system

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * The one signed-manifest format, shared by tool OTA ([OtaUpdateManager]) and app OTA
 * ([AppUpdateManager]).
 *
 * Wire format: `{"payload": "<the manifest itself, as a string>", "signature": "<base64
 * SHA256withRSA over the payload bytes>"}`. Signing the payload *bytes* (not a re-encoded
 * JSON object) is what makes the check verifiable: re-serializing would change key order
 * and whitespace and the signature would never match.
 *
 * Pure (no Android, no logging) so the exact accept/refuse split is unit-tested; each
 * manager keeps its own log lines and its own dev-only relaxation.
 */
object SignedEnvelope {
    /** Longest a base64 `payload` field may be before we refuse it as hostile input. */
    private const val MAX_PAYLOAD_CHARS = 4 * 1024 * 1024

    const val PAYLOAD_FIELD = "payload"
    const val SIGNATURE_FIELD = "signature"

    /** A parsed, still-unverified envelope. */
    data class Envelope(
        val payload: String,
        val signatureBase64: String,
    ) {
        fun payloadBytes(): ByteArray = payload.toByteArray(Charsets.UTF_8)
    }

    enum class Verdict {
        /** A real key verified the signature. */
        VERIFIED,

        /** No signing key provisioned in this build — the caller decides whether that is fatal. */
        UNVERIFIED_DEV,

        /** Key present but the signature does not verify (bad key, tampered payload, garbage). */
        REJECTED,
    }

    /**
     * Parses `{payload, signature}`; null when the body is not an envelope (a bare manifest)
     * or either field is missing/blank/oversized.
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun parse(json: String): Envelope? {
        val obj =
            try {
                JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: JsonSyntaxException) {
                // Not JSON at all: the caller reports "not a manifest", not "bad signature".
                null
            } catch (e: IllegalStateException) {
                null
            } ?: return null
        val payload = obj.get(PAYLOAD_FIELD)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val signature = obj.get(SIGNATURE_FIELD)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        if (payload.isEmpty() || signature.isBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
        return Envelope(payload, signature)
    }

    /** True when [spkiBase64] is a real X.509 SubjectPublicKeyInfo, not the build placeholder. */
    fun isConfigured(spkiBase64: String): Boolean =
        spkiBase64.isNotBlank() && !spkiBase64.startsWith(PLACEHOLDER_PREFIX)

    /** Base64 X.509 SPKI placeholder shipped until a release provisions a real key. */
    const val PLACEHOLDER_PREFIX = "REPLACE_ME"

    /**
     * Verifies [payload] against [signatureBase64] with the RSA public key in [spkiBase64].
     *
     * Never throws: any decoding/algorithm error is [Verdict.REJECTED], because an
     * unparseable signature must not be treated as "no signature to check".
     */
    @Suppress("SwallowedException")
    fun verify(
        payload: ByteArray,
        signatureBase64: String,
        spkiBase64: String,
    ): Verdict {
        if (!isConfigured(spkiBase64)) return Verdict.UNVERIFIED_DEV
        return try {
            val publicKey =
                java.security.KeyFactory
                    .getInstance("RSA")
                    .generatePublic(
                        java.security.spec.X509EncodedKeySpec(
                            java.util.Base64.getDecoder().decode(spkiBase64),
                        ),
                    )
            val verifier = java.security.Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(payload)
            val ok = verifier.verify(java.util.Base64.getDecoder().decode(signatureBase64))
            if (ok) Verdict.VERIFIED else Verdict.REJECTED
        } catch (e: java.security.GeneralSecurityException) {
            Verdict.REJECTED
        } catch (e: IllegalArgumentException) {
            Verdict.REJECTED
        }
    }

    /**
     * The single accept/refuse policy for both OTA paths: a verified signature is always
     * accepted, an unverified one only when the build is explicitly in test-host mode, and
     * a rejected signature never is.
     */
    fun accepts(
        verdict: Verdict,
        allowTestHosts: Boolean,
    ): Boolean =
        when (verdict) {
            Verdict.VERIFIED -> true
            Verdict.UNVERIFIED_DEV -> allowTestHosts
            Verdict.REJECTED -> false
        }
}
