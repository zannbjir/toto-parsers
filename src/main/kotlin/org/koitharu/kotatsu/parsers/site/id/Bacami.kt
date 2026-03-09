package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BACAMI", "Bacami", "id")
internal class Bacami(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BACAMI, 20) {

    override val configKeyDomain = ConfigKey.Domain("bacami.net")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search/${filter.query.urlEncoded()}/page/$page/"
        } else {
            val orderPath = if (order == SortOrder.POPULARITY) "score" else "latest"
            "https://$domain/custom-search/orderby/$orderPath/page/$page/"
        }

        val html = webClient.httpGet(url).bodyString()
        val document = Jsoup.parse(html)
        
        return document.select("article.genre-card").map { element ->
            val link = element.selectFirst("div.genre-info > a")!!
            val cover = element.selectFirst("div.genre-cover > a > img")
            val coverUrl = cover?.attr("abs:data-src")?.ifEmpty { cover.attr("abs:src") } ?: ""
            
            Manga(
                id = generateUid(link.attr("href")),
                url = link.attr("href").substringAfter(domain),
                publicUrl = link.attr("href"),
                title = link.text().trim(),
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = null,
                source = source,
                rating = RATING_UNKNOWN
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val html = webClient.httpGet(manga.publicUrl).bodyString()
        val document = Jsoup.parse(html)
        val content = document.selectFirst("#komik > section.manga-content")!!
        
        val chapters = document.select("ol.chapter-list > li").map { element ->
            val link = element.selectFirst("a.ch-link")!!
            MangaChapter(
                id = generateUid(link.attr("href")),
                title = link.text().substringAfter("–").trim(),
                url = link.attr("href").substringAfter(domain),
                number = link.text().filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                uploadDate = parseDate(element.select("span.ch-date").text()),
                scanlator = null,
                branch = null,
                source = source,
                volume = 0
            )
        }

        return manga.copy(
            description = content.select("p.manga-description").text().trim(),
            state = if (document.selectFirst(".tamat-tag") != null) MangaState.FINISHED else MangaState.ONGOING,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val html = webClient.httpGet("https://$domain${chapter.url}").bodyString()
        val jsonStr = html.substringAfter("imageUrls:").substringBefore("],").plus("]")
        val jsonArray = JSONArray(jsonStr)
        
        return List(jsonArray.length()) { i ->
            val imageUrl = jsonArray.getString(i)
            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }

    private fun parseDate(date: String): Long {
        return try {
            SimpleDateFormat("dd MMMM, yyyy", Locale.ENGLISH).parse(date)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
