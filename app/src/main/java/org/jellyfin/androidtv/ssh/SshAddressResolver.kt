package org.jellyfin.androidtv.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Recover local IPv6 records omitted by Android when Wi-Fi has no IPv6 default route. */
internal class SshAddressResolver(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun resolve(host: String): Array<InetAddress> = resolveSshAddresses(host, InetAddress::getAllByName) {
        if (Build.VERSION.SDK_INT >= 29) queryPlatformIpv6(it) else queryLegacyIpv6(it)
    }

    @RequiresApi(29)
    private fun queryPlatformIpv6(host: String): List<InetAddress> {
        val result = CompletableFuture<List<InetAddress>>()
        val cancellation = CancellationSignal()
        try {
            // An explicit AAAA query preserves Android's selected network and Private DNS.
            DnsResolver.getInstance().query(
                null, host, DnsResolver.TYPE_AAAA, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        result.complete(if (rcode == 0) answer else emptyList())
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        result.completeExceptionally(error)
                    }
                },
            )
            return result.get(4, TimeUnit.SECONDS)
        } finally {
            cancellation.cancel()
        }
    }

    private fun queryLegacyIpv6(host: String): List<InetAddress> {
        val network = connectivity.activeNetwork ?: return emptyList()
        val properties = connectivity.getLinkProperties(network) ?: return emptyList()
        // Older Android has no public typed resolver. Never bypass an active Private DNS service.
        if (Build.VERSION.SDK_INT >= 28 && properties.isPrivateDnsActive) return emptyList()
        val query = AaaaDnsPacket.query(host, SecureRandom().nextInt(65536))
        for (server in properties.dnsServers.take(2)) {
            val answer = runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.soTimeout = 1500
                    socket.connect(InetSocketAddress(server, 53))
                    socket.send(DatagramPacket(query, query.size))
                    val reply = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(reply)
                    AaaaDnsPacket.answers(query, reply.data.copyOf(reply.length))
                }
            }.getOrDefault(emptyList())
            if (answer.isNotEmpty()) return answer
        }
        return emptyList()
    }
}
