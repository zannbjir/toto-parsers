package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
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
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BACAKOMIK", "BacaKomik", "id")
internal class BacaKomikParser(context: MangaLoaderContext) :
    FlexibleMangaParser(context, MangaParserSource.BACAKOMIK_MY) {

    override val configKeyDomain = ConfigKey.Domain("bacakomik.my")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
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
                isMultiple = true,
            ),
            SearchCapability(
                field = STATE,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val pageSize = 20

    private fun pagePath(offset: Int): String {
        val page = (offset / pageSize) + 1
        return if (page > 1) "page/$page/" else ""
    }

    private fun buildHeaders() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .add("Referer", "https://$domain/")
        .build()

    override suspend fun getList(query: MangaSearchQuery): List<Manga> {
        val urlBuilder = "https://$domain/daftar-komik/${pagePath(query.offset)}".toHttpUrl().newBuilder()

        val orderParam = when (query.order ?: defaultSortOrder) {
            SortOrder.POPULARITY   -> "popular"
            SortOrder.UPDATED      -> "update"
            SortOrder.NEWEST       -> "latest"
            SortOrder.ALPHABETICAL -> "title"
            else                   -> "popular"
        }
        urlBuilder.addQueryParameter("order", orderParam)

        query.criteria.forEach { criterion ->
            when (criterion) {
                is Match<*> -> {
                    if (criterion.field == TITLE_NAME) {
                        urlBuilder.addQueryParameter("title", criterion.value.toString())
                    }
                }
                is Include<*> -> {
                    when (criterion.field) {
                        TAG -> criterion.values.forEach {
                            urlBuilder.addQueryParameter("genre[]", (it as MangaTag).key)
                        }
                        STATE -> criterion.values.firstOrNull()?.let {
                            urlBuilder.addQueryParameter("status", when (it as MangaState) {
                                MangaState.FINISHED -> "completed"
                                MangaState.ONGOING  -> "ongoing"
                                else                -> return@let
                            })
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        val doc = webClient.httpGet(urlBuilder.build().toString(), buildHeaders()).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.animepost").mapNotNull { el ->
            val href = el.selectFirst("div.animposx > a")?.attrAsRelativeUrl("href") ?: return@mapNotNull null
            val title = el.selectFirst(".animposx .tt h4")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("div.limit img")?.imgAttr()
            Manga(
                id        = generateUid(href),
                url       = href,
                publicUrl = "https://$domain$href",
                title     = title,
                altTitles = emptySet(),
                coverUrl  = cover.orEmpty(),
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
        val doc = webClient.httpGet("https://$domain${manga.url}", buildHeaders()).parseHtml()

        val title = doc.selectFirst("#breadcrumbs li:last-child span")?.text()?.trim() ?: manga.title

        val author = doc.selectFirst(".infox .spe span:contains(Author) :not(b)")?.text()?.trim()
        val artist = doc.selectFirst(".infox .spe span:contains(Artis) :not(b)")?.text()?.trim()
        val authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet()

        val statusText = doc.selectFirst(".infox .spe span:contains(Status)")?.text()?.lowercase() ?: ""
        val state = when {
            "berjalan" in statusText -> MangaState.ONGOING
            "tamat"    in statusText -> MangaState.FINISHED
            else -> null
        }

        val tags = doc.select(".infox > .genre-info > a, .infox .spe span:contains(Jenis Komik) a")
            .mapToSet { el ->
                MangaTag(
                    title  = el.text().trim(),
                    key    = el.attrAsRelativeUrl("href").substringAfterLast("/").trimEnd('/'),
                    source = source,
                )
            }

        val cover = doc.selectFirst(".thumb > img:nth-child(1)")?.imgAttr() ?: manga.coverUrl

        val synopsis = doc.selectFirst("div.desc > .entry-content.entry-content-single")
            ?.select("p")?.text()
            ?.substringAfter("bercerita tentang ")
            ?.trim()

        val chapters = parseChapterList(doc)

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

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        return doc.select("#chapter_list li").mapIndexedNotNull { index, el ->
            val link = el.selectFirst(".lchx a") ?: return@mapIndexedNotNull null
            val href  = link.attrAsRelativeUrl("href")
            val name  = link.text().trim()
            val number = Regex("""Chapter\s([0-9]+)""")
                .find(name)?.groups?.get(1)?.value?.toFloatOrNull() ?: (index + 1).toFloat()
            val dateText = el.selectFirst(".dt a")?.text()?.trim()
            MangaChapter(
                id         = generateUid(href),
                title      = name,
                number     = number,
                volume     = 0,
                url        = href,
                uploadDate = dateText?.let { parseChapterDate(it) } ?: 0L,
                source     = source,
                scanlator  = null,
                branch     = null,
            )
        }.reversed()
    }

    private fun parseChapterDate(date: String): Long {
        if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toIntOrNull() ?: return 0L
            return Calendar.getInstance().apply {
                when {
                    "detik"  in date -> add(Calendar.SECOND, -value)
                    "menit"  in date -> add(Calendar.MINUTE, -value)
                    "jam"    in date -> add(Calendar.HOUR_OF_DAY, -value)
                    "hari"   in date -> add(Calendar.DATE, -value)
                    "minggu" in date -> add(Calendar.DATE, -value * 7)
                    "bulan"  in date -> add(Calendar.MONTH, -value)
                    "tahun"  in date -> add(Calendar.YEAR, -value)
                }
            }.timeInMillis
        }
        return runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: 0L
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", buildHeaders()).parseHtml()
        return doc.select("div:has(>img[alt*=\"Chapter\"]) img")
            .filter { it.parent()?.tagName() != "noscript" }
            .mapIndexedNotNull { i, img ->
                val url = img.attr("onError")
                    .substringAfter("src='").substringBefore("';")
                    .takeIf { it.isNotEmpty() }
                    ?: img.imgAttr()
                if (url.isEmpty()) null
                else MangaPage(
                    id      = generateUid("${chapter.url}#$i"),
                    url     = url,
                    preview = null,
                    source  = source,
                )
            }
    }

    private suspend fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("Action", "action", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Cooking", "cooking", source),
        MangaTag("Demons", "demons", source),
        MangaTag("Doujinshi", "doujinshi", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Game", "game", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Gore", "gore", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Isekai", "isekai", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Magic", "magic", source),
        MangaTag("Manhua", "manhua", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Military", "military", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Romance", "romance", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Webtoon", "webtoon", source),
        MangaTag("Yuri", "yuri", source),
    )

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> absUrl("data-lazy-src")
        hasAttr("data-src")      -> absUrl("data-src")
        else                     -> absUrl("src")
    }
}
