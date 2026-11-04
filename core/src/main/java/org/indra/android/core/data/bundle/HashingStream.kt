package org.indra.android.core.data.bundle

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class HashingStream(input: InputStream) : FilterInputStream(input) {

    private val md: MessageDigest
    var count: Long = 0

    init {
        try {
            md = MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is mandated by the JLS; a missing provider is a broken runtime.
            throw IllegalStateException("SHA-256 is unavailable on this runtime", e)
        }
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            md.update(b.toByte())
            count++
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            md.update(b, off, n)
            count += n.toLong()
        }
        return n
    }

    fun hexDigest(): String {
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
