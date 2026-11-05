package org.jellyfin.androidtv.ui.wallaby

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StandardTitleScrollTest : FunSpec({
	test("at the top the first rows move down toward the center without blank leading space") {
		(0..3).map { standardTitleOffset(it, 100, 60, 420) } shouldBe listOf(0, 60, 120, 180)
	}
	test("subsequent rows remain centered through the middle of the library") {
		(4..96).forEach { standardTitleOffset(it, 100, 60, 420) shouldBe 180 }
	}
	test("at the bottom selection moves downward without blank trailing space") {
		(97..99).map { standardTitleOffset(it, 100, 60, 420) } shouldBe listOf(240, 300, 360)
	}
	test("a short library stays aligned to the top") {
		(0..2).map { standardTitleOffset(it, 3, 60, 420) } shouldBe listOf(0, 60, 120)
	}
	test("centering remains valid far beyond the former 100 title boundary") {
		standardTitleOffset(1120, 1171, 60, 420) shouldBe 180
		standardTitleOffset(1170, 1171, 60, 420) shouldBe 360
	}
	test("account for the toolbar so the preferred center can be the screen center") {
		standardTitleOffset(1, 100, 60, 420, preferredTop = 120) shouldBe 60
		standardTitleOffset(2, 100, 60, 420, preferredTop = 120) shouldBe 120
		standardTitleOffset(50, 100, 60, 420, preferredTop = 120) shouldBe 120
		standardTitleOffset(99, 100, 60, 420, preferredTop = 120) shouldBe 360
	}
})
