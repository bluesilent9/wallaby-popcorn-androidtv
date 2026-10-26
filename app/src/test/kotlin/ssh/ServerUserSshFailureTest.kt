package org.jellyfin.androidtv.ssh

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.repository.ServerUserRepositoryImpl
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.sdk.Jellyfin
import java.util.UUID

class ServerUserSshFailureTest : FunSpec({
    test("loading the TV login screen survives an unavailable SSH tunnel") {
        val tunnel = mockk<SshTunnelManager>()
        val server = Server(UUID.randomUUID(), "Test server", "ssh://tv@server.example")
        coEvery { tunnel.resolveApiUrl(server.address) } throws
            SshTunnelException(SshFailure.NETWORK_UNREACHABLE, "No route to the SSH server")
        val repository = ServerUserRepositoryImpl(mockk<Jellyfin>(), mockk<AuthenticationStore>(), tunnel)
        repository.getPublicServerUsers(server) shouldBe emptyList()
    }
})
