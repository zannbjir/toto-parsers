package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.KAGUYA, "kaguya.my.id") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "dd MMMM yyyy"
    override val withoutAjax = false

    override fun getRequestHeaders(): okhttp3.Headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/",
        "X-Requested-With" to "XMLHttpRequest"
    ).toHeaders()

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        
        val ajaxUrl = "https://$domain${manga.url.removeSuffix("/")}/ajax/chapters/"
        val chapterDoc = webClient.httpPost(ajaxUrl, emptyMap<String, String>(), getRequestHeaders()).parseHtml()
        
        val chapters = chapterDoc.select("li.wp-manga-chapter").mapIndexed { i, element ->
            val link = element.selectFirst("a")!!
            val href = link.attrAsRelativeUrl("href")
            val name = link.text().trim()
            
            MangaChapter(
                id = generateUid(href),
                title = name,
                url = href,
                number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
                uploadDate = 0L, 
                source = source,
                scanlator = null,
                branch = null,
                volume = 0
            )
        }

        return manga.copy(
            description = doc.select(".summary__content").text().trim(),
            chapters = chapters.reversed(),
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        return doc.select(".reading-content img").mapNotNull { img ->
            val url = img.src() ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }
}
