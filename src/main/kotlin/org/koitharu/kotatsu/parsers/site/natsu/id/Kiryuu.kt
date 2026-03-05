package org.koitharu.kotatsu.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

    override val configKeyDomain = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain("kiryuu.online")

    override suspend fun loadChapters(mangaId: String, mangaAbsoluteUrl: String): List<MangaChapter> {
        return super.loadChapters(mangaId, mangaAbsoluteUrl).map { 
            it.copy(scanlator = null, branch = null, volume = 0) 
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain))
        val doc = response.parseHtml()
        
        return doc.select("#readerarea img").mapNotNull { img ->
            val imgUrl = img.src()
            
            if (imgUrl.isNullOrBlank() || imgUrl.contains("loading")) {
                return@mapNotNull null
            }

            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source
            )
        }
    }
}
