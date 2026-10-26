package org.jellyfin.androidtv.data.service

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.ssh.SshTunnelManager
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration.Companion.milliseconds

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val imageLoader: ImageLoader,
	private val sshTunnelManager: SshTunnelManager,
) {
	companion object {
		val TRANSITION_DURATION = 800.milliseconds
	}

	// Async
	private val scope = MainScope()
	private var selectionJob: Job? = null
	private var requestedUrls: Set<String>? = null

	// Current background data
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	/**
	 * Use the selected item backdrop as background after a stable focus delay.
	 */
	fun setBackground(baseItem: BaseItemDto?) {
		val backdropBehavior = userPreferences[UserPreferences.backdropBehavior]

		// Check if item is set and backgrounds are enabled
		if (baseItem == null || baseItem.type in setOf(BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW, BaseItemKind.FOLDER) || backdropBehavior == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Enable blur for backdrops
		_blurBackground.value = backdropBehavior == BackdropBehavior.BACKDROP_WITH_BLUR

		// Get all backdrop urls
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.map { it.getUrl(api) }
			.toSet()

		requestBackgrounds(backdropUrls)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (userPreferences[UserPreferences.backdropBehavior] == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// Disable blur on splashscreen
		_blurBackground.value = false

		// Manually grab the backdrop URL
		val baseUrl = sshTunnelManager.currentEndpoint(server.address) ?: return clearBackgrounds()
		val api = jellyfin.createApi(baseUrl = baseUrl)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		requestBackgrounds(setOf(splashscreenUrl))
	}

	/** Debounce focus, retain the old image while loading, and keep only one decoded backdrop. */
	private fun requestBackgrounds(backdropUrls: Set<String>) {
		if (requestedUrls == backdropUrls && _enabled.value) return
		requestedUrls = backdropUrls
		selectionJob?.cancel()
		selectionJob = scope.launch {
			delay(1000)
			if (backdropUrls.isEmpty()) {
				_currentBackground.value = null
				_enabled.value = true
				return@launch
			}
			try {
				val url = backdropUrls.first()
				val result = imageLoader.execute(ImageRequest.Builder(context).data(url).size(1920, 1080).build())
				val bitmap = result.image?.toBitmap()?.asImageBitmap() ?: return@launch
				_currentBackground.value = bitmap
				_enabled.value = true
			} catch (cancel: CancellationException) {
				throw cancel
			} catch (_: Exception) { /* Retain the last successfully loaded artwork on connection failure. */ }
		}
	}

	fun clearBackgrounds() = requestBackgrounds(emptySet())

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		selectionJob?.cancel()
		requestedUrls = null
		_enabled.value = false
	}

}
