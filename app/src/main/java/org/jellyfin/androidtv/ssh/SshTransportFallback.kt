package org.jellyfin.androidtv.ssh

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal fun orderedSshAddresses(addresses: Iterable<InetAddress>) = addresses.distinct().sortedBy {
    when (it) { is Inet6Address -> 0; is Inet4Address -> 1; else -> 2 }
}

/** Retry transport/negotiation failures, never a changed host identity or rejected credentials. */
internal fun canRetrySshTransport(error: Exception): Boolean = classifySshFailure(error).failure in setOf(
    SshFailure.DNS, SshFailure.TIMEOUT, SshFailure.CONNECTION_REFUSED,
    SshFailure.NETWORK_UNREACHABLE, SshFailure.CONNECTION_LOST, SshFailure.SSH_HANDSHAKE_FAILED,
)

internal fun <T> connectSshTransport(addresses: Iterable<InetAddress>, attempt: (InetAddress) -> T): T {
    var lastFailure: Exception? = null
    for (endpoint in orderedSshAddresses(addresses)) {
        try { return attempt(endpoint) }
        catch (error: Exception) {
            if (!canRetrySshTransport(error)) throw error
            lastFailure = error
        }
    }
    throw lastFailure ?: UnknownHostException("No addresses found for the SSH server")
}

/** Loopback transport is independent of the remote SSH socket's address family. */
internal object SshTunnelEndpoint {
    const val LOOPBACK = "::1"
    const val JELLYFIN_PORT = 8096
    fun url(port: Int): String {
        require(port in 1..65535)
        return "http://[::1]:$port"
    }
}
