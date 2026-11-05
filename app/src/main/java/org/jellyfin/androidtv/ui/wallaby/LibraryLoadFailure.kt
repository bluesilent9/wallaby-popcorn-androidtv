package org.jellyfin.androidtv.ui.wallaby

import org.jellyfin.androidtv.ssh.SshTunnelException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.IOException

/** Do not describe decoding, permissions or UI failures as an SSH outage. */
fun libraryLoadFailureMessage(error: Exception): String = when (error) {
	is InvalidStatusException -> when (error.status) {
		401 -> "Your sign-in has expired. Sign in again to load this library."
		403 -> "This account does not have permission to view this library."
		404 -> "This library is no longer available on the server."
		else -> "The server could not load this library (HTTP ${error.status}). Please retry."
	}
	is SshTunnelException -> "The SSH tunnel is unavailable. Reconnect to the server, then retry."
	is TimeoutException -> "The server took too long to respond. Please retry."
	is IOException -> "The server connection was interrupted. Check the connection, then retry."
	is InvalidContentException -> "The server returned library data the app could not read. Please retry."
	else -> "An internal library-loading error occurred. Please retry or reopen this library."
}
