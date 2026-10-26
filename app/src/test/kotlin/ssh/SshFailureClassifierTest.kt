package org.jellyfin.androidtv.ssh

import com.jcraft.jsch.JSchException
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import io.kotest.core.spec.style.FunSpec

class SshFailureClassifierTest : FunSpec({
    test("nested Android errno retains connection refused instead of handshake advice") {
        val refused = ConnectException("Failed to connect").apply { initCause(IOException("ECONNREFUSED")) }
        classifySshFailure(JSchException("Session.connect", refused)).failure shouldBe SshFailure.CONNECTION_REFUSED
    }

    test("unreachable route is different from refused connection") {
        val unreachable = ConnectException("Failed to connect").apply { initCause(IOException("ENETUNREACH")) }
        classifySshFailure(JSchException("Session.connect", unreachable)).failure shouldBe SshFailure.NETWORK_UNREACHABLE
    }

    test("DNS timeout and connection reset remain distinct") {
        classifySshFailure(JSchException("connect", UnknownHostException())).failure shouldBe SshFailure.DNS
        classifySshFailure(JSchException("connect", SocketTimeoutException())).failure shouldBe SshFailure.TIMEOUT
        classifySshFailure(JSchException("connect", SocketException("ECONNRESET"))).failure shouldBe SshFailure.CONNECTION_LOST
    }

    test("auth failures preserve the attempted login method") {
        val failure = JSchException("Auth fail for methods 'publickey'")
        classifySshFailure(failure, publicKeyAuthentication = true).failure shouldBe SshFailure.PUBLIC_KEY_REJECTED
        classifySshFailure(failure, passwordAuthentication = true).failure shouldBe SshFailure.PASSWORD_REJECTED
    }

    test("security and algorithm failures have specific advice") {
        classifySshFailure(JSchException("HostKey has been changed")).failure shouldBe SshFailure.HOST_KEY_CHANGED
        classifySshFailure(JSchException("Algorithm negotiation fail")).failure shouldBe SshFailure.SSH_HANDSHAKE_FAILED
    }

    test("original diagnostic cause and curated errors are retained") {
        val original = SocketTimeoutException("read timeout")
        classifySshFailure(original).cause shouldBe original
        val curated = SshTunnelException(SshFailure.KEY_MISSING, "Saved key unavailable")
        classifySshFailure(curated) shouldBe curated
    }
})
