package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.YURILAB, "yurilabs.my.id", pageSize = 20, searchPageSize = 10) {

	override val listUrl = "/manga"
	override val selectMangaList = "div.manga__item"
	override val selectMangaListImg = "div.manga__thumb img"
	override val selectMangaListTitle = "h2 a"
	override val selectChapter = "div.list-chapter div.chapter-item"
	override val sourceLocale: Locale = Locale("id")

	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(),
		availableStates = emptySet(),
		availableContentTypes = emptySet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val base = buildString {
			append("https://")
			append(domain)
			append(listUrl)
			if (page > 1) {
				append("/page/")
				append(page)
			}
		}
		val url = if (filter.query != null && filter.query.isNotBlank()) {
			base.toHttpUrl().newBuilder()
				.addQueryParameter("s", filter.query)
				.build()
		} else {
			base.toHttpUrl().newBuilder().build()
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = docs.select("ul.main.version-chap li.wp-manga-chapter").mapChapters(reversed = false) { i, element ->
			val chapterLink = element.selectFirst(".chapter-name a") ?: element.selectFirst("a") ?: return@mapChapters null
			val chapterUrl = chapterLink.attrAsRelativeUrl("href")
			val chapterName = chapterLink.text()
			val parsedNumber = Regex("""chapter\s+([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
				.find(chapterName)
				?.groupValues
				?.getOrNull(1)
				?.toFloatOrNull()

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterName,
				url = chapterUrl,
				number = parsedNumber ?: (i + 1f),
				volume = 0,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			description = docs.selectFirst("div.summary__content p")?.text(),
			authors = emptySet(),
			tags = emptySet(),
			coverUrl = docs.selectFirst("div.summary_image img")?.src() ?: manga.coverUrl,
			chapters = chapters,
			contentRating = ContentRating.ADULT,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val images = docs.select("div.reading-content img")
			.filter { element -> element.parent()?.tagName() != "noscript" }
			.mapNotNull { element ->
				val fallbackFromError = element.attrOrNull("onError")?.substringAfter("src='")?.substringBefore("';")
					?: element.attrOrNull("onerror")?.substringAfter("src='")?.substringBefore("';")
				val pageUrl = (fallbackFromError?.takeIf { it.isNotBlank() } ?: element.src())
					?.takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(pageUrl),
					url = pageUrl.toRelativeUrl(domain),
					preview = null,
					source = source,
				)
			}
		if (images.isEmpty()) {
			return emptyList()
		}
		
		return images
	}

	private fun parseChapterDate(date: String?): Long {
		date ?: return 0
		val normalized = date.lowercase()
		return when {
			normalized.contains("yang lalu") -> parseRelativeDate(normalized)
			normalized.contains("hari ini") -> Calendar.getInstance().timeInMillis
			normalized.contains("kemarin") -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			else -> chapterDateFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val value = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		return when {
			"detik" in date -> Calendar.getInstance().apply { add(Calendar.SECOND, -value) }.timeInMillis
			"menit" in date -> Calendar.getInstance().apply { add(Calendar.MINUTE, -value) }.timeInMillis
			"jam" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -value) }.timeInMillis
			"hari" in date -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -value) }.timeInMillis
			"minggu" in date -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -(value * 7)) }.timeInMillis
			"bulan" in date -> Calendar.getInstance().apply { add(Calendar.MONTH, -value) }.timeInMillis
			"tahun" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, -value) }.timeInMillis
			else -> 0
		}
	}
}
