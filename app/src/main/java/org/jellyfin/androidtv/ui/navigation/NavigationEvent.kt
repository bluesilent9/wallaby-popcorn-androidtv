package org.jellyfin.androidtv.ui.navigation

import java.util.UUID

/** Unique identity distinguishes repeated Back actions from lifecycle replay. */
data class NavigationEvent(val action: NavigationAction, val id: String = UUID.randomUUID().toString())

/** Persist this alongside the host's fragment history, not in a global singleton. */
class NavigationDelivery(var lastHandledId: String? = null) {
	fun deliver(event: NavigationEvent, handle: (NavigationAction) -> Unit) {
		if (event.id == lastHandledId) return
		handle(event.action)
		lastHandledId = event.id
	}
}
