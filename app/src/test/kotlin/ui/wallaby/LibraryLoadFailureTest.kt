package org.jellyfin.androidtv.ui.wallaby

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException

class LibraryLoadFailureTest : FunSpec({
	test("authorization failures distinguish expired sign-in and forbidden libraries") {
		libraryLoadFailureMessage(InvalidStatusException(401)).contains("Sign in again") shouldBe true
		libraryLoadFailureMessage(InvalidStatusException(403)).contains("permission") shouldBe true
	}
	test("internal UI failures are never presented as SSH outages") {
		val message = libraryLoadFailureMessage(IllegalStateException("Rendering failed"))
		message.contains("internal") shouldBe true
		message.contains("SSH") shouldBe false
	}
	test("invalid data and timeouts have different retry advice") {
		libraryLoadFailureMessage(InvalidContentException()).contains("could not read") shouldBe true
		libraryLoadFailureMessage(TimeoutException()).contains("too long") shouldBe true
	}
})
