package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
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

        val response = webClient.httpGet(url)
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html)
        val mangaList = mutableListOf<Manga>()
        
        document.select("article.genre-card").forEach { element ->
            val link = element.selectFirst("div.genre-info > a")
            val cover = element.selectFirst("div.genre-cover > a > img")
            if (link != null) {
                val coverUrl = cover?.attr("abs:data-src")?.ifEmpty { cover.attr("abs:src") } ?: ""
                mangaList.add(Manga(
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
                ))
            }
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl)
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html)
        val chapters = mutableListOf<MangaChapter>()
        
        document.select("ol.chapter-list > li").forEach { element ->
            val link = element.selectFirst("a.ch-link")
            if (link != null) {
                val title = link.text().substringAfter("–").trim()
                chapters.add(MangaChapter(
                    id = generateUid(link.attr("href")),
                    title = title,
                    url = link.attr("href").substringAfter(domain),
                    number = title.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                    uploadDate = 0L,
                    scanlator = null,
                    branch = null,
                    source = source,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            description = document.select("p.manga-description").text().trim(),
            state = if (document.selectFirst(".tamat-tag") != null) MangaState.FINISHED else MangaState.ONGOING,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val html = response.body?.string() ?: ""
        val jsonStr = html.substringAfter("imageUrls:").substringBefore("],").plus("]")
        val jsonArray = JSONArray(jsonStr)
        val pages = mutableListOf<MangaPage>()
        
        for (i in 0 until jsonArray.length()) {
            val imageUrl = jsonArray.getString(i)
            pages.add(MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source))
        }
        return pages
    }
}
