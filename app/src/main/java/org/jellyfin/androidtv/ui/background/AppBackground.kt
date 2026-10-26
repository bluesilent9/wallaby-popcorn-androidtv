package org.jellyfin.androidtv.ui.background

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.koin.compose.koinInject

@Composable
private fun AppThemeBackground() {
	Image(
		painter = androidx.compose.ui.res.painterResource(R.drawable.wallaby_cinema),
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = Modifier.fillMaxSize(),
	)
	Box(Modifier.fillMaxSize().background(Color(0xA60F0819)))
}

@Composable
fun AppBackground() {
	val backgroundService = koinInject<BackgroundService>()
	val currentBackground by backgroundService.currentBackground.collectAsState()
	val blurBackground by backgroundService.blurBackground.collectAsState()
	val enabled by backgroundService.enabled.collectAsState()

	if (enabled) {
		AnimatedContent(
			targetState = currentBackground,
			transitionSpec = {
				val duration = BackgroundService.TRANSITION_DURATION.inWholeMilliseconds.toInt()
				fadeIn(tween(durationMillis = duration)) togetherWith fadeOut(tween(durationMillis = duration))
			},
			label = "BackgroundTransition",
		) { background ->
			if (background != null) {
				Image(
					bitmap = background,
					contentDescription = null,
					alignment = Alignment.Center,
					contentScale = ContentScale.Crop,
					colorFilter = ColorFilter.tint(colorResource(R.color.background_filter), BlendMode.SrcAtop),
					modifier = Modifier
						.fillMaxSize()
						.then(if (blurBackground) Modifier.blur(10.dp) else Modifier)
				)
			} else {
				AppThemeBackground()
			}
		}
	}
}
