package org.jellyfin.androidtv.ui.wallaby

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/** Ask for one available episode in season/episode order, never an entire series. */
fun firstEpisodeRequest(seriesId: UUID) = GetItemsRequest(
	parentId = seriesId,
	recursive = true,
	includeItemTypes = setOf(BaseItemKind.EPISODE),
	isMissing = false,
	sortBy = linkedSetOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
	sortOrder = setOf(SortOrder.ASCENDING),
	fields = setOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS),
	startIndex = 0,
	limit = 1,
	enableTotalRecordCount = false,
	enableImages = false,
)
