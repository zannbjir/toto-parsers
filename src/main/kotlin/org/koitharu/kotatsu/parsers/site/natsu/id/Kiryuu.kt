package org.dokiteam.doki.parsers.site.natsu.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {
        
    override val configKeyDomain = ConfigKey.Domain("kiryuu.online")
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when {
            filter.query.isNotBlank() -> "https://$domain/page/$page/?s=${filter.query.urlEncoded()}"
            filter.tags.isNotEmpty() -> {
                val genre = filter.tags.first().title.lowercase().replace(" ", "-")
                "https://$domain/genres/$genre/page/$page/"
            }
            else -> if (page == 1) "https://$domain/manga/" else "https://$domain/manga/page/$page/"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".listupd .bs .bsx, .utao .uta").map { el ->
            val a = el.selectFirst("a")!!
            val mUrl = a.absUrl("href")
            Manga(
                id = generateUid(mUrl),
                title = (el.selectFirst(".tt, h2")?.text() ?: "").trim(),
                url = mUrl,
                coverUrl = el.selectFirst("img")?.absUrl("src") ?: "",
                source = source,
                state = MangaState.OK
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()
        val description = doc.select(".entry-content[itemprop=description], .sinopsis").text().trim()
        val genres = doc.select(".genwrap a, .mgen a").map { 
            MangaTag(generateUid(it.text()), it.text(), source) 
        }.toSet()

        return manga.copy(
            description = description,
            tags = genres
        )
    }

    override suspend fun loadChapters(mangaId: String, mangaAbsoluteUrl: String): List<MangaChapter> {
        val doc = webClient.httpGet(mangaAbsoluteUrl).parseHtml()
        return doc.select("#chapterlist li, .cl-item").map { el ->
            val a = el.selectFirst("a")!!
            val cUrl = a.absUrl("href")
            val title = el.select(".chapternum, .chapter-label").text().trim().ifEmpty { a.text() }
            
            MangaChapter(
                id = generateUid(cUrl),
                title = title,
                url = cUrl,
                number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: -1f,
                uploadDate = 0L,
                source = source
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        return doc.select("#readerarea img").filter { 
            val src = it.attr("src")
            src.isNotBlank() && !src.contains("loading") && !src.contains("banner")
        }.mapIndexed { index, img ->
            val imgUrl = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                source = source
            )
        }
    }
}
