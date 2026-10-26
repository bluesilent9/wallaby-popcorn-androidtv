package org.jellyfin.androidtv.ssh

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

class AaaaDnsPacketTest : FunSpec({
    val query = AaaaDnsPacket.query("server.example", 1234)
    val address = InetAddress.getByName("2001:db8::42")

    fun response(): ByteArray = ByteArrayOutputStream().also { bytes ->
        val header = query.copyOf()
        header[2] = 0x81.toByte()
        header[3] = 0x80.toByte()
        header[7] = 1
        DataOutputStream(bytes).use {
            it.write(header)
            it.writeShort(0xc00c) // Compressed answer name
            it.writeShort(28)
            it.writeShort(1)
            it.writeInt(60)
            it.writeShort(16)
            it.write(address.address)
        }
    }.toByteArray()

    test("reads a compressed AAAA answer") {
        AaaaDnsPacket.answers(query, response()) shouldBe listOf(address)
    }

    test("rejects mismatched transaction and question") {
        val wrongId = response().apply { this[0] = 99 }
        val wrongQuestion = response().apply { this[13] = 'x'.code.toByte() }
        shouldThrow<IOException> { AaaaDnsPacket.answers(query, wrongId) }
        shouldThrow<IOException> { AaaaDnsPacket.answers(query, wrongQuestion) }
    }

    test("rejects truncated and malformed responses") {
        val truncated = response().apply { this[2] = 0x83.toByte() }
        shouldThrow<IOException> { AaaaDnsPacket.answers(query, truncated) }
        shouldThrow<IOException> { AaaaDnsPacket.answers(query, response().dropLast(1).toByteArray()) }
        shouldThrow<IOException> { AaaaDnsPacket.answers(query, byteArrayOf()) }
    }
})
