package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NARASININJA", "NarasiNinja", "id")
internal class NarasiNinjaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NARASININJA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("narasininja.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			if (!filter.query.isNullOrEmpty()) {
				// Search uses /search endpoint
				addPathSegment("search")
				addQueryParameter("s", filter.query)
				if (page > 1) {
					addPathSegment("page").addPathSegment(page.toString())
				}
			} else {
				// Browse listing
				addPathSegment("komik")
				addQueryParameter(
					"order",
					when (order) {
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.ALPHABETICAL_DESC -> "titlereverse"
						SortOrder.NEWEST -> "latest"
						SortOrder.POPULARITY -> "popular"
						SortOrder.UPDATED -> "update"
						else -> "update"
					},
				)

				filter.tags.forEach { tag ->
					if (tag.key.startsWith("-")) {
						addQueryParameter("genre[]", tag.key)
					} else {
						addQueryParameter("genre[]", tag.key)
					}
				}

				// Tags exclusion
				filter.tagsExclude.forEach { tag ->
					addQueryParameter("genre[]", "-${tag.key}")
				}

				filter.states.oneOrThrowIfMany()?.let {
					addQueryParameter(
						"status",
						when (it) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							else -> ""
						},
					)
				}

				filter.types.oneOrThrowIfMany()?.let {
					addQueryParameter(
						"type",
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							else -> ""
						},
					)
				}

				if (page > 1) {
					addQueryParameter("page", page.toString())
				}
			}
		}.build()

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select(".listupd .bs .bsx").mapNotNull { el ->
			val linkEl = el.selectFirst("a") ?: return@mapNotNull null
			val href = linkEl.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = el.selectFirst("div.tt a")?.text()
				?: linkEl.attr("title").orEmpty()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = el.selectFirst("img.ts-post-image, img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		// Extract metadata from the info table
		val infoTable = doc.selectFirst("table.infotable")

		val state = infoTable?.selectFirst("td:contains(Status)")?.let { statusTd ->
			val value = statusTd.nextElementSibling()?.text()?.trim()?.lowercase()
			when (value) {
				"ongoing", "berlangsung" -> MangaState.ONGOING
				"completed", "tamat" -> MangaState.FINISHED
				else -> null
			}
		}

		val author = infoTable?.selectFirst("td:contains(Author)")?.let { authorTd ->
			authorTd.nextElementSibling()?.text()?.trim()
		}

		// Description from entry-content
		val description = doc.selectFirst("div.entry-content.entry-content-single")
			?.html()

		// Genre tags
		val tags = doc.select(".seriestugenre a").mapToSet {
			val key = it.attrAsRelativeUrlOrNull("href")
				?.removePrefix("/genre/")
				?.removeSuffix("/")
				?: it.text()
			MangaTag(
				key = key,
				title = it.text().trim(),
				source = source,
			)
		}

		// Chapters from the chapter list
		val chapterDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
		val chapters = doc.select("#chapterlist ul > li").mapChapters(reversed = true) { index, el ->
			val chapterLink = el.selectFirst("a") ?: return@mapChapters null
			val url = chapterLink.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val dateText = el.selectFirst(".chapterdate")?.text()
			MangaChapter(
				id = generateUid(url),
				title = el.selectFirst(".chapternum")?.textOrNull(),
				number = index + 1f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = chapterDateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}

		// Cover image (try multiple selectors for Themesia themes)
		val coverUrl = doc.selectFirst(
			".seriestucontl .thumb img, .bigcover img, .ts-post-image",
		)?.src() ?: doc.selectFirst("img.wp-post-image")?.src()

		return manga.copy(
			coverUrl = coverUrl ?: manga.coverUrl,
			authors = setOfNotNull(author),
			state = state,
			description = description,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// Primary: images from the reader area
		return doc.select("img.ts-main-image").mapNotNull {
			val url = it.attrAsAbsoluteUrlOrNull("src") ?: return@mapNotNull null
			if (url.contains("data:", ignoreCase = true)) return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}.ifEmpty {
			// Fallback: try other common image containers
			doc.select(".reading-content img, .reader-area img, #reader img, .chapterbody img")
				.mapNotNull { el ->
					val url = el.attrAsAbsoluteUrlOrNull("src")
						?: el.attrAsAbsoluteUrlOrNull("data-src")
						?: return@mapNotNull null
					if (url.contains("data:", ignoreCase = true)) return@mapNotNull null
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("/komik/".toAbsoluteUrl(domain)).parseHtml()

		// Themesia themes expose genres in the sidebar filter: ul.genrez > li
		val genreElements = doc.select("ul.genrez > li, .genre-filter a, .widget_tag_cloud a, .seriestugenre a")
		if (genreElements.isNotEmpty()) {
			return genreElements.mapToSet { el ->
				val link = el.selectFirst("a") ?: el
				MangaTag(
					key = link.attrAsRelativeUrlOrNull("href")
						?.removePrefix("/genre/")
						?.removeSuffix("/")
						?: link.text(),
					title = link.text().trim(),
					source = source,
				)
			}
		}

		// Fallback: extract from a known manga detail page
		val firstManga = doc.selectFirst(".listupd a")?.attrAsRelativeUrlOrNull("href")
		if (firstManga != null) {
			val detailDoc = webClient.httpGet(firstManga.toAbsoluteUrl(domain)).parseHtml()
			return detailDoc.select(".seriestugenre a").mapToSet {
				MangaTag(
					key = it.attrAsRelativeUrlOrNull("href")
						?.removePrefix("/genre/")
						?.removeSuffix("/")
						?: it.text(),
					title = it.text().trim(),
					source = source,
				)
			}
		}

		return emptySet()
	}
}
