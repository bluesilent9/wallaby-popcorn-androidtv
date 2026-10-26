package org.jellyfin.androidtv.ui.wallaby

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.browsing.BrowsingUtils
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.android.ext.android.inject
import java.util.Locale

/** Native TV library: one query, cache, selection and view preference for all three layouts. */
class WallabyLibraryFragment : Fragment() {
	private val api by inject<ApiClient>()
	private val navigation by inject<NavigationRepository>()
	private val backgrounds by inject<BackgroundService>()
	private val preferences by inject<PreferencesRepository>()
	private val sessions by inject<SessionRepository>()
	private lateinit var folder: BaseItemDto
	private lateinit var libraryPrefs: LibraryPreferences
	private lateinit var list: RecyclerView
	private lateinit var panel: LinearLayout
	private lateinit var status: TextView
	private lateinit var viewButton: Button
	private lateinit var favoriteButton: Button
	private lateinit var unwatchedButton: Button
	private lateinit var sortButton: Button
	private lateinit var retryButton: Button
	private val window = LibraryWindow<BaseItemDto>()
	private val requests = mutableMapOf<Int, Job>()
	private var detailJob: Job? = null
	private var generation = 0
	private var selected = 0
	private var viewMode = LibraryView.STANDARD
	private var letter: String? = null
	private var richItem: BaseItemDto? = null
	private var shownItem: java.util.UUID? = null
	private var restoreFocus = true
	private var savedScroll: android.os.Parcelable? = null
	private var rows: Rows? = null
	private val gold = Color.rgb(246, 198, 106)
	private val ink = Color.rgb(255, 244, 223)
	private val sorts = listOf(
		"Title" to ItemSortBy.SORT_NAME, "Year" to ItemSortBy.PRODUCTION_YEAR,
		"Date added" to ItemSortBy.DATE_CREATED, "Rating" to ItemSortBy.COMMUNITY_RATING,
	)
	private val localPrefs get() = requireContext().getSharedPreferences("wallaby_library", 0)
	private val preferenceKey get() = "${sessions.currentSession.value?.serverId}/${sessions.currentSession.value?.userId}/${folder.id}"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		folder = Json.decodeFromString(requireArguments().getString(Extras.Folder)!!)
		libraryPrefs = preferences.getLibraryPreferences(folder.displayPreferencesId ?: folder.id.toString())
		viewMode = runCatching { LibraryView.valueOf(localPrefs.getString("$preferenceKey/view", "STANDARD")!!) }.getOrDefault(LibraryView.STANDARD)
		selected = savedInstanceState?.getInt("wallaby_selection") ?: localPrefs.getInt("$preferenceKey/selection", 0)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putInt("wallaby_selection", selected)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
		val root = LinearLayout(requireContext()).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(30))
		}
		val toolbar = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
		toolbar.addView(label(folder.name.orEmpty(), 23f), LinearLayout.LayoutParams(0, dp(52), 1f))
		viewButton = control(viewMode.title) { chooseView() }
		sortButton = control("Sort") { chooseSort() }
		favoriteButton = control("Favorites") { toggleFilter(LibraryPreferences.filterFavoritesOnly) }
		unwatchedButton = control("Unwatched") { toggleFilter(LibraryPreferences.filterUnwatchedOnly) }
		val letters = control("A–Z") { chooseLetter() }
		listOf(viewButton, sortButton, favoriteButton, unwatchedButton, letters).forEach { toolbar.addView(it) }
		root.addView(toolbar)
		status = label("", 14f)
		root.addView(status)
		retryButton = control("Retry loading") { loadPage(if (window.total == 0) 0 else window.page(selected), force = true) }.apply { visibility = View.GONE }
		root.addView(retryButton)
		val workspace = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		list = RecyclerView(requireContext()).apply {
			id = View.generateViewId()
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			itemAnimator = null
			setHasFixedSize(true)
			setItemViewCacheSize(12)
			addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
					val manager = layoutManager as? LinearLayoutManager ?: return
					loadNear(manager.findFirstVisibleItemPosition().coerceAtLeast(0))
					loadNear(manager.findLastVisibleItemPosition().coerceAtLeast(0))
				}
			})
		}
		panel = LinearLayout(requireContext()).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20))
			background = rounded(0xDF261636.toInt())
		}
		val details = ScrollView(requireContext()).apply {
			isFillViewport = true
			addView(panel)
		}
		workspace.addView(list, LinearLayout.LayoutParams(0, -1, 0.43f))
		workspace.addView(details, LinearLayout.LayoutParams(0, -1, 0.57f).apply { marginStart = dp(28) })
		root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
		applyView()
		updateControls()
		return root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		restoreFocus = true
		if (window.total == 0) loadPage(0) else {
			list.scrollToPosition(selected.coerceIn(0, (window.total - 1).coerceAtLeast(0)))
			loadNear(selected)
			list.post { focusSelection() }
		}
		richItem?.let { renderDetails(it) }
	}

	override fun onResume() {
		super.onResume()
		backgrounds.setBackground(richItem ?: window[selected])
	}

	override fun onDestroyView() {
		generation++
		savedScroll = list.layoutManager?.onSaveInstanceState()
		localPrefs.edit().putInt("$preferenceKey/selection", selected).apply()
		requests.values.toList().forEach { it.cancel() }
		requests.clear()
		detailJob?.cancel()
		list.adapter = null
		list.recycledViewPool.clear()
		panel.removeAllViews()
		rows = null
		super.onDestroyView()
	}

	private fun applyView() {
		val standard = viewMode == LibraryView.STANDARD
		val details = panel.parent as View
		details.visibility = if (standard) View.VISIBLE else View.GONE
		(list.layoutParams as LinearLayout.LayoutParams).weight = if (standard) 0.43f else 1f
		list.layoutManager = if (viewMode == LibraryView.GRID) GridLayoutManager(requireContext(), 5) else LinearLayoutManager(requireContext())
		rows = Rows().also { list.adapter = it }
		list.layoutManager?.onRestoreInstanceState(savedScroll)
		list.scrollToPosition(selected)
		viewButton.text = viewMode.title
	}

	private fun chooseView() {
		AlertDialog.Builder(requireContext()).setTitle("Library view")
			.setSingleChoiceItems(LibraryView.entries.map { it.title }.toTypedArray(), viewMode.ordinal) { dialog, which ->
				viewMode = LibraryView.entries[which]
				localPrefs.edit().putString("$preferenceKey/view", viewMode.name).apply()
				savedScroll = null
				applyView()
				richItem?.let { renderDetails(it) }
				dialog.dismiss()
				list.post { focusSelection() }
			}.show()
	}

	private fun chooseSort() {
		val options = sorts.flatMap { (name, _) -> listOf("$name ↑", "$name ↓") }
		AlertDialog.Builder(requireContext()).setTitle("Sort library").setItems(options.toTypedArray()) { _, choice ->
			libraryPrefs[LibraryPreferences.sortBy] = sorts[choice / 2].second
			libraryPrefs[LibraryPreferences.sortOrder] = if (choice % 2 == 0) SortOrder.ASCENDING else SortOrder.DESCENDING
			persistAndReload()
		}.show()
	}

	private fun chooseLetter() {
		val options = listOf("All titles") + ('A'..'Z').map(Char::toString)
		AlertDialog.Builder(requireContext()).setTitle("Title starts with").setItems(options.toTypedArray()) { _, choice ->
			letter = options[choice].takeIf { choice != 0 }
			persistAndReload()
		}.show()
	}

	private fun toggleFilter(preference: org.jellyfin.preference.Preference<Boolean>) {
		libraryPrefs[preference] = !libraryPrefs[preference]
		persistAndReload()
	}

	private fun persistAndReload() {
		generation++
		requests.values.toList().forEach { it.cancel() }
		requests.clear()
		detailJob?.cancel()
		window.clear()
		selected = 0
		richItem = null
		shownItem = null
		restoreFocus = true
		panel.removeAllViews()
		rows?.notifyDataSetChanged()
		updateControls()
		viewLifecycleOwner.lifecycleScope.launch { libraryPrefs.commit() }
		loadPage(0)
	}

	private fun updateControls() {
		favoriteButton.text = if (libraryPrefs[LibraryPreferences.filterFavoritesOnly]) "✓ Favorites" else "Favorites"
		unwatchedButton.text = if (libraryPrefs[LibraryPreferences.filterUnwatchedOnly]) "✓ Unwatched" else "Unwatched"
		sortButton.text = "Sort"
	}

	private fun loadNear(position: Int) {
		val page = window.page(position)
		loadPage(page)
		if (page > 0) loadPage(page - 1)
		if ((page + 1) * window.pageSize < window.total) loadPage(page + 1)
	}

	private fun loadPage(page: Int, force: Boolean = false) {
		if (!isAdded || view == null || page < 0 || requests.containsKey(page) || (!force && window.hasPage(page))) return
		// At most three requests in flight, shared by every layout and canceled on exit/filter changes.
		if (requests.size >= 3) return
		val ticket = generation
		val request = BrowsingUtils.createBrowseGridItemsRequest(folder).copy(
			fields = ItemRepository.browseFields,
			startIndex = page * window.pageSize,
			limit = window.pageSize,
			enableTotalRecordCount = true,
			nameStartsWith = letter,
			sortBy = setOf(libraryPrefs[LibraryPreferences.sortBy], ItemSortBy.SORT_NAME),
			sortOrder = setOf(libraryPrefs[LibraryPreferences.sortOrder]),
			filters = buildSet {
				if (libraryPrefs[LibraryPreferences.filterFavoritesOnly]) add(ItemFilter.IS_FAVORITE)
				if (libraryPrefs[LibraryPreferences.filterUnwatchedOnly]) add(ItemFilter.IS_UNPLAYED)
			},
		)
		requests[page] = viewLifecycleOwner.lifecycleScope.launch {
			var succeeded = false
			try {
				val result = api.itemsApi.getItems(request).content
				if (ticket != generation) return@launch
				val previousTotal = window.total
				window.put(page, result.items.orEmpty(), result.totalRecordCount ?: 0, selected)
				succeeded = true
				status.text = if (window.total == 0) "No matching titles. Change filters to see more." else ""
				retryButton.visibility = View.GONE
				if (previousTotal != window.total) rows?.notifyDataSetChanged()
				else rows?.notifyItemRangeChanged(page * window.pageSize, result.items.orEmpty().size)
				if (previousTotal == 0 && window.total > 0) {
					selected = selected.coerceIn(0, window.total - 1)
					list.scrollToPosition(selected)
					loadNear(selected)
				}
				if (window.page(selected) == page) {
					select(selected)
					list.post { if (restoreFocus) focusSelection() }
				}
			} catch (cancel: CancellationException) {
				throw cancel
			} catch (_: Exception) {
				if (ticket == generation) {
					status.text = "Could not load titles. Check the server/SSH connection, then retry."
					retryButton.visibility = View.VISIBLE
				}
			} finally {
				if (ticket == generation) {
					requests.remove(page)
					if (succeeded && window.total > 0 && window[selected] == null) loadPage(window.page(selected))
				}
			}
		}
	}

	private fun focusSelection() {
		val holder = list.findViewHolderForAdapterPosition(selected)
		if (holder != null) { holder.itemView.requestFocus(); restoreFocus = false }
	}

	private fun select(position: Int) {
		selected = position
		val item = window[position] ?: run { loadNear(position); return }
		if (shownItem == item.id) return
		shownItem = item.id
		richItem = item
		backgrounds.setBackground(item)
		detailJob?.cancel()
		renderDetails(item)
		detailJob = viewLifecycleOwner.lifecycleScope.launch {
			delay(180)
			try {
				val full = api.userLibraryApi.getItem(item.id).content
				if (shownItem == full.id) { richItem = full; renderDetails(full) }
			} catch (cancel: CancellationException) {
				throw cancel
			} catch (_: Exception) { /* Keep summary visible; details remain accessible on retry. */ }
		}
	}

	private fun renderDetails(item: BaseItemDto) {
		if (viewMode != LibraryView.STANDARD) return
		val restoreAction = panel.findViewWithTag<View>("details-action")?.hasFocus() == true
		panel.removeAllViews()
		val heading = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
		val summary = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
		summary.addView(label(item.name.orEmpty(), 27f))
		val metadata = listOfNotNull(item.productionYear?.toString(), item.officialRating, item.runTimeTicks?.let { "${it / 600000000} min" }, item.communityRating?.let { "★ %.1f".format(it) })
		summary.addView(label(metadata.joinToString(" · "), 14f).apply { setTextColor(gold) })
		summary.addView(label(item.genres.orEmpty().joinToString(" · "), 14f))
		val overview = Html.fromHtml(item.overview.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString()
		summary.addView(label(overview, 16f).apply { maxLines = 9 })
		heading.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
		heading.addView(AsyncImageView(requireContext()).apply {
			scaleType = ImageView.ScaleType.FIT_CENTER
			load(item.itemImages[ImageType.PRIMARY]?.getUrl(api, maxHeight = 600))
		}, LinearLayout.LayoutParams(dp(112), dp(170)).apply { marginStart = dp(14) })
		panel.addView(heading)
		val source = item.mediaSources?.firstOrNull()
		val streams = source?.mediaStreams ?: item.mediaStreams.orEmpty()
		val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
		val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == source?.defaultAudioStreamIndex }
			?: streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }
			?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
		val resolution = when {
			(video?.width ?: 0) >= 3840 || (video?.height ?: 0) >= 2160 -> "4K UHD"
			(video?.width ?: 0) >= 2560 || (video?.height ?: 0) > 1080 -> "1440p"
			(video?.width ?: 0) >= 1920 || (video?.height ?: 0) > 720 -> "1080p"
			(video?.width ?: 0) >= 1280 || (video?.height ?: 0) > 576 -> "720p"
			video?.height != null -> "${video.height}p"
			else -> "Video details unavailable"
		}
		val channels = when (audio?.channels) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1 Surround"; 8 -> "7.1 Surround"; else -> audio?.channelLayout.orEmpty() }
		val badges = LinearLayout(requireContext())
		badges.addView(badge("▣ $resolution · ${codec(video?.codec)}"))
		val quality = listOfNotNull(audio?.bitRate?.let { "${it / 1000} kb/s" }, audio?.sampleRate?.let { "${it / 1000} kHz" }).joinToString(" · ")
		badges.addView(badge("♫ ${codec(audio?.codec)} · $channels\n$quality"))
		panel.addView(badges)
		val languages = streams.filter { it.type == MediaStreamType.SUBTITLE }.map { language(it.language) }.distinct()
		val subtitleRow = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
		subtitleRow.addView(label("Subtitles: ", 14f))
		if (languages.isEmpty()) subtitleRow.addView(label("None listed", 14f))
		languages.forEach { code -> subtitleRow.addView(badge(code).apply { setTextColor(ink); background = rounded(0xB04D3568.toInt() xor (code.hashCode() and 0x001F1F1F)) }) }
		panel.addView(subtitleRow)
		// SDK 1.8.12 does not expose original-language metadata. Never substitute file/metadata-UI language.
		panel.addView(label("Original language: Not provided by server", 14f))
		panel.addView(control("Open details & playback") { navigation.navigate(Destinations.itemDetails(item.id)) }.apply {
			setOnKeyListener { _, key, event ->
				if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) { focusSelection(); true } else false
			}
		})
		if (restoreAction) panel.findViewWithTag<View>("details-action")?.requestFocus()
	}

	private inner class Rows : RecyclerView.Adapter<Holder>() {
		init { setHasStableIds(true) }
		override fun getItemId(position: Int) = position.toLong()
		override fun getItemCount() = window.total
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val cell = LinearLayout(requireContext()).apply {
				orientation = if (viewMode == LibraryView.GRID) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				isFocusable = true
				isClickable = true
				setPadding(dp(10))
				background = focusBackground()
				layoutParams = RecyclerView.LayoutParams(-1, dp(if (viewMode == LibraryView.GRID) 236 else 54)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
			}
			val image = AsyncImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
			if (viewMode != LibraryView.STANDARD) cell.addView(image, LinearLayout.LayoutParams(if (viewMode == LibraryView.GRID) -1 else dp(42), if (viewMode == LibraryView.GRID) dp(166) else -1))
			val title = label("", 16f).apply { maxLines = 2; setTypeface(null, Typeface.BOLD) }
			val year = label("", 14f)
			if (viewMode == LibraryView.GRID) {
				cell.addView(title); cell.addView(year)
			} else {
				cell.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); cell.addView(year)
			}
			return Holder(cell, title, year, image)
		}
		override fun onBindViewHolder(holder: Holder, position: Int) {
			val item = window[position]
			holder.title.text = item?.name ?: "Loading title…"
			holder.year.text = item?.productionYear?.toString().orEmpty()
			if (viewMode != LibraryView.STANDARD) holder.image.load(item?.itemImages?.get(ImageType.PRIMARY)?.getUrl(api, maxHeight = 400))
			holder.itemView.setOnFocusChangeListener { _, focused ->
				if (focused && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
					select(holder.bindingAdapterPosition); loadNear(holder.bindingAdapterPosition)
				}
			}
			holder.itemView.setOnClickListener {
				val position = holder.bindingAdapterPosition
				if (position == RecyclerView.NO_POSITION) return@setOnClickListener
				val current = window[position]
				if (current != null) navigation.navigate(Destinations.itemDetails(current.id))
			}
			holder.itemView.setOnKeyListener { _, key, event ->
				if (event.action == KeyEvent.ACTION_DOWN && viewMode == LibraryView.STANDARD) when (key) {
					KeyEvent.KEYCODE_DPAD_RIGHT -> { panel.findViewWithTag<View>("details-action")?.requestFocus(); true }
					KeyEvent.KEYCODE_DPAD_LEFT -> { viewButton.requestFocus(); true }
					else -> false
				} else false
			}
		}
	}
	private class Holder(view: View, val title: TextView, val year: TextView, val image: AsyncImageView) : RecyclerView.ViewHolder(view)
	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
	private fun label(value: String, size: Float) = TextView(requireContext()).apply { text = value; textSize = size; setTextColor(ink); setPadding(dp(3)) }
	private fun control(value: String, action: () -> Unit) = Button(requireContext()).apply {
		text = value; textSize = 14f; isAllCaps = false; minHeight = dp(40); minimumHeight = dp(40)
		setTextColor(ink); background = focusBackground(); setPadding(dp(10), dp(6), dp(10), dp(6))
		setOnClickListener { action() }
		if (value == "Open details & playback") tag = "details-action"
	}
	private fun badge(value: String) = label(value, 14f).apply { setPadding(dp(9)); background = rounded(0xB0160D22.toInt()) }
	private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat(); setStroke(dp(1), 0x60F6C66A) }
	private fun focusBackground() = StateListDrawable().apply {
		addState(intArrayOf(android.R.attr.state_focused), rounded(0xEF624475.toInt()))
		addState(intArrayOf(), rounded(0x33160D22))
	}
	private fun codec(value: String?) = when (value?.lowercase()) { "h264" -> "H.264"; "hevc" -> "HEVC"; "ac3" -> "Dolby Digital"; "eac3" -> "Dolby Digital Plus"; "truehd" -> "Dolby TrueHD"; else -> value?.uppercase().orEmpty() }
	private fun language(value: String?): String {
		if (value.isNullOrBlank() || value == "und") return "?"
		val locale = Locale.forLanguageTag(value)
		val code = if (locale.language.length == 2) locale.language else Locale.getAvailableLocales().firstOrNull { runCatching { it.getISO3Language() == value }.getOrDefault(false) && it.language.length == 2 }?.language
		return code?.replaceFirstChar { it.uppercase() } ?: "?"
	}
}
