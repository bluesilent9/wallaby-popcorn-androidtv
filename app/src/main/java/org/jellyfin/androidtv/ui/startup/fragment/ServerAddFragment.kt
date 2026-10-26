package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.auth.model.ConnectingState
import org.jellyfin.androidtv.auth.model.UnableToConnectState
import org.jellyfin.androidtv.databinding.FragmentServerAddBinding
import org.jellyfin.androidtv.ui.startup.ServerAddViewModel
import org.jellyfin.androidtv.ssh.SshServerAddress
import org.jellyfin.androidtv.ssh.SshTunnelException
import org.jellyfin.androidtv.ssh.SshTunnelManager
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.androidtv.util.getSummary
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.inject
import kotlin.coroutines.resume

class ServerAddFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ADDRESS = "server_address"
	}

	private val startupViewModel: ServerAddViewModel by viewModel()
	private val sshTunnelManager: SshTunnelManager by inject()
	private var _binding: FragmentServerAddBinding? = null
	private val binding get() = _binding!!

	private val serverAddressArgument get() = arguments?.getString(ARG_SERVER_ADDRESS)?.ifBlank { null }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentServerAddBinding.inflate(inflater, container, false)

		with(binding.address) {
			setOnEditorActionListener { _, actionId, _ ->
				when (actionId) {
					EditorInfo.IME_ACTION_DONE -> {
						submitAddress()
						true
					}

					else -> false
				}
			}
		}

		with(binding.confirm) {
			setOnClickListener { submitAddress() }
		}

		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (serverAddressArgument != null) {
			binding.address.setText(serverAddressArgument)
			binding.address.isEnabled = false
			submitAddress()
		} else {
			binding.address.requestFocus()
		}

		startupViewModel.state.onEach { state ->
			when (state) {
				is ConnectingState -> {
					// Disable form
					binding.address.isEnabled = false
					binding.confirm.isEnabled = false
					// Update state text
					binding.error.text = getString(R.string.server_connecting, state.address)
				}

				is UnableToConnectState -> {
					// Enable form
					binding.address.isEnabled = true
					binding.confirm.isEnabled = true
					binding.address.requestFocus()
					// Update state text
					binding.error.text = getString(
						R.string.server_connection_failed_candidates,
						state.addressCandidates
							.map { "${it.key} - ${it.value.getSummary(requireContext())}" }
							.joinToString(prefix = "\n", separator = "\n")
					)
				}

				is ConnectedState -> parentFragmentManager.commit {
					// Open server view
					replace<StartupToolbarFragment>(R.id.content_view)
					add<ServerFragment>(
						R.id.content_view,
						null,
						createBundle {
							putString(ServerFragment.ARG_SERVER_ID, state.id.toString())
						}
					)
				}

				null -> Unit
			}
		}.launchIn(lifecycleScope)
	}

	override fun onDestroyView() {
		super.onDestroyView()

		_binding = null
	}

	private fun submitAddress() {
		val address = binding.address.text.toString().trim()
		if (address.isBlank()) {
			binding.error.setText(R.string.server_field_empty)
			return
		}

		if (address.startsWith("ssh://", ignoreCase = true)) {
			val sshAddress = SshServerAddress.parse(address)
			if (sshAddress == null) {
				binding.error.text = "Use ssh://username@host[:port] without a password or path."
				return
			}

			binding.address.isEnabled = false
			binding.confirm.isEnabled = false
			viewLifecycleOwner.lifecycleScope.launchWhenStarted {
				try {
					setupSsh(sshAddress)
				} catch (exception: SshTunnelException) {
					binding.error.text = exception.message
				} catch (exception: Exception) {
					binding.error.text = exception.message ?: "SSH setup failed."
				} finally {
					binding.address.isEnabled = true
					binding.confirm.isEnabled = true
				}
			}
		} else {
			startupViewModel.addServer(address)
		}
	}

	private suspend fun setupSsh(address: SshServerAddress) {
		val hostKey = sshTunnelManager.inspectHostKey(address)
		val trustedFingerprint = sshTunnelManager.getTrustedFingerprint(address)
		if (trustedFingerprint == null) {
			val accepted = confirm(
				title = "Verify SSH server",
				message = "Server: ${address.hostname}:${address.port}\nAccount: ${address.username}\n\nFingerprint:\n${hostKey.fingerprint}\n\nOnly continue if this fingerprint matches your server.",
				positive = "Trust server",
			)
			if (!accepted) return
			sshTunnelManager.trustHostKey(address, hostKey.fingerprint, hostKey.algorithm)
		}

		if (sshTunnelManager.hasEnrolledKey(address)) {
			sshTunnelManager.connectWithEnrolledKey(address)
		} else {
			val password = requestPassword(address) ?: return
			try {
				sshTunnelManager.enrollAndConnect(address, password)
			} finally {
				password.fill('\u0000')
			}
		}

		startupViewModel.addServer(address.profileAddress())
	}

	private suspend fun confirm(title: String, message: String, positive: String): Boolean =
		suspendCancellableCoroutine { continuation ->
			val dialog = AlertDialog.Builder(requireContext())
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton(positive) { _, _ -> if (continuation.isActive) continuation.resume(true) }
				.setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
				.setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
				.create()
			continuation.invokeOnCancellation { dialog.dismiss() }
			dialog.show()
		}

	private suspend fun requestPassword(address: SshServerAddress): CharArray? = suspendCancellableCoroutine { continuation ->
		val passwordInput = EditText(requireContext()).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			isSingleLine = true
			hint = "SSH account password"
		}
		val dialog = AlertDialog.Builder(requireContext())
			.setTitle("Enroll this TV")
			.setMessage("Enter the SSH password for ${address.username}@${address.hostname}. The app will store a dedicated key and use it for future connections.")
			.setView(passwordInput)
			.setPositiveButton("Enroll key") { _, _ ->
				if (continuation.isActive) continuation.resume(passwordInput.text.toString().toCharArray())
				passwordInput.text.clear()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(null) }
			.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
			.create()
		continuation.invokeOnCancellation { dialog.dismiss() }
		dialog.show()
		passwordInput.requestFocus()
	}
}
