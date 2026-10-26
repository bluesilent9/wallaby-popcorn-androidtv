package org.jellyfin.androidtv.ui.wallaby

/** One bounded cache for all library views. Positions refer to the full server result. */
class LibraryWindow<T>(val pageSize: Int = 50, private val maxPages: Int = 8) {
	init { require(pageSize > 0 && maxPages >= 3) }
	private val pages = LinkedHashMap<Int, List<T>>(maxPages, 0.75f, true)
	var total: Int = 0
		private set
	fun page(position: Int) = position.coerceAtLeast(0) / pageSize
	fun hasPage(page: Int) = pages.containsKey(page)
	operator fun get(position: Int): T? = pages[page(position)]?.getOrNull(position % pageSize)
	fun clear() { pages.clear(); total = 0 }
	fun put(page: Int, items: List<T>, totalCount: Int, anchor: Int) {
		total = totalCount.coerceAtLeast(0)
		pages[page] = items.take(pageSize)
		while (pages.size > maxPages) {
			val anchorPage = this.page(anchor)
			val evict = pages.keys.firstOrNull { it != anchorPage && it != page } ?: pages.keys.first()
			pages.remove(evict)
		}
	}
	val retainedItems get() = pages.values.sumOf { it.size }
}

enum class LibraryView(val title: String) { STANDARD("Standard View"), GRID("Grid View"), LIST("List View") }
