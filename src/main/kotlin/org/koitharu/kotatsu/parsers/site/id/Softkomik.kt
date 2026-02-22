package org.koitharu.kotatsu.parsers.site.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SOFTKOMIK", "SoftKomik", "id")
internal class SoftKomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, 
        SortOrder.NEWEST,  
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/komik/list")

            when {
                filter is MangaListFilter.Search -> {
                    append("?name=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page)
                }
                filter is MangaListFilter.Advanced && filter.tags.isNotEmpty() -> {
                    val tag = filter.tags.first()
                    append("?genre=")
                    append(tag.key.urlEncoded())
                    append("&page=")
                    append(page)
                }
                else -> {
                    append("?page=")
                    append(page)
                    when (order) {
                        SortOrder.NEWEST  -> append("&sort=newest")
                        else             
                    }
                }
            }
        }

        val doc = webClient.httpGet(url, headers).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a[href$=-bahasa-indonesia]:has(img)").mapNotNull { el ->
            parseMangaFromElement(el)
        }
    }

    private fun parseMangaFromElement(el: Element): Manga? {
        val href = el.attrAsRelativeUrl("href").takeIf { it.isNotBlank() } ?: return null
        val title = el.selectFirst("img")?.attr("alt")?.trim()
            ?: el.text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val coverRaw = el.selectFirst("img")?.attr("src") ?: ""
        val coverUrl = extractNextImageUrl(coverRaw)

        return Manga(
            id            = generateUid(href),
            url           = href,
            publicUrl     = "https://$domain$href",
            title         = title,
            altTitle      = null,
            coverUrl      = coverUrl,
            rating        = RATING_UNKNOWN,
            isNsfw        = false,
            tags          = emptySet(),
            state         = null,
            author        = null,
            source        = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}", headers).parseHtml()
        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: manga.title
        val author = doc.select("td")
            .firstOrNull { it.text().trim() == "Author" }
            ?.nextElementSibling()
            ?.text()?.trim()
        val statusText = doc.select("td")
            .firstOrNull { it.text().trim() == "Status" }
            ?.nextElementSibling()
            ?.text()?.lowercase()?.trim()
        val state = when {
            statusText?.contains("ongoing")  == true -> MangaState.ONGOING
            statusText?.contains("complete") == true -> MangaState.FINISHED
            statusText?.contains("hiatus")   == true -> MangaState.PAUSED
            else -> null
        }
        val tags = doc.select("a[href*='/komik/genre/']").mapToSet { el ->
            MangaTag(
                title  = el.text().trim(),
                key    = el.attr("href").substringAfterLast("/"),
                source = source,
            )
        }
        val cover = doc.selectFirst("img[alt='$title']")?.let {
            extractNextImageUrl(it.attr("src"))
        } ?: manga.coverUrl
        val synopsis = doc.select("p").firstOrNull { p ->
            p.text().length > 50 && !p.text().startsWith("Semua komik")
        }?.text()?.trim()
        val chapters = fetchChapterList(manga)

        return manga.copy(
            title       = title,
            author      = author,
            description = synopsis,
            state       = state,
            tags        = tags,
            coverUrl    = cover,
            chapters    = chapters,
        )
    }

    private suspend fun fetchChapterList(manga: Manga): List<MangaChapter> {
        val slug = manga.url.trimStart('/').trimEnd('/')
        return emptyList() 
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", headers).parseHtml()
        val imgs = doc.select("img[src*='softdevices'], img[src*='softkomik'], img[data-src*='softdevices']")
        if (imgs.isNotEmpty()) {
            return imgs.mapIndexed { i, img ->
                val url = extractNextImageUrl(img.attr("src").ifBlank { img.attr("data-src") })
                MangaPage(
                    id      = generateUid("${chapter.url}#$i"),
                    url     = url,
                    preview = null,
                    source  = source,
                )
            }
        }

        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            val imageUrls = Regex(""""(https://[^"]*(?:softdevices|softkomik)[^"]*\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"]*)?)"[,\s]""")
                .findAll(nextData)
                .map { it.groupValues[1] }
                .toList()

            if (imageUrls.isNotEmpty()) {
                return imageUrls.mapIndexed { i, url ->
                    MangaPage(
                        id      = generateUid("${chapter.url}#$i"),
                        url     = url,
                        preview = null,
                        source  = source,
                    )
                }
            }
        }
        return emptyList() 
    }

    private suspend fun getAvailableTags(): Set<MangaTag> {
        return setOf(
            MangaTag("Action", "Action", source),
            MangaTag("Adventure", "Adventure", source),
            MangaTag("Comedy", "Comedy", source),
            MangaTag("Drama", "Drama", source),
            MangaTag("Fantasy", "Fantasy", source),
            MangaTag("Horror", "Horror", source),
            MangaTag("Isekai", "Isekai", source),
            MangaTag("Martial Arts", "Martial Arts", source),
            MangaTag("Mystery", "Mystery", source),
            MangaTag("Romance", "Romance", source),
            MangaTag("Sci-fi", "Sci-fi", source),
            MangaTag("Seinen", "Seinen", source),
            MangaTag("Shounen", "Shounen", source),
            MangaTag("Slice of Life", "Slice of Life", source),
            MangaTag("Supernatural", "Supernatural", source),
            MangaTag("Thriller", "Thriller", source),
        )
    }

    private fun extractNextImageUrl(src: String): String {
        if (src.isBlank()) return ""
        return try {
            if (src.startsWith("/_next/image")) {
                src.toHttpUrl().queryParameter("url") ?: src
            } else {
                src
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
