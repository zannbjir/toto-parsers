package org.koitharu.kotatsu.parsers.site.id

import android.util.Base64
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGUYA, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.kaguya.pro")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING
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
            append("https://$domain/all-series/")
            if (page > 1) append("page/$page/")
            append("?m_orderby=")
            append(when (order) {
                SortOrder.POPULARITY -> "views"
                SortOrder.RATING -> "rating"
                else -> "latest"
            })
            if (!filter.query.isNullOrEmpty()) {
                append("&s=${filter.query.urlEncoded()}")
            }
        }

        val response = webClient.httpGet(url)
        val document = Jsoup.parse(response.bodyString())

        return document.select(".page-item-detail, .manga-item").map { element ->
            val link = element.selectFirst(".post-title a, h3 a")!!
            val coverUrl = element.selectFirst("img")?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
            } ?: ""

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
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        while (true) {
            val ajaxResponse = webClient.httpPost(
                url = "${manga.publicUrl}ajax/chapters?t=${page++}",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            val ajaxHtml = ajaxResponse.bodyString()
            val ajaxDoc = Jsoup.parse(ajaxHtml)
            val items = ajaxDoc.select(".wp-manga-chapter")

            if (items.isEmpty()) break

            items.forEach { element ->
                val link = element.selectFirst("a")!!
                val chName = link.text().trim()
                chapters.add(MangaChapter(
                    id = generateUid(link.attr("href")),
                    title = chName,
                    url = link.attr("href").substringAfter(domain),
                    number = chName.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                    uploadDate = parseDate(element.select(".chapter-release-date").text()),
                    scanlator = null,
                    branch = null,
                    source = source,
                    volume = 0
                ))
            }
            if (page > 10) break
        }

        val statusText = document.select(".summary-heading:contains(Status) + .summary-content").text()

        return manga.copy(
            description = document.select(".manga-excerpt, .description-summary").text().trim(),
            state = if (statusText.contains("OnGoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            tags = document.select(".genres-content a").map {
                MangaTag(it.attr("href").substringAfter("genre="), it.text(), source)
            }.toSet(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val document = Jsoup.parse(response.bodyString())

        return document.select(".page-break img, .reading-content img").map { element ->
            val imageUrl = if (element.hasAttr("data-aesir")) {
                try {
                    val decoded = Base64.decode(element.attr("data-aesir"), Base64.DEFAULT)
                        .toString(Charsets.UTF_8).trim()
                    if (decoded.isNotEmpty()) decoded else element.attr("abs:src")
                } catch (e: Exception) {
                    element.attr("abs:src")
                }
            } else {
                element.attr("abs:src")
            }

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun parseDate(date: String): Long {
        return try {
            SimpleDateFormat("d MMMM", Locale("en")).parse(date.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
