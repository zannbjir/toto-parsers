package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("BACAMAN", "Bacaman", "id")
internal class Bacaman(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.BACAMAN, "bacaman.id") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "MMMM dd, yyyy"
    
    override val withoutAjax = false
    override val listUrl = "manga/"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/",
        "X-Requested-With" to "XMLHttpRequest"
    ).toHeaders()

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".page-item-detail, .manga-item, .item-list").mapNotNull { item ->
            val link = item.selectFirst(".post-title a, h3 a, .title a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = item.selectFirst("img")?.src() ?: "",
                title = link.text().trim(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, commonHeaders).parseHtml()
        val chapters = loadChapters(manga.url, doc)

        return manga.copy(
            description = doc.select(".description-summary, .summary__content, .manga-excerpt").text().trim(),
            tags = doc.select(".genres-content a").map {
                MangaTag(
                    key = it.attr("href").substringAfterLast("/").ifEmpty { "genre" },
                    title = it.text().trim(),
                    source = source
                )
            }.toSet(),
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), commonHeaders).parseHtml()
        return doc.select(".reading-content img, .page-break img, .read-container img").mapNotNull { element ->
            val imageUrl = element.src() ?: return@mapNotNull null

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    override fun getFilters(): List<MangaListFilter> {
        return listOf(
            MangaListFilter.Select(
                "Genre",
                listOf(
                    MangaListFilter.Option("Action", "3"),
                    MangaListFilter.Option("Adult", "106"),
                    MangaListFilter.Option("Adventure", "8"),
                    MangaListFilter.Option("Comedy", "4"),
                    MangaListFilter.Option("Dark Battle", "357"),
                    MangaListFilter.Option("Drama", "13"),
                    MangaListFilter.Option("Ecchi", "9"),
                    MangaListFilter.Option("Elf", "347"),
                    MangaListFilter.Option("Fantasy", "10"),
                    MangaListFilter.Option("Harem", "14"),
                    MangaListFilter.Option("Historical", "101"),
                    MangaListFilter.Option("Horror", "60"),
                    MangaListFilter.Option("Isekai", "345"),
                    MangaListFilter.Option("Magic", "346"),
                    MangaListFilter.Option("Martial Arts", "303"),
                    MangaListFilter.Option("Mature", "5"),
                    MangaListFilter.Option("Monster", "351"),
                    MangaListFilter.Option("Mystery", "61"),
                    MangaListFilter.Option("Overpowered", "348"),
                    MangaListFilter.Option("Psychological", "122"),
                    MangaListFilter.Option("Romance", "11"),
                    MangaListFilter.Option("School Life", "31"),
                    MangaListFilter.Option("Sci-fi", "40"),
                    MangaListFilter.Option("Seinen", "6"),
                    MangaListFilter.Option("Shoujo", "223"),
                    MangaListFilter.Option("Shounen", "16"),
                    MangaListFilter.Option("Slice of Life", "57"),
                    MangaListFilter.Option("Spin-off", "358"),
                    MangaListFilter.Option("Spirit Realm", "354"),
                    MangaListFilter.Option("Sports", "160"),
                    MangaListFilter.Option("Supernatural", "25"),
                    MangaListFilter.Option("Tragedy", "7"),
                    MangaListFilter.Option("Yaoi", "237")
                )
            )
        )
    }
}
