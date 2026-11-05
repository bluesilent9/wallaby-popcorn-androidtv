package org.jellyfin.androidtv.ssh

import com.jcraft.jsch.JSchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SshTransportFallbackTest : FunSpec({
    val v4 = InetAddress.getByName("192.0.2.1")
    val v6 = InetAddress.getByName("2001:db8::1")

    test("IPv6 succeeds without attempting IPv4 even if DNS lists IPv4 first") {
        val tried = mutableListOf<InetAddress>()
        connectSshTransport(listOf(v4, v6)) { tried.add(it); "connected" } shouldBe "connected"
        tried shouldBe listOf(v6)
    }
    test("no IPv6 route falls back to IPv4") {
        val tried = mutableListOf<InetAddress>()
        connectSshTransport(listOf(v4, v6)) {
            tried.add(it)
            if (it == v6) throw NoRouteToHostException()
            "connected"
        } shouldBe "connected"
        tried shouldBe listOf(v6, v4)
    }
    test("IPv6 timeout falls back to IPv4") {
        connectSshTransport(listOf(v6, v4)) {
            if (it == v6) throw SocketTimeoutException()
            it
        } shouldBe v4
    }
    test("SSH negotiation failure after TCP connection also falls back") {
        connectSshTransport(listOf(v6, v4)) {
            if (it == v6) throw JSchException("Session.connect: End of IO Stream Read")
            it
        } shouldBe v4
    }
    test("changed server key is fatal and never bypassed through another address") {
        val tried = mutableListOf<InetAddress>()
        shouldThrow<JSchException> {
            connectSshTransport(listOf(v6, v4)) { tried.add(it); throw JSchException("HostKey has been changed") }
        }
        tried shouldBe listOf(v6)
    }
    test("rejected credentials do not cause repeated authentication on IPv4") {
        val tried = mutableListOf<InetAddress>()
        shouldThrow<JSchException> {
            connectSshTransport(listOf(v6, v4)) { tried.add(it); throw JSchException("Auth fail") }
        }
        tried shouldBe listOf(v6)
    }
    test("IPv4-only DNS hosts still connect") {
        connectSshTransport(listOf(v4)) { it } shouldBe v4
    }
    test("a literal IPv6 host cannot invent an IPv4 destination") {
        var attempts = 0
        shouldThrow<NoRouteToHostException> {
            connectSshTransport(listOf(v6)) { attempts++; throw NoRouteToHostException() }
        }
        attempts shouldBe 1
    }
    test("an empty address set reports DNS failure") {
        shouldThrow<UnknownHostException> { connectSshTransport(emptyList()) { it } }
    }
    test("both unavailable families report failure after trying each once") {
        val tried = mutableListOf<InetAddress>()
        shouldThrow<NoRouteToHostException> {
            connectSshTransport(listOf(v4, v6)) { tried.add(it); throw NoRouteToHostException() }
        }
        tried shouldBe listOf(v6, v4)
    }
    test("IPv6 loopback endpoint is used regardless of the public SSH address family") {
        SshTunnelEndpoint.LOOPBACK shouldBe "::1"
        SshTunnelEndpoint.JELLYFIN_PORT shouldBe 8096
        SshTunnelEndpoint.url(32123) shouldBe "http://[::1]:32123"
    }
})
