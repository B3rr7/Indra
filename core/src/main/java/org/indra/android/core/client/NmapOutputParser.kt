// Byte offsets and masks are the C struct layout this parser mirrors.
@file:Suppress("MagicNumber")

package org.indra.android.core.client

import org.indra.android.events.Event
import org.indra.android.events.Hop
import org.indra.android.events.Os
import org.indra.android.events.Port
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Parses the `nmap` handler's structured output (`cSploitHandlers/nmap.h`), the only structured
 * handler the app drives (`SmartToolBox.nmap`/`nmapXml`); every other tool runs through `raw`.
 *
 * The bodies are cast straight from the wire, so the offsets mirror the C structs:
 * `nmap_service_info { char action; char proto; uint16 port; char service[]; }` and
 * `nmap_os_info { char action; uint8 accuracy; char os[]; }`. `Hop` uses the **packed**
 * `nmap_hop_info`, so its fields are unaligned.
 */
object NmapOutputParser {

    private const val ACTION_OFFSET = 0
    private const val PROTO_OFFSET = 1
    private const val PORT_OFFSET = 2
    private const val ACCURACY_OFFSET = 1
    private const val SERVICE_OFFSET = 4
    private const val OS_OFFSET = 2

    private const val HOP_HOP_OFFSET = 1
    private const val HOP_USEC_OFFSET = 3
    private const val HOP_ADDRESS_OFFSET = 7
    private const val HOP_NAME_OFFSET = 11

    private const val ACTION_HOP = 0
    private const val ACTION_PORT = 1
    private const val ACTION_SERVICE = 2
    private const val ACTION_OS = 3

    /** `enum nmap_proto`, indexed by the wire value. */
    private val PROTOCOLS = arrayOf("tcp", "udp", "icmp", "igmp", "unknown")

    fun parse(body: ByteArray): Event? {
        if (body.isEmpty()) return null
        return when (body[ACTION_OFFSET].toInt()) {
            ACTION_PORT, ACTION_SERVICE -> portEvent(body)
            ACTION_OS -> osEvent(body)
            ACTION_HOP -> hopEvent(body)
            else -> null
        }
    }

    private fun portEvent(body: ByteArray): Port {
        val protoIndex = body[PROTO_OFFSET].toInt() and 0xFF
        val protocol = PROTOCOLS.getOrElse(protoIndex) { PROTOCOLS.last() }
        val port = le16(body, PORT_OFFSET)
        val strings = stringArray(body, SERVICE_OFFSET)
        return Port(protocol, port, strings.getOrNull(0), strings.getOrNull(1))
    }

    private fun osEvent(body: ByteArray): Os {
        val accuracy = (body[ACCURACY_OFFSET].toInt() and 0xFF).toShort()
        val strings = stringArray(body, OS_OFFSET)
        return Os(accuracy, strings.getOrNull(0), strings.getOrNull(1))
    }

    private fun hopEvent(body: ByteArray): Hop {
        val hop = le16(body, HOP_HOP_OFFSET)
        val usec = le32(body, HOP_USEC_OFFSET).toLong() and 0xFFFFFFFFL
        val address =
            runCatching {
                InetAddress.getByAddress(
                    byteArrayOf(
                        body[HOP_ADDRESS_OFFSET],
                        body[HOP_ADDRESS_OFFSET + 1],
                        body[HOP_ADDRESS_OFFSET + 2],
                        body[HOP_ADDRESS_OFFSET + 3],
                    ),
                )
            }.getOrNull()
        val name = cString(body, HOP_NAME_OFFSET).takeIf { it.isNotEmpty() }
        return Hop(hop, usec, address, name)
    }

    /** Walk the NUL-terminated strings from [start] to the end of the body (str_array rule). */
    private fun stringArray(body: ByteArray, start: Int): List<String> {
        val result = ArrayList<String>()
        var offset = start
        while (offset < body.size) {
            val next = cString(body, offset)
            result += next
            offset += next.toByteArray(StandardCharsets.UTF_8).size + 1
        }
        return result
    }

    private fun cString(body: ByteArray, start: Int): String {
        if (start >= body.size) return ""
        var end = start
        while (end < body.size && body[end] != 0.toByte()) end++
        return String(body, start, end - start, StandardCharsets.UTF_8)
    }

    private fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
