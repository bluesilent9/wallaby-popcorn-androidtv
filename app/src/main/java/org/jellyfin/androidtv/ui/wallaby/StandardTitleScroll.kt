package org.jellyfin.androidtv.ui.wallaby

/** Desired row-top offset: centered where possible, with no empty space at either end. */
fun standardTitleOffset(index: Int, total: Int, rowHeight: Int, viewport: Int, preferredTop: Int = (viewport - rowHeight) / 2): Int {
	if (total <= 0 || rowHeight <= 0 || viewport <= 0) return 0
	val top = index.coerceIn(0, total - 1).toLong() * rowHeight
	val center = preferredTop.coerceIn(0, (viewport - rowHeight).coerceAtLeast(0)).toLong()
	val maximum = (total.toLong() * rowHeight - viewport).coerceAtLeast(0)
	val scroll = (top - center).coerceIn(0, maximum)
	return (top - scroll).toInt()
}
