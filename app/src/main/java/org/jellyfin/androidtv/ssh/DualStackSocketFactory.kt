package org.jellyfin.androidtv.ssh

import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** Try every resolved address, IPv6 first, instead of stopping at an unreachable IPv4 address. */
internal class DualStackSocketFactory(
    private val timeoutMillis: Int,
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val newSocket: () -> Socket = ::Socket,
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val addresses = resolve(host).distinct().sortedBy {
            when (it) {
                is Inet6Address -> 0
                is Inet4Address -> 1
                else -> 2
            }
        }
        if (addresses.isEmpty()) throw UnknownHostException("No addresses found for $host")
        var lastFailure: IOException? = null
        for (address in addresses) {
            val socket = newSocket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMillis)
                return socket
            } catch (failure: IOException) {
                lastFailure = failure
                runCatching { socket.close() }
            }
        }
        throw lastFailure ?: UnknownHostException("No usable address found for $host")
    }

    override fun getInputStream(socket: Socket) = socket.getInputStream()
    override fun getOutputStream(socket: Socket) = socket.getOutputStream()
}
