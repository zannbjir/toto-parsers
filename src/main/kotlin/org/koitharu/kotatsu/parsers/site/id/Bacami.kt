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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain")
            if (!filter.query.isNullOrEmpty()) {
                append("/search/${filter.query.urlEncoded()}/page/$page/")
            } else {
                val orderPath = when (order) {
                    SortOrder.POPULARITY -> "score"
                    SortOrder.ALPHABETICAL -> "name"
                    else -> "latest"
                }
                append("/custom-search/orderby/$orderPath/page/$page/")
            }
        }

        val response = webClient.httpGet(url)
        val document = Jsoup.parse(response.bodyString())
        
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
        val response = webClient.httpGet(manga.publicUrl)
        val document = Jsoup.parse(response.bodyString())
        val content = document.selectFirst("#komik > section.manga-content")!!
        
        val chapters = document.select("ol.chapter-list > li").map { element ->
            val link = element.selectFirst("a.ch-link")!!
            val chNum = link.text().substringAfter("–").filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
            
            MangaChapter(
                id = generateUid(link.attr("href")),
                title = link.text().substringAfter("–").trim(),
                url = link.attr("href").substringAfter(domain),
                number = chNum,
                uploadDate = parseDate(element.select("span.ch-date").text()),
                scanlator = null,
                branch = null,
                source = source,
                volume = 0
            )
        }

        val statusText = document.selectFirst(".hot-tag, .project-tag, .tamat-tag")?.text() ?: ""
        val state = when {
            statusText.contains("Tamat", true) -> MangaState.FINISHED
            statusText.contains("Hot", true) || statusText.contains("Project", true) -> MangaState.ONGOING
            else -> null
        }

        return manga.copy(
            author = content.selectFirst(".info-item:contains(Author) .info-value")?.text() ?: "",
            description = content.select("p.manga-description").text().trim(),
            state = state,
            tags = content.select("nav > span > a").map { 
                MangaTag(it.attr("href").substringAfterLast("/"), it.text(), source) 
            }.toSet(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val html = response.bodyString()
        
        val jsonStr = html.substringAfter("imageUrls:").substringBefore("],").plus("]")
        val jsonArray = JSONArray(jsonStr)
        
        val pages = mutableListOf<MangaPage>()
        for (i in 0 until jsonArray.length()) {
            val imageUrl = jsonArray.getString(i)
            pages.add(MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            ))
        }
        return pages
    }

    private fun parseDate(date: String): Long {
        return try {
            SimpleDateFormat("dd MMMM, yyyy", Locale.ENGLISH).parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
