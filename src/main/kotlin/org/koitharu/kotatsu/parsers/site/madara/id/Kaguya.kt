package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Base64
import java.util.Locale

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.KAGUYA, "01.kaguya.pro", pageSize = 20) {

    override val sourceLocale: Locale = Locale("id")

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain").parseHtml()
        val tags = doc.select(".genres__collapse ul.list-unstyled a").mapNotNull {
            val name = it.text().replace(Regex("""\(\d+\)"""), "").trim()
            val value = it.attrAsRelativeUrl("href").removeSuffix("/").substringAfterLast("/")
            if (name.isNotBlank() && value.isNotBlank()) MangaTag(title = name, key = value, source = source) else null
        }.toSet()

        return MangaListFilterOptions(availableTags = tags, availableStates = emptySet(), availableContentTypes = emptySet())
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain)
            if (!filter.query.isNullOrEmpty()) {
                append("/page/").append(page).append("/?s=").append(filter.query.urlEncoded()).append("&post_type=wp-manga")
            } else if (filter.tags.isNotEmpty()) {
                val tag = filter.tags.first()
                append("/series-genre/").append(tag.key).append("/page/").append(page).append("/")
            } else {
                append("/series/page/").append(page).append("/")
            }
        }

        val docs = webClient.httpGet(url).parseHtml()
        return docs.select("div.manga__item").mapNotNull {
            val a = it.selectFirst("h2 a") ?: return@mapNotNull null
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                title = a.text().trim(),
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = it.selectFirst("div.manga__thumb img")?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val baseManga = super.getDetails(manga)
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val parsedAuthor = docs.selectFirst("div.author-content a")?.text()
        val parsedDescription = docs.select("div.summary__content p").joinToString("\n") { it.text() }
        
        val parsedTags = docs.select("div.genres-content a").mapNotNull {
            val tagText = it.text().trim()
            if (tagText.isNotBlank()) MangaTag(title = tagText, key = tagText, source = source) else null
        }.toSet()
        
        val parsedStatus = docs.selectFirst("div.post-content_item:has(h5:contains(Status)) div.summary-content")?.text()?.trim()
        val state = if (parsedStatus?.contains("OnGoing", ignoreCase = true) == true) {
            MangaState.ONGOING
        } else if (parsedStatus?.contains("Completed", ignoreCase = true) == true || parsedStatus?.contains("End", ignoreCase = true) == true) {
            MangaState.FINISHED
        } else {
            null
        }

        return baseManga.copy(
            description = parsedDescription.takeIf { it.isNotBlank() } ?: baseManga.description,
            authors = setOfNotNull(parsedAuthor).ifEmpty { baseManga.authors },
            tags = parsedTags.ifEmpty { baseManga.tags },
            state = state ?: baseManga.state,
            coverUrl = docs.selectFirst("div.summary_image img")?.src() ?: baseManga.coverUrl
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return docs.select(".reading-content .page-break img").mapNotNull { img ->
            val aesirData = img.attr("data-aesir")
            val imageUrl = if (aesirData.isNotBlank()) {
                String(Base64.getDecoder().decode(aesirData)).trim()
            } else {
                img.src()?.trim()
            }

            if (!imageUrl.isNullOrBlank()) {
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl.toRelativeUrl(domain),
                    preview = null,
                    source = source
                )
            } else {
                null
            }
        }
    }
}
