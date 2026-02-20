package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
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
        SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        // Update parameter: menggunakan 'name' untuk search sesuai temuanmu
        val url = if (filter.query.isNullOrBlank()) {
            "https://$domain/komik/list?page=${page + 1}"
        } else {
            "https://$domain/komik/list?name=${filter.query.URLEncode()}&page=${page + 1}"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        // Selector disesuaikan untuk mengambil item di halaman /komik/list
        return doc.select(".list-update_item, .komik-item, div.bs").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val title = element.selectFirst(".title, h3, .tt")?.text()?.trim() ?: ""
            val cover = element.selectFirst("img")?.src()

            Manga(
                id = generateUid(href),
                url = href,
                title = title,
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        // Ambil list chapter agar tombol 'Baca' muncul di APK
        val chapters = doc.select(".list-chapter ul li, #chapterlist ul li, .cl ul li").mapChapters(reversed = true) { index, element ->
            val a = element.selectFirst("a") ?: return@mapChapters null
            val url = a.attrAsRelativeUrl("href")

            MangaChapter(
                id = generateUid(url),
                title = a.text().trim(),
                url = url,
                number = index + 1f,
                source = source
            )
        }

        return manga.copy(
            description = doc.select(".entry-content p, .synopsis p").text().trim(),
            chapters = chapters,
            state = if (doc.select(".status").text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        return doc.select(".reader-area img, #readerarea img").mapNotNull { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}