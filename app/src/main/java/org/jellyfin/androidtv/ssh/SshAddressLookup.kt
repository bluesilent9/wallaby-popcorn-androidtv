package org.jellyfin.androidtv.ssh

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

internal fun resolveSshAddresses(
    host: String,
    systemLookup: (String) -> Array<InetAddress>,
    ipv6Lookup: (String) -> List<InetAddress>,
): Array<InetAddress> {
    val systemResult = runCatching { systemLookup(host).toList() }
    val addresses = systemResult.getOrDefault(emptyList())
    val literal = ':' in host || host.all { it.isDigit() || it == '.' }
    val ipv6 = if (literal || addresses.any { it is Inet6Address }) emptyList() else {
        runCatching { ipv6Lookup(host) }.getOrDefault(emptyList())
    }
    return (ipv6 + addresses).distinct().toTypedArray().also {
        if (it.isEmpty()) throw systemResult.exceptionOrNull() ?: UnknownHostException(host)
    }
}
