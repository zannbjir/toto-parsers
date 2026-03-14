package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "https://$domain/")
        .build()

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = setOf(
            MangaTag("3", "Action", source), MangaTag("106", "Adult", source),
            MangaTag("8", "Adventure", source), MangaTag("4", "Comedy", source),
            MangaTag("357", "Dark Battle", source), MangaTag("13", "Drama", source),
            MangaTag("9", "Ecchi", source), MangaTag("347", "Elf", source),
            MangaTag("10", "Fantasy", source), MangaTag("14", "Harem", source),
            MangaTag("101", "Historical", source), MangaTag("60", "Horror", source),
            MangaTag("345", "Isekai", source), MangaTag("346", "Magic", source),
            MangaTag("303", "Martial Arts", source), MangaTag("5", "Mature", source),
            MangaTag("351", "Monster", source), MangaTag("61", "Mystery", source),
            MangaTag("348", "Overpowered Protagonist", source), MangaTag("122", "Psychological", source),
            MangaTag("11", "Romance", source), MangaTag("31", "School Life", source),
            MangaTag("40", "Sci-fi", source), MangaTag("6", "Seinen", source),
            MangaTag("223", "Shoujo", source), MangaTag("16", "Shounen", source),
            MangaTag("57", "Slice of Life", source), MangaTag("358", "Spin-off", source),
            MangaTag("354", "Spirit Realm", source), MangaTag("160", "Sports", source),
            MangaTag("25", "Supernatural", source), MangaTag("7", "Tragedy", source),
            MangaTag("237", "Yaoi", source)
        )
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/$listUrl".toHttpUrl().newBuilder().apply {
            if (page > 0) addPathSegments("page/${page + 1}/")
            filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("s", it) }
            filter.tags.forEach { addQueryParameter("genre[]", it.key) }
        }.build()
        return parseMangaList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
    }
}
