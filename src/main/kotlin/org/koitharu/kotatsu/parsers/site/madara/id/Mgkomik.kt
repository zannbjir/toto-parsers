package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.PagedMangaParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class WebMgkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MGKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("web.mgkomik.cc")

    // Header super kuat anti 403
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", getRandomUserAgent())
        .add("Referer", "https://web.mgkomik.cc/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Cache-Control", "max-age=0")
        .build()

    private fun getRandomUserAgent(): String {
        val list = listOf(
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )
        return list.random()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(), // kalau butuh genre nanti kita tambah
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA)
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

        return doc.select("div.manga-item, article, .series-item, .manga__item").mapNotNull { el ->
            val a = el.selectFirst("a[href*='/komik/']") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val title = a.selectFirst("h2, h3, .title, .manga-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.src() ?: el.selectFirst("img")?.attr("data-src")

            Manga(
                id = generateUid(href),
                title = title,
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                coverUrl = cover,
                largeCoverUrl = cover,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.distinctBy { it.id }
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = "https://web.mgkomik.cc"

        if (!filter.query.isNullOrEmpty()) {
            return "$base/page/$page/?s=${filter.query.urlEncoded()}"
        }

        val sort = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.NEWEST -> "latest"
            else -> "update"
        }

        return "$base/komik/page/$page/?order=$sort"
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1.entry-title, h1.title, .manga-title")?.text()?.trim() ?: manga.title
        val description = doc.selectFirst(".description, .summary__content, .entry-content p")?.text()?.trim() ?: ""

        val chapters = doc.select("li.chapter-item, .wp-manga-chapter, .chapter-link").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val url = a.attrAsRelativeUrl("href")
            val chapterTitle = a.text().trim()

            MangaChapter(
                id = generateUid(url),
                title = chapterTitle,
                url = url,
                number = chapterTitle.parseChapterNumber() ?: 0f,
                volume = 0,
                source = source,
            )
        }.sortedByDescending { it.number }

        return manga.copy(
            title = title,
            description = description,
            chapters = chapters,
            state = if (doc.text().contains("tamat", true)) MangaState.FINISHED else MangaState.ONGOING
        )
    }

    private fun String.parseChapterNumber(): Float? {
        return Regex("""[0-9]+(\.[0-9]+)?""").find(this)?.value?.toFloatOrNull()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

        return doc.select("img.wp-manga-chapter-img, .reading-content img, .page-break img, .chapter-image img")
            .mapNotNull { img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                if (url.isNotBlank() && !url.contains("placeholder")) {
                    MangaPage(
                        id = generateUid(url),
                        url = url.toAbsoluteUrl(domain),
                        preview = null,
                        source = source
                    )
                } else null
            }
    }
}
