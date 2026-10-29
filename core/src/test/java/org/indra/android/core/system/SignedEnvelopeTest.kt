package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * The one signature check both OTA paths rely on. A regression here is a silent
 * "anyone who can answer the URL can push a root binary / an APK", so every
 * accept, refuse and dev-relax branch is pinned.
 */
class SignedEnvelopeTest {
    private val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    private val spki: String by lazy { Base64.getEncoder().encodeToString(keyPair.public.encoded) }

    private fun sign(payload: String): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun envelope(
        payload: String,
        signature: String = sign(payload),
    ): String = """{"payload":${quoteJson(payload)},"signature":"$signature"}"""

    private fun quoteJson(raw: String): String = "\"" + raw.replace("\"", "\\\"") + "\""

    @Test
    fun `a well formed envelope parses into payload and signature`() {
        val parsed = SignedEnvelope.parse(envelope("""{"version":"1.0"}"""))

        assertNotNull(parsed)
        assertEquals("""{"version":"1.0"}""", parsed?.payload)
        assertEquals(sign("""{"version":"1.0"}"""), parsed?.signatureBase64)
    }

    @Test
    fun `a bare manifest is not an envelope`() {
        assertNull(SignedEnvelope.parse("""{"version":"1.0","url":"https://x/a.apk"}"""))
    }

    @Test
    fun `a missing signature field is not an envelope`() {
        assertNull(SignedEnvelope.parse("""{"payload":"{\"version\":\"1.0\"}"}"""))
    }

    @Test
    fun `a missing payload field is not an envelope`() {
        assertNull(SignedEnvelope.parse("""{"signature":"abc"}"""))
    }

    @Test
    fun `an empty payload or signature is refused`() {
        assertNull(SignedEnvelope.parse("""{"payload":"","signature":"abc"}"""))
        assertNull(SignedEnvelope.parse("""{"payload":"{}","signature":"   "}"""))
    }

    @Test
    fun `text that is not json is refused rather than thrown`() {
        assertNull(SignedEnvelope.parse("<html>404</html>"))
        assertNull(SignedEnvelope.parse(""))
    }

    @Test
    fun `a real signature verifies`() {
        val payload = """{"version":"2.0.0"}"""

        assertEquals(
            SignedEnvelope.Verdict.VERIFIED,
            SignedEnvelope.verify(payload.toByteArray(), sign(payload), spki),
        )
    }

    @Test
    fun `a tampered payload is rejected`() {
        val signed = sign("""{"version":"2.0.0"}""")

        assertEquals(
            SignedEnvelope.Verdict.REJECTED,
            SignedEnvelope.verify("""{"version":"9.9.9"}""".toByteArray(), signed, spki),
        )
    }

    @Test
    fun `a signature from another key is rejected`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val foreign = Signature.getInstance("SHA256withRSA").apply {
            initSign(other.private)
            update("""{"version":"2.0.0"}""".toByteArray())
        }.sign()
        val foreignB64 = Base64.getEncoder().encodeToString(foreign)

        assertEquals(
            SignedEnvelope.Verdict.REJECTED,
            SignedEnvelope.verify("""{"version":"2.0.0"}""".toByteArray(), foreignB64, spki),
        )
    }

    @Test
    fun `a garbage signature is rejected not treated as absent`() {
        assertEquals(
            SignedEnvelope.Verdict.REJECTED,
            SignedEnvelope.verify("""{"version":"2.0.0"}""".toByteArray(), "not-base64!!", spki),
        )
    }

    @Test
    fun `a garbage public key is rejected not treated as absent`() {
        assertEquals(
            SignedEnvelope.Verdict.REJECTED,
            SignedEnvelope.verify("""{"version":"2.0.0"}""".toByteArray(), "AAAA", "AAAA"),
        )
    }

    @Test
    fun `the build placeholder key reports unverified instead of verified`() {
        assertEquals(
            SignedEnvelope.Verdict.UNVERIFIED_DEV,
            SignedEnvelope.verify("""{}""".toByteArray(), "AAAA", "REPLACE_ME_with_a_real_key"),
        )
        assertFalse(SignedEnvelope.isConfigured("REPLACE_ME_with_a_real_key"))
        assertFalse(SignedEnvelope.isConfigured(""))
        assertTrue(SignedEnvelope.isConfigured(spki))
    }

    @Test
    fun `unverified is accepted only in test-host mode, rejected never`() {
        assertTrue(SignedEnvelope.accepts(SignedEnvelope.Verdict.VERIFIED, allowTestHosts = false))
        assertTrue(SignedEnvelope.accepts(SignedEnvelope.Verdict.UNVERIFIED_DEV, allowTestHosts = true))
        assertFalse(SignedEnvelope.accepts(SignedEnvelope.Verdict.UNVERIFIED_DEV, allowTestHosts = false))
        assertFalse(SignedEnvelope.accepts(SignedEnvelope.Verdict.REJECTED, allowTestHosts = true))
        assertFalse(SignedEnvelope.accepts(SignedEnvelope.Verdict.REJECTED, allowTestHosts = false))
    }
}
