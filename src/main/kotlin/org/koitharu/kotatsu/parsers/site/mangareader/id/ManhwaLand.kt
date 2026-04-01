package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANHWALAND", "ManhwaLand.vip", "id", ContentType.HENTAI)
internal class ManhwaLand(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWALAND, "www.manhwaland.baby", pageSize = 20, searchPageSize = 10) {

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isTagsExclusionSupported = false)

    override val datePattern = "MMM d, yyyy"

override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

    return doc.select("img[data-src], img[src]").mapNotNull { img ->
        var url = img.attr("data-src").ifBlank { img.attr("src") }
        if (url.isBlank()) return@mapNotNull null

        // Paksa HTTPS + tambah referer lewat interceptor sudah ada
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) url = "https://img.manhwaland.email$url"

        MangaPage(
            id = generateUid(url),
            url = url,
            preview = null,
            source = source,
        )
    }.distinctBy { it.url }
}

    // Interceptor lebih kuat
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("manhwaland")) {
            val newRequest = request.newBuilder()
                .addHeader("Origin", "https://www.manhwaland.baby/")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .addHeader("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
