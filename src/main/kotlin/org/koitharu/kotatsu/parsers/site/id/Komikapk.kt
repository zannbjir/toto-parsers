package org.koitharu.kotatsu.parsers.site.id

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikApk", "id")
internal class Komikapk(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKAPK, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("komikapk.app")

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaFilterCapabilities
		get() = MangaFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override val availableSortOrders: Set<SortOrder>
		get() = sortOrders

	// Mapping sort order ke URL
	private val sortOrderMap = mapOf(
		SortOrder.UPDATED to "terbaru",
		SortOrder.NEWEST to "terbaru",
		SortOrder.POPULARITY to "populer",
		SortOrder.ALPHABETICAL to "a-z",
	)

	// Genre/Tag mapping
	private val tagsMap = ArrayMap<String, String>().apply {
		put("action", "action")
		put("adventure", "adventure")
		put("comedy", "comedy")
		put("cooking", "cooking")
		put("crime", "crime")
		put("demons", "demons")
		put("doujinshi", "doujinshi")
		put("drama", "drama")
		put("ecchi", "ecchi")
		put("fantasy", "fantasy")
		put("game", "game")
		put("gender-bender", "gender-bender")
		put("gore", "gore")
		put("harem", "harem")
		put("historical", "historical")
		put("horror", "horror")
		put("isekai", "isekai")
		put("josei", "josei")
		put("magic", "magic")
		put("martial-arts", "martial-arts")
		put("mature", "mature")
		put("mecha", "mecha")
		put("medical", "medical")
		put("military", "military")
		put("monster", "monster")
		put("music", "music")
		put("mystery", "mystery")
		put("one-shot", "one-shot")
		put("parody", "parody")
		put("police", "police")
		put("psychological", "psychological")
		put("romance", "romance")
		put("samurai", "samurai")
		put("school-life", "school-life")
		put("sci-fi", "sci-fi")
		put("seinen", "seinen")
		put("shoujo", "shoujo")
		put("shoujo-ai", "shoujo-ai")
		put("shounen", "shounen")
		put("shounen-ai", "shounen-ai")
		put("slice-of-life", "slice-of-life")
		put("smut", "smut")
		put("sports", "sports")
		put("super-power", "super-power")
		put("supernatural", "supernatural")
		put("survival", "survival")
		put("thriller", "thriller")
		put("time-travel", "time-travel")
		put("tragedy", "tragedy")
		put("vampire", "vampire")
		put("webtoon", "webtoon")
		put("yuri", "yuri")
	}

	// Type mapping
	private val typesMap = mapOf(
		"semua" to "semua",
		"manga" to "manga",
		"manhwa" to "manhwa",
		"manhua" to "manhua",
	)

	override suspend fun getFilterOptions(): MangaFilterOptions {
		return MangaFilterOptions(
			availableTags = tagsMap.keys.map { MangaTag(title = it.replace("-", " ").capitalize(), key = it, source = source) }.toSet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = setOf(
				MangaContentType.MANGA,
				MangaContentType.MANHWA,
				MangaContentType.MANHUA,
			),
		)
	}

	override suspend fun getList(
		offset: Int,
		query: String?,
		tags: Set<MangaTag>?,
		sortOrder: SortOrder?,
		state: MangaState?,
		contentType: MangaContentType?,
	): List<Manga> {
		val page = (offset / pageSize) + 1
		val url = buildListUrl(page, query, tags, sortOrder, state, contentType)
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun buildListUrl(
		page: Int,
		query: String?,
		tags: Set<MangaTag>?,
		sortOrder: SortOrder?,
		state: MangaState?,
		contentType: MangaContentType?,
	): String {
		val domain = domain

		// Search URL
		if (!query.isNullOrBlank()) {
			return "https://$domain/pencarian?q=${query.urlEncoded()}&page=$page"
		}

		// Build pustaka URL
		val type = when (contentType) {
			MangaContentType.MANGA -> "manga"
			MangaContentType.MANHWA -> "manhwa"
			MangaContentType.MANHUA -> "manhua"
			else -> "semua"
		}

		val tag = tags?.firstOrNull()?.key ?: "semua"
		val sort = sortOrderMap[sortOrder] ?: "terbaru"

		return "https://$domain/pustaka/$type/$tag/$sort/$page"
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val mangaList = mutableListOf<Manga>()

		// Homepage manga grid
		doc.select("a[href^='/komik/']").forEach { element ->
			val href = element.attr("href")
			if (href.startsWith("/komik/")) {
				val slug = href.removePrefix("/komik/").removeSuffix("/")

				// Get cover
				val coverImg = element.selectFirst("img[src*='cdn-guard']")
				val coverUrl = coverImg?.absUrl("src") ?: coverImg?.attr("src") ?: ""

				// Get title
				val titleEl = element.selectFirst("div.font-display")
				val title = titleEl?.text()?.trim() ?: slug.replace("-", " ").capitalize()

				// Get type (manga/manhwa/manhua)
				val typeEl = element.selectFirst("div.bg-primary, div.bg-secondary, div.bg-neutral")
				val typeText = typeEl?.text()?.lowercase() ?: ""

				mangaList.add(
					Manga(
						id = generateUid(slug),
						title = title,
						altTitle = null,
						url = href,
						publicUrl = "https://${domain}$href",
						rating = RATING_UNKNOWN,
						isNsfw = false,
						coverUrl = coverUrl,
						tags = emptySet(),
						state = MangaState.ONGOING,
						author = null,
						source = source,
					),
				)
			}
		}

		// Remove duplicates
		return mangaList.distinctBy { it.id }
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url).parseHtml()

		// Title
		val title = doc.selectFirst("h1.font-label")?.text()?.trim() ?: manga.title

		// Cover
		val cover = doc.selectFirst("img.h-\\[200px\\]")?.absUrl("src") ?: manga.coverUrl

		// Description
		val description = doc.selectFirst("div.font-display.mt-5.text-center")?.text()?.trim()

		// Tags/Genres
		val tags = doc.select("a[href^='/pustaka/semua/'][href*='/terbaru/']")
			.mapNotNull { a ->
				val tagSlug = a.attr("href")
					.split("/")
					.getOrNull(3) ?: return@mapNotNull null
				MangaTag(
					title = a.text().trim().capitalize(),
					key = tagSlug,
					source = source,
				)
			}
			.toSet()

		// State - check for completed status in description or title
		val state = when {
			doc.html().contains("completed", ignoreCase = true) ||
			doc.html().contains("tamat", ignoreCase = true) ||
			doc.selectFirst("div:contains(Completed)") != null -> MangaState.FINISHED
			else -> MangaState.ONGOING
		}

		// Author
		val author = doc.selectFirst("div.font-label:contains(BY:) + span, div:contains(BY:) span")
			?.text()?.trim()

		// Type (check from badges)
		val isNsfw = doc.select("a.btn-soft").any {
			it.text().lowercase() in listOf("mature", "smut", "ecchi", "adult")
		}

		// Chapters
		val chapters = async {
			val chapterList = mutableListOf<MangaChapter>()

			doc.select("a[href^='/komik/'][href*='/kmapk/'], a[href^='/komik/'][href*='/chapter']").forEach { a ->
				val chapterUrl = a.attr("href")
				val chapterSlug = chapterUrl.split("/").lastOrNull() ?: return@forEach

				val chapterName = a.selectFirst("div")?.text()?.trim() ?: "Chapter $chapterSlug"

				// Parse chapter number
				val number = chapterSlug.toFloatOrNull() ?: parseChapterNumber(chapterName)

				chapterList.add(
					MangaChapter(
						id = generateUid(chapterUrl),
						name = chapterName,
						number = number,
						url = chapterUrl,
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					),
				)
			}

			// Sort by chapter number descending
			chapterList.sortedByDescending { it.number }
		}

		manga.copy(
			title = title,
			altTitle = null,
			description = description,
			coverUrl = cover,
			tags = tags,
			state = state,
			author = author,
			isNsfw = isNsfw,
			chapters = chapters.await(),
		)
	}

	private fun parseChapterNumber(name: String): Float {
		val regex = Regex("""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		val match = regex.find(name)
		return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()

		val pages = mutableListOf<MangaPage>()

		// Find all images in chapter
		doc.select("img[src*='komikapk-chapter'], img[src*='cdn-guard.com/komikapk-chapter']").forEach { img ->
			val imageUrl = img.absUrl("src")

			if (imageUrl.isNotBlank()) {
				pages.add(
					MangaPage(
						id = generateUid(imageUrl),
						url = imageUrl,
						preview = null,
						source = source,
					),
				)
			}
		}

		// Also check for images in section
		doc.select("section img[src*='.jpg'], section img[src*='.png'], section img[src*='.webp']").forEach { img ->
			val imageUrl = img.absUrl("src")

			if (imageUrl.isNotBlank() && imageUrl.contains("cdn-guard")) {
				pages.add(
					MangaPage(
						id = generateUid(imageUrl),
						url = imageUrl,
						preview = null,
						source = source,
					),
				)
			}
		}

		return pages.distinctBy { it.id }
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	companion object {
		private val userAgentKey = ConfigKey.UserAgent(
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/131.0.0.0 Mobile Safari/537.36",
		)
	}
}
