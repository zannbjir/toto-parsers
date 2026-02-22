package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BACAKOMIK_MY", "BacaKomik", "id")
internal class BacaKomikParser(context: MangaLoaderContext) :
    MangaParser(context, MangaParserSource.BACAKOMIK_MY) {
    override val configKeyDomain = ConfigKey.Domain("bacakomik.my")
    override val headers: Headers
        get() = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .add("Referer", "https://$domain/")
            .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,   
        SortOrder.UPDATED,      
        SortOrder.NEWEST,       
        SortOrder.ALPHABETICAL, 
    )

    override val availableStates: Set<MangaState> = EnumSet.of(
        MangaState.ONGOING,
        MangaState.FINISHED,
    )
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    private fun pagePath(page: Int) = if (page > 1) "page/$page/" else ""

    override suspend fun getList(offset: Int, filter: MangaListFilter?): List<Manga> {
        val page = (offset / 20) + 1

        val url = when (filter) {
            is MangaListFilter.Search -> {
                val builder = "$baseUrl/daftar-komik/${pagePath(page)}".toHttpUrl().newBuilder()
                builder.addQueryParameter("title", filter.query)
                builder.build().toString()
            }
            is MangaListFilter.Advanced -> {
                val builder = "$baseUrl/daftar-komik/${pagePath(page)}".toHttpUrl().newBuilder()

                val order = when (filter.sortOrder) {
                    SortOrder.POPULARITY   -> "popular"
                    SortOrder.UPDATED      -> "update"
                    SortOrder.NEWEST       -> "latest"
                    SortOrder.ALPHABETICAL -> "title"
                    else                   -> "update"
                }
                builder.addQueryParameter("order", order)

                filter.states.firstOrNull()?.let {
                    val status = when (it) {
                        MangaState.FINISHED -> "completed"
                        MangaState.ONGOING  -> "ongoing"
                        else                -> ""
                    }
                    if (status.isNotEmpty()) builder.addQueryParameter("status", status)
                }

                filter.tags.forEach { tag ->
                    builder.addQueryParameter("genre[]", tag.key)
                }

                builder.build().toString()
            }
            else -> {
                "$baseUrl/daftar-komik/${pagePath(page)}?order=popular"
            }
        }

        return parseMangaList(webClient.httpGet(url, headers).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.animepost").mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val href = el.selectFirst("div.animposx > a")?.attrAsRelativeUrl("href") ?: return null
        val title = el.selectFirst(".animposx .tt h4")?.text()?.trim() ?: return null
        val cover = el.selectFirst("div.limit img")?.imgAttr()

        return Manga(
            id        = generateUid(href),
            title     = title,
            altTitle  = null,
            url       = href,
            publicUrl = "https://$domain$href",
            rating    = RATING_UNKNOWN,
            isNsfw    = false,
            coverUrl  = cover.orEmpty(),
            tags      = emptySet(),
            state     = null,
            author    = null,
            source    = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}", headers).parseHtml()
        val title = doc.selectFirst("#breadcrumbs li:last-child span")?.text()?.trim()
            ?: manga.title
        val author = doc.selectFirst(".infox .spe span:contains(Author) :not(b)")?.text()?.trim()
        val artist = doc.selectFirst(".infox .spe span:contains(Artis) :not(b)")?.text()?.trim()
        val synopsis = doc.selectFirst("div.desc > .entry-content.entry-content-single")
            ?.select("p")?.text()
            ?.substringAfter("bercerita tentang ")
            ?.trim()
        val statusText = doc.selectFirst(".infox .spe span:contains(Status)")?.text()?.lowercase()
        val state = when {
            statusText?.contains("berjalan") == true -> MangaState.ONGOING
            statusText?.contains("tamat")    == true -> MangaState.FINISHED
            else -> null
        }
        val tags = doc.select(".infox > .genre-info > a, .infox .spe span:contains(Jenis Komik) a")
            .mapToSet { el ->
                MangaTag(
                    title  = el.text().trim(),
                    key    = el.attrAsRelativeUrl("href")
                        .substringAfterLast("/").trimEnd('/'),
                    source = source,
                )
            }

        val cover = doc.selectFirst(".thumb > img:nth-child(1)")?.imgAttr() ?: manga.coverUrl
        val chapters = parseChapterList(doc)

        return manga.copy(
            title       = title,
            altTitle    = null,
            author      = artist?.let { "$author, $it" } ?: author,
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
            val href = link.attrAsRelativeUrl("href")
            val name = link.text().trim()
            val number = Regex("""Chapter\s([0-9]+)""")
                .find(name)?.groups?.get(1)?.value?.toFloatOrNull() ?: index.toFloat()
            val dateText = el.selectFirst(".dt a")?.text()?.trim()
            val date = dateText?.let { parseChapterDate(it) } ?: 0L

            MangaChapter(
                id         = generateUid(href),
                name       = name,
                number     = number,
                url        = href,
                uploadDate = date,
                source     = source,
                scanlator  = null,
                branch     = null,
            )
        }.reversed()
    }

    private fun parseChapterDate(date: String): Long {
        return if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toIntOrNull() ?: return 0L
            Calendar.getInstance().apply {
                when {
                    "detik" in date -> add(Calendar.SECOND, -value)
                    "menit" in date -> add(Calendar.MINUTE, -value)
                    "jam"   in date -> add(Calendar.HOUR_OF_DAY, -value)
                    "hari"  in date -> add(Calendar.DATE, -value)
                    "minggu"in date -> add(Calendar.DATE, -value * 7)
                    "bulan" in date -> add(Calendar.MONTH, -value)
                    "tahun" in date -> add(Calendar.YEAR, -value)
                }
            }.timeInMillis
        } else {
            runCatching { dateFormat.parse(date)?.time }.getOrNull() ?: 0L
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", headers).parseHtml()
        return doc.select("div:has(>img[alt*=\"Chapter\"]) img")
            .filter { it.parent()?.tagName() != "noscript" }
            .mapIndexedNotNull { i, img ->
                val url = img.attr("onError")
                    .substringAfter("src='")
                    .substringBefore("';")
                    .takeIf { it.isNotEmpty() }
                    ?: img.imgAttr()

                if (url.isEmpty()) return@mapIndexedNotNull null

                MangaPage(
                    id      = generateUid("${chapter.url}#$i"),
                    url     = url,
                    preview = null,
                    source  = source,
                )
            }
    }

    override suspend fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("4-Koma", "4-koma", source),
        MangaTag("4-Koma. Comedy", "4-koma-comedy", source),
        MangaTag("Action", "action", source),
        MangaTag("Action. Adventure", "action-adventure", source),
        MangaTag("Adult", "adult", source),
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
        MangaTag("Manga", "manga", source),
        MangaTag("Manhua", "manhua", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Medical", "medical", source),
        MangaTag("Military", "military", source),
        MangaTag("Music", "music", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("One Shot", "one-shot", source),
        MangaTag("Parody", "parody", source),
        MangaTag("Police", "police", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Romance", "romance", source),
        MangaTag("Samurai", "samurai", source),
        MangaTag("School", "school", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shoujo Ai", "shoujo-ai", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Shounen Ai", "shounen-ai", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Smut", "smut", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Super Power", "super-power", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Vampire", "vampire", source),
        MangaTag("Webtoon", "webtoon", source),
        MangaTag("Yuri", "yuri", source),
    )

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> absUrl("data-lazy-src")
        hasAttr("data-src")      -> absUrl("data-src")
        else                     -> absUrl("src")
    }

    private val baseUrl get() = "https://$domain"
}
