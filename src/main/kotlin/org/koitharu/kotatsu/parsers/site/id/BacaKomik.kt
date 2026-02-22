package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("BACAKOMIK", "BacaKomik", "id")
internal class BacaKomik(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.BACAKOMIK, "bacakomik.my") {
    
    override val listUrl = "/daftar-komik/"
    override val selectMangaList = "div.listupd div.bs"
    override val selectMangaListTitle = "div.tt"
    override val selectMangaListImg = "img"
    override val selectChapter = "div#chapterlist ul li"
    
    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .add("Referer", "https://bacakomik.my/")
            .build()
    }
}
