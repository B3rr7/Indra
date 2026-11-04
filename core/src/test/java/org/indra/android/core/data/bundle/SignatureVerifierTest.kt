package org.indra.android.core.data.bundle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/** Unit tests for [SignatureVerifier] using a throwaway EC P-256 key. */
class SignatureVerifierTest {

    private companion object {
        const val EC_KEY_BITS = 256
        const val MESSAGE = "the signed manifest bytes"
    }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(EC_KEY_BITS) }.generateKeyPair()

    private fun sign(data: ByteArray, key: KeyPair, algorithm: String = "SHA256withECDSA"): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(key.private)
            update(data)
            sign()
        }

    @Test
    fun `accepts a valid signature`() {
        val key = keyPair()
        val data = MESSAGE.toByteArray()
        assertTrue(SignatureVerifier.verify(data, sign(data, key), key.public))
    }

    @Test
    fun `rejects a signature over different data`() {
        val key = keyPair()
        assertFalse(SignatureVerifier.verify("tampered".toByteArray(), sign(MESSAGE.toByteArray(), key), key.public))
    }

    @Test
    fun `rejects a signature from another key`() {
        val key = keyPair()
        val data = MESSAGE.toByteArray()
        assertFalse(SignatureVerifier.verify(data, sign(data, key), keyPair().public))
    }

    @Test
    fun `rejects malformed signature bytes`() {
        val key = keyPair()
        assertFalse(SignatureVerifier.verify(MESSAGE.toByteArray(), ByteArray(0), key.public))
    }

    @Test
    fun `fails closed when no key is configured`() {
        val key = keyPair()
        val data = MESSAGE.toByteArray()
        assertFalse(SignatureVerifier.verify(data, sign(data, key), null))
    }

    @Test
    fun `the pinned key is present and parses`() {
        assertTrue(SignatureVerifier.isConfigured)
    }
}
