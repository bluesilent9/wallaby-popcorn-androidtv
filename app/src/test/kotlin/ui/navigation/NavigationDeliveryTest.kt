package org.jellyfin.androidtv.ui.navigation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NavigationDeliveryTest : FunSpec({
	test("resuming a host does not repeat the last Back action") {
		val delivery = NavigationDelivery()
		val event = NavigationEvent(NavigationAction.GoBack)
		var pops = 0
		delivery.deliver(event) { pops++ }
		delivery.deliver(event) { pops++ }
		pops shouldBe 1
	}
	test("two intentional Back presses both navigate") {
		val delivery = NavigationDelivery()
		var pops = 0
		delivery.deliver(NavigationEvent(NavigationAction.GoBack)) { pops++ }
		delivery.deliver(NavigationEvent(NavigationAction.GoBack)) { pops++ }
		pops shouldBe 2
	}
	test("restored history also restores the consumed navigation identity") {
		val event = NavigationEvent(NavigationAction.GoBack)
		val restored = NavigationDelivery(event.id)
		var pops = 0
		restored.deliver(event) { pops++ }
		pops shouldBe 0
	}
	test("a failed transaction is not marked consumed") {
		val delivery = NavigationDelivery()
		val event = NavigationEvent(NavigationAction.GoBack)
		shouldThrow<IllegalStateException> {
			delivery.deliver(event) { error("Host not ready") }
		}
		var pops = 0
		delivery.deliver(event) { pops++ }
		pops shouldBe 1
	}
})
