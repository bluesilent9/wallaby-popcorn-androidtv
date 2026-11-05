package org.jellyfin.androidtv.ui.wallaby

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

class FirstEpisodeRequestTest : FunSpec({
	test("sample the earliest available episode in season and episode order, not title order") {
		val series = UUID.randomUUID()
		val request = firstEpisodeRequest(series)
		request.parentId shouldBe series
		request.recursive shouldBe true
		request.includeItemTypes shouldBe setOf(BaseItemKind.EPISODE)
		request.isMissing shouldBe false
		request.sortBy?.toList() shouldBe listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)
		request.sortOrder shouldBe setOf(SortOrder.ASCENDING)
	}
	test("load one episode with file streams rather than the entire series") {
		val request = firstEpisodeRequest(UUID.randomUUID())
		request.startIndex shouldBe 0
		request.limit shouldBe 1
		request.fields shouldBe setOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS)
		request.enableTotalRecordCount shouldBe false
		request.enableImages shouldBe false
	}
})
