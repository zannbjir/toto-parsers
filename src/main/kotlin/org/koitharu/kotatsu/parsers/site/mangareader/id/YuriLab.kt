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
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Locale

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.YURILAB, "yurilabs.my.id", pageSize = 20, searchPageSize = 10) {

	override val listUrl = "/series"
	override val selectMangaList = "div.manga__item"
	override val selectMangaListImg = "div.manga__thumb img"
	override val selectMangaListTitle = "h2 a"
	override val selectChapter = "ul.main.version-chap li.wp-manga-chapter"
	override val sourceLocale: Locale = Locale("id")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(),
		availableStates = emptySet(),
		availableContentTypes = emptySet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain$listUrl".toHttpUrl().newBuilder().apply {
			if (page > 1) addPathSegment("page").addPathSegment(page.toString())
			if (filter.query != null) addQueryParameter("s", filter.query)
		}.build()
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = docs.select(selectChapter).mapChapters(reversed = false) { i, element ->
			val a = element.selectFirst(".chapter-name a") ?: element.selectFirst("a") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(a.attrAsRelativeUrl("href")),
				title = a.text(),
				url = a.attrAsRelativeUrl("href"),
				number = Regex("""(\d+(?:\.\d+)?)""").find(a.text())?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1f),
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
		return docs.select("div.reading-content img")
			.filter { it.parent()?.tagName() != "noscript" }
			.mapNotNull { img ->
				val src = img.attrOrNull("onerror")?.substringAfter("src='")?.substringBefore("';")
					?: img.src()
				src?.takeIf { it.isNotBlank() }?.let {
					MangaPage(generateUid(it), it.toRelativeUrl(domain), null, source)
				}
			}
	}
}
