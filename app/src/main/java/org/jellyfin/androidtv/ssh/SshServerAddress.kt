package org.jellyfin.androidtv.ssh

import java.net.URI

/** Parsed SSH transport address. The Jellyfin endpoint remains a local, forwarded HTTP URL. */
data class SshServerAddress(
    val username: String,
    val hostname: String,
    val port: Int = DEFAULT_SSH_PORT,
) {
    fun profileAddress(): String = "ssh://$username@${formatHost(hostname)}${if (port == DEFAULT_SSH_PORT) "" else ":$port"}"

    companion object {
        const val DEFAULT_SSH_PORT = 22

        fun parse(address: String): SshServerAddress? {
            val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("ssh", ignoreCase = true) || uri.rawPath.orEmpty().isNotEmpty() ||
                uri.rawQuery != null || uri.rawFragment != null
            ) return null

            val rawUserInfo = uri.rawUserInfo ?: return null
            // Passwords in URLs are intentionally rejected: SSH and Jellyfin credentials stay separate.
            if (rawUserInfo.isBlank() || rawUserInfo.contains(':') || rawUserInfo.contains('%')) return null
            val username = rawUserInfo
            if (!username.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,31}\\$?"))) return null

            val hostname = uri.host?.takeIf(String::isNotBlank)?.removeSurrounding("[", "]") ?: return null
            val port = if (uri.port == -1) DEFAULT_SSH_PORT else uri.port
            if (port !in 1..65535) return null
            return SshServerAddress(username, hostname, port)
        }

        private fun formatHost(hostname: String): String = if (hostname.contains(':')) "[$hostname]" else hostname
    }
}
