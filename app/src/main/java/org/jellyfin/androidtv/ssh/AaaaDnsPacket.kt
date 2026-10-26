package org.jellyfin.androidtv.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress

/** Minimal bounded AAAA exchange for the DNS servers selected by pre-Android-10 devices. */
internal object AaaaDnsPacket {
    fun query(host: String, id: Int): ByteArray {
        val name = IDN.toASCII(host.removeSuffix("."))
        require(name.isNotEmpty() && name.length <= 253)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(0x0100) // Recursion desired
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            for (label in name.split('.')) {
                val encoded = label.toByteArray(Charsets.US_ASCII)
                require(encoded.size in 1..63)
                out.writeByte(encoded.size)
                out.write(encoded)
            }
            out.writeByte(0)
            out.writeShort(28) // AAAA
            out.writeShort(1) // IN
        }
        return bytes.toByteArray()
    }

    fun answers(query: ByteArray, reply: ByteArray): List<InetAddress> {
        fun word(offset: Int): Int {
            if (offset < 0 || offset + 2 > reply.size) throw IOException("Short DNS response")
            return ((reply[offset].toInt() and 255) shl 8) or (reply[offset + 1].toInt() and 255)
        }
        if (reply.size < query.size || query.size < 17) throw IOException("Short DNS response")
        if (!reply.copyOfRange(0, 2).contentEquals(query.copyOfRange(0, 2))) throw IOException("DNS transaction mismatch")
        val flags = word(2)
        if (flags and 0x8000 == 0 || flags and 0x7A0F != 0 || word(4) != 1) throw IOException("Unsuccessful or truncated DNS response")
        if (!reply.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size))) throw IOException("DNS question mismatch")
        fun skipName(start: Int): Int {
            var offset = start
            repeat(128) {
                if (offset >= reply.size) throw IOException("Short DNS name")
                val size = reply[offset++].toInt() and 255
                if (size == 0) return offset
                if (size and 0xC0 == 0xC0) {
                    if (offset >= reply.size) throw IOException("Short DNS pointer")
                    val target = ((size and 63) shl 8) or (reply[offset].toInt() and 255)
                    if (target >= reply.size) throw IOException("Invalid DNS pointer")
                    return offset + 1
                }
                if (size > 63 || offset + size > reply.size) throw IOException("Invalid DNS label")
                offset += size
            }
            throw IOException("DNS name too long")
        }
        var offset = query.size
        val addresses = mutableListOf<InetAddress>()
        repeat(word(6)) {
            offset = skipName(offset)
            val type = word(offset)
            val clazz = word(offset + 2)
            val length = word(offset + 8)
            offset += 10
            if (offset + length > reply.size) throw IOException("Short DNS record")
            if (type == 28 && clazz == 1 && length == 16) {
                addresses += InetAddress.getByAddress(reply.copyOfRange(offset, offset + length))
            }
            offset += length
        }
        return addresses.distinct()
    }
}
