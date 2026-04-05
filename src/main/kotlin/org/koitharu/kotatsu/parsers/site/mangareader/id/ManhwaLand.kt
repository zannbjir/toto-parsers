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
internal class ManhwaLand(
    context: MangaLoaderContext
) : MangaReaderParser(
    context,
    MangaParserSource.MANHWALAND,
    "www.manhwaland.baby",
    pageSize = 20,
    searchPageSize = 10
) {

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false
        )

    override val datePattern = "MMM d, yyyy"

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return super.getPages(chapter).map { page ->
            page.copy(
                url = page.url.replace("http://", "https://")
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("manhwaland.email")) {
            val newRequest = request.newBuilder()
                .header("Referer", "https://www.manhwaland.baby/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
                )
                .header("Accept", "image/webp,image/*,*/*;q=0.8")
                .build()

            return chain.proceed(newRequest)
        }

        return chain.proceed(request)
    }
}
