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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/custom-search/").parseHtml()
        
        val tags = doc.select("select#genre option").mapNotNull {
            val key = it.attr("value").trim()
            val title = it.text().trim()
            if (key.isNotBlank() && key != "GENRE ALL") MangaTag(title, key, source) else null
        }.toSet()
        
        return MangaListFilterOptions(availableTags = tags)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain)
            append("/custom-search/")
            
            if (filter.tags.isNotEmpty()) {
                append("genre/").append(filter.tags.first().key).append("/")
            }
            
            append("orderby/")
            when (order) {
                SortOrder.POPULARITY -> append("score/")
                SortOrder.ALPHABETICAL -> append("name/")
                else -> append("latest/")
            }
            
            append("page/").append(page + 1).append("/")
            
            if (!filter.query.isNullOrEmpty()) {
                append("?s=").append(filter.query.urlEncoded())
            }
        }

        val response = webClient.httpGet(url)
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html)
        val mangaList = mutableListOf<Manga>()
        
        document.select("article.genre-card").forEach { element ->
            val link = element.selectFirst("div.genre-info > a") ?: element.selectFirst("a.genre-title")
            val cover = element.selectFirst("img.lazy-image, div.genre-cover > a > img")
            
            if (link != null) {
                val coverUrl = cover?.attr("data-src")?.ifEmpty { cover.attr("src") } ?: ""
                val title = link.text().trim()
                
                mangaList.add(Manga(
                    id = generateUid(link.attr("href")),
                    url = link.attr("href").substringAfter(domain),
                    publicUrl = link.attr("abs:href"),
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = coverUrl,
                    largeCoverUrl = coverUrl,
                    authors = emptySet(),
                    tags = emptySet(),
                    state = null,
                    description = null,
                    contentRating = ContentRating.SAFE,
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
        document.select("ol.chapter-list > li, ul.chapter-list > li, .chapter-list li").forEach { element ->
            val link = element.selectFirst("a.ch-link, a")
            if (link != null) {
                val title = link.text().trim() 
                val urlPath = link.attr("href").substringAfter(domain)
                val numMatch = Regex("""[0-9]+(\.[0-9]+)?""").findAll(title).lastOrNull()?.value
                val number = numMatch?.toFloatOrNull() ?: 0f
                val dateText = element.selectFirst(".chapterdate, .date")?.text()?.trim() ?: ""

                chapters.add(MangaChapter(
                    id = generateUid(link.attr("href")),
                    title = title,
                    url = urlPath,
                    number = number,
                    uploadDate = parseDate(dateText),
                    source = source,
                    scanlator = null,
                    branch = null,   
                    volume = 0       
                ))
            }
        }

        val tags = document.select("nav > span > a[href*='/genre/'], .genre-info a").mapNotNull {
            val key = it.attr("href").substringAfter("/genre/").replace("/", "").trim()
            val title = it.text().trim()
            if (key.isNotBlank()) MangaTag(title, key, source) else null
        }.toSet()

        val state = if (document.selectFirst(".tamat-tag, .status:contains(Completed)") != null) MangaState.FINISHED else MangaState.ONGOING

        return manga.copy(
            description = document.select("p.manga-description, .entry-content p").text().trim(),
            tags = tags,
            state = state,
            chapters = chapters.sortedBy { it.number }
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val html = response.body?.string() ?: ""
        
        val pages = mutableListOf<MangaPage>()
        try {
            if (html.contains("imageUrls:")) {
                val jsonStr = html.substringAfter("imageUrls:").substringBefore("],").plus("]")
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val imageUrl = jsonArray.getString(i)
                    pages.add(MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source))
                }
                if (pages.isNotEmpty()) return pages
            }
        } catch (e: Exception) {}

        val document = Jsoup.parse(html)
        document.select("#readerarea img, .reader-area img").forEach { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }.trim()
            if (src.isNotBlank()) {
                pages.add(MangaPage(id = generateUid(src), url = src, preview = null, source = source))
            }
        }
        
        return pages
    }
    
    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
