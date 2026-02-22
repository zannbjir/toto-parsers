package org.koitharu.kotatsu.parsers.site.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.FlexibleMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SOFTKOMIK", "SoftKomik", "id")
internal class SoftKomik(context: MangaLoaderContext) :
    FlexibleMangaParser(context, MangaParserSource.SOFTKOMIK) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
    )

    override val searchQueryCapabilities: MangaSearchQueryCapabilities
        get() = MangaSearchQueryCapabilities(
            SearchCapability(
                field = TITLE_NAME,
                criteriaTypes = setOf(Match::class),
                isMultiple = false,
            ),
            SearchCapability(
                field = TAG,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
    )

    private val pageSize = 20

    override suspend fun getList(query: MangaSearchQuery): List<Manga> {
        val page = (query.offset / pageSize) + 1
        val urlBuilder = "https://$domain/komik/list".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        query.criteria.forEach { criterion ->
            when (criterion) {
                is Match<*> -> {
                    if (criterion.field == TITLE_NAME) {
                        urlBuilder.addQueryParameter("name", criterion.value.toString())
                    }
                }
                is Include<*> -> {
                    if (criterion.field == TAG) {
                        criterion.values.firstOrNull()?.let {
                            urlBuilder.addQueryParameter("genre", (it as MangaTag).key)
                        }
                    }
                }
                else -> {}
            }
        }

        when (query.order ?: defaultSortOrder) {
            SortOrder.NEWEST -> urlBuilder.addQueryParameter("sort", "newest")
            else             
        }

        val doc = webClient.httpGet(urlBuilder.build().toString()).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a[href$=-bahasa-indonesia]:has(img)").mapNotNull { el ->
            val href  = el.attrAsRelativeUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.selectFirst("img")?.attr("alt")?.trim()
                ?: el.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val coverUrl = decodeNextImageUrl(el.selectFirst("img")?.attr("src") ?: "")
            Manga(
                id        = generateUid(href),
                url       = href,
                publicUrl = "https://$domain$href",
                title     = title,
                altTitles = emptySet(),
                coverUrl  = coverUrl,
                rating    = RATING_UNKNOWN,
                isNsfw    = false,
                tags      = emptySet(),
                state     = null,
                authors   = emptySet(),
                source    = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}").parseHtml()

        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: manga.title

        val author = doc.select("td")
            .firstOrNull { it.text().trim() == "Author" }
            ?.nextElementSibling()?.text()?.trim()
        val authors = setOfNotNull(author).filter { it.isNotBlank() }.toSet()

        val statusText = doc.select("td")
            .firstOrNull { it.text().trim() == "Status" }
            ?.nextElementSibling()?.text()?.lowercase()?.trim()
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
            decodeNextImageUrl(it.attr("src"))
        } ?: manga.coverUrl

        val synopsis = doc.select("p").firstOrNull { p ->
            p.text().length > 50 && !p.text().startsWith("Semua komik")
        }?.text()?.trim()

        val chapters = fetchChapterList(manga, doc)

        return manga.copy(
            title       = title,
            altTitles   = emptySet(),
            authors     = authors,
            description = synopsis,
            state       = state,
            tags        = tags,
            coverUrl    = cover,
            chapters    = chapters,
        )
    }

    private fun fetchChapterList(manga: Manga, doc: Document): List<MangaChapter> {
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return emptyList()

        val mangaSlug = manga.url.trimStart('/')
        val pattern = Regex(""""(/$mangaSlug/chapter/[^"]+)"""")
        val urls = pattern.findAll(nextData).map { it.groupValues[1] }.distinct().toList()
        if (urls.isEmpty()) return emptyList()

        return urls.mapIndexed { index, url ->
            val chapterSlug = url.substringAfterLast("/")
            val number = chapterSlug.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                ?: (index + 1).toFloat()
            MangaChapter(
                id         = generateUid(url),
                title      = "Chapter $chapterSlug",
                number     = number,
                volume     = 0,
                url        = url,
                uploadDate = 0L,
                source     = source,
                scanlator  = null,
                branch     = null,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()

        val directImgs = doc.select(
            "img[src*='softdevices'], img[data-src*='softdevices'], img[src*='softkomik']"
        )
        if (directImgs.isNotEmpty()) {
            return directImgs.mapIndexed { i, img ->
                val url = decodeNextImageUrl(img.attr("src").ifBlank { img.attr("data-src") })
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
            val imageUrls = Regex(
                """"(https://[^"]*(?:softdevices|softkomik)[^"]*\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"]*)?)"[,\s}]"""
            ).findAll(nextData).map { it.groupValues[1] }.distinct().toList()

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

    private suspend fun getAvailableTags(): Set<MangaTag> = setOf(
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

    private fun decodeNextImageUrl(src: String): String {
        if (src.isBlank()) return ""
        return try {
            if (src.startsWith("/_next/image")) {
                src.toHttpUrl().queryParameter("url") ?: src
            } else src
        } catch (_: Exception) { src }
    }
}
