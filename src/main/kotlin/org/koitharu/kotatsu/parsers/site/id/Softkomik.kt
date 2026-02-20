package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLEncoder
import java.util.*

@MangaSourceParser("SOFTKOMIK", "SoftKomik", "id")
internal class SoftKomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val url = if (filter.query.isNullOrBlank()) {
            "https://$domain/komik/list?page=$page"
        } else {
            val q = URLEncoder.encode(filter.query, "UTF-8")
            "https://$domain/komik/list?name=$q&page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".list-update_item, .komik-item, div.bs").mapNotNull {

            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                title = it.selectFirst(".title, h3, .tt")?.text().orEmpty(),
                coverUrl = it.selectFirst("img")?.src(),
                rating = RATING_UNKNOWN,
                altTitles = emptySet(),
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val chapters = doc.select(
            ".list-chapter ul li, #chapterlist ul li, .cl ul li"
        ).mapChapters(reversed = true) { index, li ->

            val a = li.selectFirst("a") ?: return@mapChapters null
            val url = a.attrAsRelativeUrl("href")

            MangaChapter(
                id = generateUid(url),
                url = url,
                title = a.text(),
                number = index + 1f,
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source
            )
        }

        return manga.copy(
            description = doc.select(".entry-content p, .synopsis p").text(),
            chapters = chapters,
            state = if (doc.select(".status").text()
                    .contains("ongoing", true)
            ) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        return doc.select(".reader-area img, #readerarea img").map {
            val img = it.requireSrc().toRelativeUrl(domain)

            MangaPage(
                id = generateUid(img),
                url = img,
                preview = null,
                source = source
            )
        }
    }
}
