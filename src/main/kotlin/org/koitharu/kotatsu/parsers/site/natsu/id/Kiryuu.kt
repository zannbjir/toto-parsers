package org.koitharu.kotatsu.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

    override val configKeyDomain = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain("kiryuu.online")

    override suspend fun loadChapters(mangaId: String, mangaAbsoluteUrl: String): List<MangaChapter> {
        val chapters = super.loadChapters(mangaId, mangaAbsoluteUrl)
        return chapters.map { it.copy(scanlator = null, branch = null, volume = 0) }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#readerarea img").filter { 
            val src = it.attr("src")
            src.isNotBlank() && !src.contains("loading") 
        }.map { img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
