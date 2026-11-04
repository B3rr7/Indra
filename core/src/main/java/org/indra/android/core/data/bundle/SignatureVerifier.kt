package org.indra.android.core.data.bundle

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies bundle manifests against the pinned ECDSA P-256 public key.
 * CI signs the exact manifest bytes with `openssl dgst -sha256 -sign`,
 * producing a DER-encoded signature.
 */
object SignatureVerifier {

    /**
     * X.509 SubjectPublicKeyInfo of the bundle signing key. Generated once with
     * `openssl ecparam -name prime256v1 -genkey -noout`; the private half is held only in the
     * `BUNDLE_SIGNING_KEY` CI secret (`.github/workflows/bundle.yml`).
     */
    const val PINNED_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEVtEq3s0EPD9ZpRzJXamzzIus0l76
MgmOROKLmfXe7z4GTQRrhqTD2CNSOrg3ZwneB/lwULEQeHIxqfWkDmi22Q==
-----END PUBLIC KEY-----"""

    /**
     * The pinned verification key, or null when the build was provisioned with a key that does
     * not parse. Nullable on purpose: a bad PEM must fail every verification, not crash the
     * first `verify()` call (the parse used to run while evaluating the default argument, i.e.
     * before the try block that swallows verification failures).
     */
    val pinnedKey: PublicKey? by lazy { runCatching { parsePublicKey(PINNED_PUBLIC_KEY_PEM) }.getOrNull() }

    /** False when this build cannot verify any manifest (missing or malformed pinned key). */
    val isConfigured: Boolean get() = pinnedKey != null

    private fun parsePublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** True only if [signature] (DER) is a valid SHA256withECDSA signature of [data] by [key]. */
    fun verify(data: ByteArray, signature: ByteArray, key: PublicKey? = pinnedKey): Boolean {
        if (key == null) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            // Malformed DER etc. is a verification failure, not a crash.
            false
        }
    }
}
