package org.jellyfin.androidtv.ssh

import com.jcraft.jsch.JSchException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Classify the entire cause chain: Android commonly wraps ConnectException in ErrnoException. */
internal fun classifySshFailure(
    exception: Throwable?,
    connected: Boolean = false,
    passwordAuthentication: Boolean = false,
    publicKeyAuthentication: Boolean = false,
): SshTunnelException {
    if (exception is SshTunnelException) return exception
    val causes = generateSequence(exception) { it.cause }.take(16).toList()
    val message = causes.joinToString(" ") { it.message.orEmpty() }
    val (failure, display) = when {
        causes.any { it is UnknownHostException } -> SshFailure.DNS to
            "Could not resolve the SSH host name. Check the spelling, DNS, and network connection."
        causes.any { it is NoRouteToHostException } || message.contains("ENETUNREACH", true) || message.contains("EHOSTUNREACH", true) -> SshFailure.NETWORK_UNREACHABLE to
            "No network route to the SSH server. Check Wi-Fi/mobile data and IPv6 connectivity."
        causes.any { it is SocketTimeoutException } || message.contains("timeout", true) || message.contains("timed out", true) -> SshFailure.TIMEOUT to
            "The SSH connection timed out. Check server availability, IPv6 connectivity, and firewall rules."
        message.contains("ECONNREFUSED", true) || message.contains("connection refused", true) -> SshFailure.CONNECTION_REFUSED to
            "The SSH connection was refused. Check the host, port, and whether SSH is running on the server."
        causes.any { it is ConnectException } -> SshFailure.UNKNOWN to
            "Could not open a connection to SSH. Check the host, port, and network connection."
        message.contains("HostKey", true) || message.contains("host key", true) -> SshFailure.HOST_KEY_CHANGED to
            "The SSH server identity could not be verified. Check its fingerprint with the administrator before trusting it."
        message.contains("Auth fail", true) && passwordAuthentication -> SshFailure.PASSWORD_REJECTED to
            "The SSH password was rejected or password login is disabled. Check the SSH username and server policy."
        message.contains("Auth fail", true) && publicKeyAuthentication -> SshFailure.PUBLIC_KEY_REJECTED to
            "The enrolled SSH key was rejected. Check the SSH username and this device's authorized key. Password fallback is disabled."
        message.contains("Auth fail", true) -> SshFailure.UNSUPPORTED_AUTHENTICATION to
            "SSH responded, but did not accept the available login method. Check the account's authentication settings."
        message.contains("Algorithm negotiation", true) -> SshFailure.SSH_HANDSHAKE_FAILED to
            "SSH responded, but no compatible security algorithm was found. Check client and server SSH compatibility."
        causes.any { it is SocketException } -> SshFailure.CONNECTION_LOST to
            "The SSH connection was interrupted. Check the network and retry; the server may have closed the connection."
        connected -> SshFailure.JELLYFIN_UNAVAILABLE to
            "SSH connected, but Jellyfin could not be reached through the tunnel. Check Jellyfin on server port 8096 and forwarding permissions."
        causes.any { it is JSchException } -> SshFailure.SSH_HANDSHAKE_FAILED to
            "SSH negotiation did not complete. Check that the selected port runs SSH and that client and server are compatible."
        else -> SshFailure.UNKNOWN to
            "The SSH connection failed. Check the server address, port, network, and SSH configuration."
    }
    return SshTunnelException(failure, display, exception)
}
