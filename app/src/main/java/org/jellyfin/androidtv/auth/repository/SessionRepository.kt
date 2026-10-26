package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.ssh.SshTunnelManager
import org.jellyfin.androidtv.ssh.SshTunnelException
import org.jellyfin.androidtv.ssh.SshServerAddress
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.preference.TelemetryPreferences
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.DISABLED
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.LAST_USER
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior.SPECIFIC_USER
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

data class Session(
	val userId: UUID,
	val serverId: UUID,
	val accessToken: String,
)

enum class SessionRepositoryState {
	READY,
	RESTORING_SESSION,
	SWITCHING_SESSION,
}

interface SessionRepository {
	val currentSession: StateFlow<Session?>
	val state: StateFlow<SessionRepositoryState>

	suspend fun restoreSession(destroyOnly: Boolean)
	suspend fun switchCurrentSession(serverId: UUID, userId: UUID): Boolean
	fun destroyCurrentSession()
}

class SessionRepositoryImpl(
	private val authenticationPreferences: AuthenticationPreferences,
	private val authenticationStore: AuthenticationStore,
	private val userApiClient: ApiClient,
	private val preferencesRepository: PreferencesRepository,
	private val defaultDeviceInfo: DeviceInfo,
	private val userRepository: UserRepository,
	private val serverRepository: ServerRepository,
	private val telemetryPreferences: TelemetryPreferences,
	private val sshTunnelManager: SshTunnelManager,
) : SessionRepository {
	private companion object {
		const val SSH_RESTORE_ATTEMPTS = 4
		const val SSH_RESTORE_RETRY_DELAY_MS = 2_000L
	}

	private val currentSessionMutex = Mutex()
	private val _currentSession = MutableStateFlow<Session?>(null)
	override val currentSession = _currentSession.asStateFlow()
	private val _state = MutableStateFlow(SessionRepositoryState.RESTORING_SESSION)
	override val state = _state.asStateFlow()

	override suspend fun restoreSession(destroyOnly: Boolean): Unit = withContext(NonCancellable) {
		currentSessionMutex.withLock {
			Timber.i("Restoring session")

			_state.value = SessionRepositoryState.RESTORING_SESSION

			val alwaysAuthenticate = authenticationPreferences[AuthenticationPreferences.alwaysAuthenticate]
			val autoLoginBehavior = authenticationPreferences[AuthenticationPreferences.autoLoginUserBehavior]

			when {
				alwaysAuthenticate -> destroyCurrentSession()
				autoLoginBehavior == DISABLED -> destroyCurrentSession()
				autoLoginBehavior == LAST_USER && !destroyOnly -> restoreAutomatically(createLastUserSession())
				autoLoginBehavior == SPECIFIC_USER && !destroyOnly -> {
					val serverId = authenticationPreferences[AuthenticationPreferences.autoLoginServerId].toUUIDOrNull()
					val userId = authenticationPreferences[AuthenticationPreferences.autoLoginUserId].toUUIDOrNull()
					if (serverId != null && userId != null) restoreAutomatically(createUserSession(serverId, userId))
				}
			}

			_state.value = SessionRepositoryState.READY
		}
	}

	private suspend fun restoreAutomatically(session: Session?) {
		if (session == null) return

		val server = authenticationStore.getServer(session.serverId)
		val sshAddress = server?.takeIf { SshServerAddress.parse(it.address) != null }
		val attempts = if (sshAddress != null) SSH_RESTORE_ATTEMPTS else 1
		for (attempt in 0 until attempts) {
			_state.value = SessionRepositoryState.RESTORING_SESSION
			val restored = try {
				if (sshAddress != null) sshTunnelManager.resolveApiUrl(sshAddress.address)
				setCurrentSession(session, restoring = true)
			} catch (exception: Exception) {
				Timber.w(exception, "Automatic sign-in restore failed (attempt ${attempt + 1}/$attempts)")
				false
			}
			if (restored) return
			if (attempt < attempts - 1) delay(SSH_RESTORE_RETRY_DELAY_MS)
		}
	}

	override suspend fun switchCurrentSession(serverId: UUID, userId: UUID): Boolean {
		// No change in user - don't switch
		if (currentSession.value?.userId == userId) {
			Timber.d("Current session user is the same as the requested user")
			return false
		}

		_state.value = SessionRepositoryState.SWITCHING_SESSION
		Timber.i("Switching current session to user $userId")

		val session = createUserSession(serverId, userId)
		if (session == null) {
			Timber.w("Could not switch to non-existing session for user $userId")
			_state.value = SessionRepositoryState.READY
			return false
		}

		return try {
			setCurrentSession(session)
		} catch (error: SshTunnelException) {
			Timber.w(error, "SSH unavailable while switching session")
			false
		} finally {
			_state.value = SessionRepositoryState.READY
		}
	}

	override fun destroyCurrentSession() {
		destroyCurrentSession(updateState = true)
	}

	private fun destroyCurrentSession(updateState: Boolean) {
		Timber.i("Destroying current session")

		userRepository.setCurrentUser(null)
		serverRepository.setCurrentServer(null)
		_currentSession.value = null
		if (updateState) _state.value = SessionRepositoryState.READY
	}

	private suspend fun setCurrentSession(session: Session?, restoring: Boolean = false): Boolean {
		var server: Server? = null

		if (session != null) {
			// No change in session - don't switch
			if (currentSession.value?.userId == session.userId) return true

			// Update last active user
			authenticationPreferences[AuthenticationPreferences.lastServerId] = session.serverId.toString()
			authenticationPreferences[AuthenticationPreferences.lastUserId] = session.userId.toString()

			// Check if server version is supported
			server = serverRepository.getServer(session.serverId, true)
			if (server == null || !server.versionSupported) return false
		}

		// Update session after binding the apiclient settings
		val deviceInfo = session?.let { defaultDeviceInfo.forUser(it.userId) } ?: defaultDeviceInfo
		Timber.i("Updating current session. userId=${session?.userId} server=${server?.serverVersion}")

		val baseUrl = server?.address?.let { sshTunnelManager.resolveApiUrl(it) }
		val applied = userApiClient.applySession(session, deviceInfo, baseUrl)
		if (applied && session != null) {
			try {
				val user = withContext(Dispatchers.IO) {
					userApiClient.userApi.getCurrentUser().content
				}
				userRepository.setCurrentUser(user)
				serverRepository.setCurrentServer(server)
			} catch (err: ApiClientException) {
				Timber.e(err, "Unable to authenticate: bad response when getting user info")
				destroyCurrentSession(updateState = !restoring)
				return false
			}

			// Update crash reporting URL
			val crashReportUrl = userApiClient.clientLogApi.logFileUrl()
			telemetryPreferences[TelemetryPreferences.crashReportUrl] = crashReportUrl
			telemetryPreferences[TelemetryPreferences.crashReportToken] = session.accessToken
		} else {
			userRepository.setCurrentUser(null)
			serverRepository.setCurrentServer(null)
		}
		preferencesRepository.onSessionChanged()
		_currentSession.value = session

		return true
	}

	private fun createLastUserSession(): Session? {
		val lastUserId = authenticationPreferences[AuthenticationPreferences.lastUserId].toUUIDOrNull()
		val lastServerId = authenticationPreferences[AuthenticationPreferences.lastServerId].toUUIDOrNull()

		return if (lastUserId != null && lastServerId != null) createUserSession(lastServerId, lastUserId)
		else null
	}

	private fun createUserSession(serverId: UUID, userId: UUID): Session? {
		val account = authenticationStore.getUser(serverId, userId)
		if (account?.accessToken == null) return null

		return Session(
			userId = userId,
			serverId = serverId,
			accessToken = account.accessToken
		)
	}

	private fun ApiClient.applySession(
		session: Session?,
		newDeviceInfo: DeviceInfo = defaultDeviceInfo,
		resolvedBaseUrl: String? = null,
	): Boolean {
		if (session == null) {
			update(
				baseUrl = null,
				accessToken = null,
				deviceInfo = newDeviceInfo,
			)
		} else {
			val server = authenticationStore.getServer(session.serverId)
				?: return false

			update(
				baseUrl = resolvedBaseUrl ?: server.address,
				accessToken = session.accessToken,
				deviceInfo = newDeviceInfo,
			)
		}

		return true
	}
}
