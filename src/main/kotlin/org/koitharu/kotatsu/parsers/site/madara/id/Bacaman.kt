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
    override val withoutAjax = true
    override val listUrl = "manga/"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://bacaman.id/"
    ).toHeaders()

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".page-item-detail, .manga-item").mapNotNull { item ->
            val link = item.selectFirst(".post-title a, h3 a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            val title = link.text().trim()
            val cover = item.selectFirst("img")?.src() ?: ""

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
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
        val response = webClient.httpGet(manga.publicUrl, headers)
        val doc = response.parseHtml()

        val chapters = mutableListOf<MangaChapter>()
        doc.select("li.wp-manga-chapter").forEachIndexed { i, element ->
            val link = element.selectFirst("a")
            if (link != null) {
                val href = link.attrAsRelativeUrl("href")
                val name = link.text().trim()
                chapters.add(MangaChapter(
                    id = generateUid(href),
                    title = name,
                    url = href,
                    number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
                    uploadDate = 0L,
                    source = source,
                    scanlator = null,
                    branch = null,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            title = doc.selectFirst(".post-title h1")?.text()?.trim() ?: manga.title,
            description = doc.select(".description-summary, .summary__content").text().trim(),
            tags = doc.select(".genres-content a").map { 
                MangaTag(key = it.attr("href").substringAfterLast("/").ifEmpty { "genre" }, title = it.text(), source = source) 
            }.toSet(),
            chapters = chapters.reversed(),
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), headers)
        val doc = response.parseHtml()
        
        return doc.select(".reading-content img, .page-break img").mapNotNull { img ->
            val url = img.src() ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
