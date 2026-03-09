package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGUYA, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.kaguya.pro")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/all-series/${if (page > 1) "page/$page/" else ""}?m_orderby=${if (order == SortOrder.POPULARITY) "views" else "latest"}"
        val response = webClient.httpGet(url)
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html)
        val mangaList = mutableListOf<Manga>()

        document.select(".page-item-detail").forEach { element ->
            val link = element.selectFirst(".post-title a")
            if (link != null) {
                val img = element.selectFirst("img")
                val coverUrl = img?.attr("abs:data-src")?.ifEmpty { img.attr("abs:src") } ?: ""
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
        val chapters = mutableListOf<MangaChapter>()
        val ajaxResponse = webClient.httpPost("${manga.publicUrl}ajax/chapters/", emptyMap<String, String>())
        val ajaxHtml = ajaxResponse.body?.string() ?: ""
        val ajaxDoc = Jsoup.parse(ajaxHtml)
        
        ajaxDoc.select(".wp-manga-chapter").forEach { element ->
            val link = element.selectFirst("a")
            if (link != null) {
                val chName = link.text().trim()
                chapters.add(MangaChapter(
                    id = generateUid(link.attr("href")),
                    title = chName,
                    url = link.attr("href").substringAfter(domain),
                    number = chName.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                    uploadDate = 0L,
                    scanlator = null,
                    branch = null,
                    source = source,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            description = "Kaguya Manga",
            state = MangaState.ONGOING,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html)
        val pages = mutableListOf<MangaPage>()

        document.select(".page-break img").forEach { element ->
            val imageUrl = if (element.hasAttr("data-aesir")) {
                try {
                    val decoded = java.util.Base64.getDecoder().decode(element.attr("data-aesir").trim())
                    String(decoded)
                } catch (e: Exception) { element.attr("abs:src") }
            } else {
                element.attr("abs:src")
            }
            pages.add(MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source))
        }
        return pages
    }
}
