package org.jellyfin.androidtv.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ssh.SshServerAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val SSH_CONNECT_TIMEOUT_MS = 10_000
private const val ENROLLMENT_PROTOCOL = "JFIN-SSH-ENROLL/1"
private const val SSH_AUTH_TIMEOUT_SECONDS = 15
private const val SSH_KEEPALIVE_COUNT = 3
private const val SSH_KEY_ALIAS_PREFIX = "jellyfin-ssh-"
private const val SSH_PRIVATE_KEY_FILE = "identity.enc"
private const val SSH_PUBLIC_KEY_FILE = "identity.pub"
private const val SSH_PIN_PREFERENCES = "ssh-host-pins"
private val SSH_HOST_KEY_ALGORITHMS = listOf(
    "ssh-ed25519",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "rsa-sha2-512",
    "rsa-sha2-256",
    "ssh-rsa",
)

enum class SshFailure {
    DNS,
    TIMEOUT,
    CONNECTION_REFUSED,
    NETWORK_UNREACHABLE,
    CONNECTION_LOST,
    SSH_HANDSHAKE_FAILED,
    HOST_KEY_CHANGED,
    PASSWORD_REJECTED,
    PUBLIC_KEY_REJECTED,
    UNSUPPORTED_AUTHENTICATION,
    ENROLLMENT_UNAVAILABLE,
    EXISTING_KEY,
    ENROLLMENT_REJECTED,
    FORWARDING_REJECTED,
    JELLYFIN_UNAVAILABLE,
    KEY_MISSING,
    UNKNOWN,
}

class SshTunnelException(val failure: SshFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
data class SshHostKeyIdentity(val fingerprint: String, val algorithm: String)

/** Owns the SSH identity, pinned server key, connection, and loopback-only Jellyfin tunnel. */
class SshTunnelManager(private val context: Context) {
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()
    private val addressResolver = SshAddressResolver(context)
    private val preferences = context.getSharedPreferences(SSH_PIN_PREFERENCES, Context.MODE_PRIVATE)
    private val sshDirectory = File(context.filesDir, "ssh-tunnel")
    private var session: Session? = null
    private var localPort: Int? = null
    private var activeAddress: SshServerAddress? = null

    fun hasEnrolledKey(address: SshServerAddress): Boolean = keyFiles(address).privateFile.isFile

    fun getTrustedFingerprint(address: SshServerAddress): String? = preferences.getString(pinKey(address), null)

    fun currentEndpoint(address: SshServerAddress): String? {
        val currentSession = session ?: return null
        val port = localPort ?: return null
        if (!currentSession.isConnected || activeAddress != address) return null
        return "http://127.0.0.1:$port"
    }

    fun currentEndpoint(address: String): String? {
        val sshAddress = SshServerAddress.parse(address) ?: return address
        return currentEndpoint(sshAddress)
    }

    suspend fun resolveApiUrl(address: String): String {
        val sshAddress = SshServerAddress.parse(address) ?: return address
        return try {
            (currentEndpoint(sshAddress) ?: connectWithEnrolledKey(sshAddress)).also {
                _connectionError.value = null
            }
        } catch (error: SshTunnelException) {
            _connectionError.value = error.message
            throw error
        }
    }

    fun disconnect() = close()

    suspend fun inspectHostKey(address: SshServerAddress): SshHostKeyIdentity = withContext(Dispatchers.IO) {
        val expected = getTrustedFingerprint(address)
        val pinnedAlgorithm = preferences.getString(hostKeyAlgorithmKey(address), null)
        val algorithms = pinnedAlgorithm?.let(::listOf) ?: SSH_HOST_KEY_ALGORITHMS
        var observedKey = false
        var lastProbe: Session? = null
        var lastFailure: Throwable? = null
        for (algorithm in algorithms) {
            val probe = JSch().getSession(address.username, address.hostname, address.port)
            probe.setSocketFactory(DualStackSocketFactory(SSH_CONNECT_TIMEOUT_MS, addressResolver::resolve))
            lastProbe = probe
            try {
                probe.setConfig("StrictHostKeyChecking", "no")
                probe.setHostKeyRepository(ProbeHostKeyRepository)
                probe.setConfig("PreferredAuthentications", "none")
                probe.setConfig("server_host_key", algorithm)
                probe.setTimeout(SSH_CONNECT_TIMEOUT_MS)
                val probeResult = runCatching { probe.connect(SSH_CONNECT_TIMEOUT_MS) }
                lastFailure = probeResult.exceptionOrNull()
                val hostKey = probe.hostKey ?: continue
                observedKey = true
                val identity = SshHostKeyIdentity(fingerprint(Base64.decode(hostKey.key, Base64.DEFAULT)), algorithm)
                if (expected == null) return@withContext identity
                if (expected == identity.fingerprint) {
                    // Older app versions pinned only the fingerprint. Remember which server key matched it
                    // so future connections keep using that same key when the server offers several.
                    preferences.edit().putString(hostKeyAlgorithmKey(address), algorithm).apply()
                    return@withContext identity
                }
            } finally {
                probe.disconnect()
            }
        }
        if (observedKey && expected != null) {
            throw SshTunnelException(SshFailure.HOST_KEY_CHANGED, "The SSH server host key changed. Verify it with the server administrator before reconnecting.")
        }
        throw classify(lastProbe, lastFailure)
    }

    fun trustHostKey(address: SshServerAddress, fingerprint: String, algorithm: String) {
        preferences.edit()
            .putString(pinKey(address), fingerprint)
            .putString(hostKeyAlgorithmKey(address), algorithm)
            .apply()
    }

    suspend fun enrollAndConnect(address: SshServerAddress, password: CharArray): String = withContext(Dispatchers.IO) {
        val pin = getTrustedFingerprint(address)
            ?: throw SshTunnelException(SshFailure.UNKNOWN, "Verify and trust this server's SSH fingerprint before enrolling a key.")
        val pair = generateIdentity()
        // Ed25519 keys in this JSch build only support the OpenSSH v1 private-key
        // format. writePrivateKey() calls the unsupported legacy serializer.
        val privateKey = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
        val publicBlob = pair.publicKeyBlob
        val publicText = "ssh-ed25519 ${Base64.encodeToString(publicBlob, Base64.NO_WRAP)}"
        val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        var bootstrap: Session? = null
        try {
            bootstrap = connect(address, pin, password = passwordBytes)
            val response = enroll(bootstrap, "$ENROLLMENT_PROTOCOL $publicText\n".toByteArray(StandardCharsets.UTF_8))
            when {
                response == "$ENROLLMENT_PROTOCOL OK INSTALLED" -> Unit
                response == "$ENROLLMENT_PROTOCOL OK ALREADY_PRESENT" -> Unit
                response == "$ENROLLMENT_PROTOCOL ERROR EXISTING_KEY_REQUIRES_ADMIN" ->
                    throw SshTunnelException(SshFailure.EXISTING_KEY, "This SSH account already has a different key. Ask the administrator to review authorized_keys.")
                response.startsWith("$ENROLLMENT_PROTOCOL ERROR HELPER_UNAVAILABLE") ->
                    throw SshTunnelException(SshFailure.ENROLLMENT_UNAVAILABLE, "The server-side SSH enrollment helper is not installed or the restricted bootstrap session is unavailable.")
                else -> throw SshTunnelException(SshFailure.ENROLLMENT_REJECTED, "The server rejected the SSH key enrollment request.")
            }
            storeIdentity(address, privateKey, publicBlob)
            connectWithKeyAndForward(address, pin)
        } catch (exception: SshTunnelException) {
            throw exception
        } catch (exception: Exception) {
            throw classify(bootstrap, exception, passwordAuthentication = true)
        } finally {
            passwordBytes.fill(0)
            privateKey.fill(0)
            pair.dispose()
            bootstrap?.disconnect()
        }
    }

    suspend fun connectWithEnrolledKey(address: SshServerAddress): String = withContext(Dispatchers.IO) {
        val pin = getTrustedFingerprint(address)
            ?: throw SshTunnelException(SshFailure.UNKNOWN, "The SSH server key has not been trusted yet. Verify its fingerprint to continue.")
        if (!hasEnrolledKey(address)) {
            throw SshTunnelException(SshFailure.KEY_MISSING, "The phone's SSH key is missing. Re-enroll it after an administrator checks the server account.")
        }
        connectWithKeyAndForward(address, pin)
    }

    private fun connectWithKeyAndForward(address: SshServerAddress, pin: String): String {
        currentEndpoint(address)?.let { return it }
        close()
        val files = keyFiles(address)
        if (!files.privateFile.isFile || !files.publicFile.isFile) {
            throw SshTunnelException(SshFailure.KEY_MISSING, "The phone's SSH key is missing. Re-enroll it after an administrator checks the server account.")
        }

        val encrypted = files.privateFile.readBytes()
        val privateKey = decryptPrivateKey(address, encrypted)
        val publicBlob = Base64.decode(files.publicFile.readText().trim(), Base64.NO_WRAP)
        encrypted.fill(0)
        val ssh = JSch()
        try {
            ssh.addIdentity("jellyfin-mobile", privateKey, publicBlob, null)
        } finally {
            privateKey.fill(0)
        }
        val connected = try {
            connect(address, pin, jsch = ssh)
        } catch (exception: Exception) {
            throw classify(null, exception, publicKeyAuthentication = true)
        }

        var forwardedPort: Int? = null
        var forwardingFailure: JSchException? = null
        repeat(3) {
            if (forwardedPort != null) return@repeat
            val candidate = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use(ServerSocket::getLocalPort)
            try {
                connected.setPortForwardingL("127.0.0.1", candidate, "127.0.0.1", 8096)
                forwardedPort = candidate
            } catch (error: JSchException) {
                forwardingFailure = error
                // A local race can consume the probed port; try a new OS-assigned port.
            }
        }
        val port = forwardedPort ?: run {
            connected.disconnect()
            throw SshTunnelException(SshFailure.FORWARDING_REJECTED, "SSH signed in, but the local Jellyfin tunnel could not be opened. Retry, then check SSH forwarding permissions and whether another tunnel is using the local port.", forwardingFailure)
        }
        session = connected
        localPort = port
        activeAddress = address
        return "http://127.0.0.1:$port"
    }

    private fun connect(
        address: SshServerAddress,
        pin: String,
        password: ByteArray? = null,
        jsch: JSch = JSch(),
    ): Session {
        val ssh = jsch.getSession(address.username, address.hostname, address.port)
        ssh.setSocketFactory(DualStackSocketFactory(SSH_CONNECT_TIMEOUT_MS, addressResolver::resolve))
        ssh.setHostKeyRepository(PinnedHostKeyRepository(pin))
        ssh.setConfig("StrictHostKeyChecking", "yes")
        ssh.setConfig("PreferredAuthentications", if (password != null) "password" else "publickey")
        preferences.getString(hostKeyAlgorithmKey(address), null)?.let { ssh.setConfig("server_host_key", it) }
        ssh.setServerAliveInterval(SSH_AUTH_TIMEOUT_SECONDS * 1_000)
        ssh.setServerAliveCountMax(SSH_KEEPALIVE_COUNT)
        ssh.setTimeout(SSH_CONNECT_TIMEOUT_MS)
        if (password != null) ssh.setPassword(password)
        return try {
            ssh.connect(SSH_CONNECT_TIMEOUT_MS)
            ssh
        } catch (exception: Exception) {
            ssh.disconnect()
            throw exception
        }
    }

    private fun enroll(ssh: Session, request: ByteArray): String {
        val channel = ssh.openChannel("shell") as ChannelShell
        return try {
            channel.setPty(false)
            val input = channel.inputStream
            channel.connect(SSH_CONNECT_TIMEOUT_MS)
            channel.outputStream.use { output ->
                output.write(request)
                output.flush()
            }
            val response = ByteArrayOutputStream()
            while (response.size() <= 256) {
                val byte = input.read()
                if (byte == -1 || byte == '\n'.code) break
                response.write(byte)
            }
            request.fill(0)
            response.toString(StandardCharsets.UTF_8.name())
        } catch (exception: Exception) {
            throw SshTunnelException(SshFailure.ENROLLMENT_UNAVAILABLE, "The server did not complete the restricted key enrollment session.")
        } finally {
            channel.disconnect()
        }
    }

    private fun generateIdentity(): KeyPair = try {
        KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
    } catch (exception: Exception) {
        throw SshTunnelException(SshFailure.UNSUPPORTED_AUTHENTICATION, "This device's SSH library could not generate an Ed25519 key.")
    }

    private fun storeIdentity(address: SshServerAddress, privateKey: ByteArray, publicBlob: ByteArray) {
        sshDirectory.mkdirs()
        val files = keyFiles(address)
        val encrypted = encryptPrivateKey(address, privateKey)
        writeAtomically(files.privateFile, encrypted)
        writeAtomically(files.publicFile, Base64.encode(publicBlob, Base64.NO_WRAP))
        encrypted.fill(0)
    }

    private fun encryptPrivateKey(address: SshServerAddress, privateKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey(address))
        val encrypted = cipher.doFinal(privateKey)
        return cipher.iv + encrypted
    }

    private fun decryptPrivateKey(address: SshServerAddress, encrypted: ByteArray): ByteArray {
        try {
            val iv = encrypted.copyOfRange(0, 12)
            val payload = encrypted.copyOfRange(12, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(address), GCMParameterSpec(128, iv))
            return cipher.doFinal(payload)
        } catch (exception: Exception) {
            throw SshTunnelException(SshFailure.KEY_MISSING, "The stored SSH key could not be opened. Re-enrollment requires administrator review of the existing server key.")
        }
    }

    private fun getOrCreateEncryptionKey(address: SshServerAddress): SecretKey {
        val alias = keyAlias(address)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun writeAtomically(destination: File, contents: ByteArray) {
        val temporary = File(sshDirectory, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(contents)
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) throw IllegalStateException("Unable to store SSH identity")
        } finally {
            temporary.delete()
        }
    }

    private fun keyFiles(address: SshServerAddress): IdentityFiles {
        val directory = File(sshDirectory, identityId(address))
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Unable to create private SSH storage")
        return IdentityFiles(File(directory, SSH_PRIVATE_KEY_FILE), File(directory, SSH_PUBLIC_KEY_FILE))
    }

    private fun close() {
        localPort?.let { port -> runCatching { session?.delPortForwardingL(port) } }
        session?.disconnect()
        session = null
        localPort = null
        activeAddress = null
    }

    private fun identityId(address: SshServerAddress): String =
        MessageDigest.getInstance("SHA-256").digest(address.profileAddress().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun keyAlias(address: SshServerAddress): String = SSH_KEY_ALIAS_PREFIX + identityId(address)

    private fun pinKey(address: SshServerAddress): String = "${address.username}@${address.hostname}:${address.port}"

    private fun hostKeyAlgorithmKey(address: SshServerAddress): String = "${pinKey(address)}:algorithm"

    private fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(key),
        Base64.NO_WRAP,
    ).trimEnd('=')

    private fun classify(
        session: Session?,
        exception: Throwable?,
        passwordAuthentication: Boolean = false,
        publicKeyAuthentication: Boolean = false,
    ): SshTunnelException {
        return classifySshFailure(exception, session?.isConnected == true, passwordAuthentication, publicKeyAuthentication)
    }

    private data class IdentityFiles(val privateFile: File, val publicFile: File)

    private object ProbeHostKeyRepository : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int = HostKeyRepository.NOT_INCLUDED
        override fun add(hostKey: HostKey?, userInfo: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        override fun getKnownHostsRepositoryID(): String = "fingerprint-probe"
    }

    /** Try IPv6 first, then IPv4 when the network cannot connect over IPv6. */
    private class PinnedHostKeyRepository(private val trustedFingerprint: String) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val current = "SHA256:" + Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key),
                Base64.NO_WRAP,
            ).trimEnd('=')
            return if (current == trustedFingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostKey: HostKey?, userInfo: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        override fun getKnownHostsRepositoryID(): String = "pinned-sha256"
    }
}
