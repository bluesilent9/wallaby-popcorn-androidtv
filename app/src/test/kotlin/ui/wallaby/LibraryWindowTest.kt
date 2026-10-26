package org.jellyfin.androidtv.ui.wallaby

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibraryWindowTest : FunSpec({
	test("all view types use a full library index beyond the former 100 boundary") {
		LibraryView.entries.forEach { _ ->
			val window = LibraryWindow<Int>()
			window.put(22, (1100 until 1150).toList(), 1171, 1120)
			window.total shouldBe 1171
			window[1120] shouldBe 1120
		}
	}
	test("browsing the complete library retains at most eight pages") {
		val window = LibraryWindow<Int>()
		for (page in 0..23) {
			val start = page * 50
			window.put(page, (start until minOf(start + 50, 1171)).toList(), 1171, start)
			(window.retainedItems <= 400) shouldBe true
		}
		window[1170] shouldBe 1170
		window[0] shouldBe null
	}
	test("prefetch eviction protects the selected page") {
		val window = LibraryWindow<Int>(10, 3)
		window.put(0, (0..9).toList(), 1000, 3)
		for (page in 1..8) window.put(page, (page * 10 until page * 10 + 10).toList(), 1000, 3)
		window[3] shouldBe 3
		window.retainedItems shouldBe 30
	}
	test("reset discards pages and counts when filters change") {
		val window = LibraryWindow<Int>()
		window.put(0, (0..49).toList(), 1171, 0)
		window.clear()
		window.total shouldBe 0
		window.retainedItems shouldBe 0
		window.hasPage(0) shouldBe false
	}
})
