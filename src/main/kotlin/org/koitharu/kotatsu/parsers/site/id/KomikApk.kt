package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikAPK", "id")
internal class KomikAPK(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKAPK, 20) {

    override val configKeyDomain = ConfigKey.Domain("komikapk.app")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchTags()
        )
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return try {
            val url = when {
                !filter.query.isNullOrEmpty() -> {
                    "https://$domain/pencarian?q=${filter.query.urlEncoded()}&is-adult=on"
                }
                else -> {
                    val genre = if (filter.tags.size == 1) {
                        filter.tags.first().key
                    } else {
                        "semua"
                    }

                    val sort = when (order) {
                        SortOrder.NEWEST -> "terbaru"
                        SortOrder.POPULARITY -> "populer"
                        SortOrder.RATING -> "rating"
                        else -> "terbaru"
                    }

                    "https://$domain/pustaka/semua/$genre/$sort/$page?include_adult=true"
                }
            }

            val doc = webClient.httpGet(url).parseHtml()
            
            doc.select("a[href^=\"/komik/\"]").mapNotNull { link ->
                try {
                    val href = link.attr("href")
                    val slug = href.removePrefix("/komik/").substringBefore("?").split("/").first()
                    
                    if (slug.isBlank()) return@mapNotNull null

                    val img = link.selectFirst("img")?.absUrl("src") ?: return@mapNotNull null
                    if (img.isBlank()) return@mapNotNull null
                    val title = link.selectFirst("h3")?.text()?.trim()
                        ?: link.select("div").lastOrNull()?.text()?.trim()
                        
                    if (title.isNullOrBlank()) return@mapNotNull null
                    
                    Manga(
                        id = generateUid(slug),
                        title = title,
                        altTitles = emptySet(),
                        url = "/komik/$slug",
                        publicUrl = "https://$domain/komik/$slug",
                        rating = RATING_UNKNOWN,
                        contentRating = null,
                        coverUrl = img,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val doc = webClient.httpGet(manga.publicUrl).parseHtml()
            val title = doc.selectFirst("h1.font-label")?.text()?.trim() ?: manga.title
            val description = doc.select("div.font-display").firstOrNull { 
                it.text().length > 50 
            }?.text()?.trim()
            val tags = doc.select("a[href*='/pustaka/semua/'][href*='/terbaru/']").mapNotNull { link ->
                val genre = link.text().trim()
                val href = link.attr("href")
                val key = href.substringAfter("/pustaka/semua/").substringBefore("/terbaru")
                
                if (genre.isNotEmpty() && key.isNotEmpty()) {
                    MangaTag(key, genre, source)
                } else null
            }.toSet()

            val chapters = doc.select("a.btn.join-itemm[href^=\"/komik/\"]").mapNotNull { link ->
                val href = link.attr("href")
                val parts = href.removePrefix("/komik/").split("/")
                if (parts.size < 3) return@mapNotNull null
                
                val chapterNum = parts.lastOrNull()?.toFloatOrNull() ?: return@mapNotNull null
                val uploader = parts.getOrNull(1) ?: return@mapNotNull null
                val chapterTitle = link.selectFirst("div")?.text()?.trim() 
                    ?: "Chapter $chapterNum"
                
                if (chapterTitle.isBlank()) return@mapNotNull null
                
                MangaChapter(
                    id = generateUid(href),
                    title = chapterTitle,
                    url = href,
                    number = chapterNum,
                    volume = 0,
                    scanlator = uploader,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            }.reversed()

            manga.copy(
                title = title,
                description = description,
                tags = tags,
                chapters = chapters
            )
        } catch (e: Exception) {
            manga.copy(chapters = emptyList())
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return try {
            val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
            
            doc.select("section img[src*='komikapk-chapter']").mapNotNull { img ->
                val imageUrl = img.absUrl("src").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                
                if (imageUrl.isBlank()) return@mapNotNull null

                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return try {
            val doc = webClient.httpGet("https://$domain/pustaka/semua/semua/terbaru/1?include_adult=true")
                .parseHtml()
            val tags = doc.select("dialog#modal_genres a[href*='/pustaka/semua/'][href*='/terbaru/']")
                .distinctBy { it.attr("href") }
                .mapNotNull { link ->
                    val genre = link.text().trim()
                    val href = link.attr("href")
                    val key = href.substringAfter("/pustaka/semua/").substringBefore("/terbaru")
                    
                    if (genre.isNotEmpty() && key.isNotEmpty()) {
                        MangaTag(key, genre, source)
                    } else null
                }.toSet()
            
            if (tags.isEmpty()) {
                doc.select("a[href*='/pustaka/semua/'][href*='/terbaru/']")
                    .distinctBy { it.attr("href") }
                    .mapNotNull { link ->
                        val genre = link.text().trim()
                        val href = link.attr("href")
                        val key = href.substringAfter("/pustaka/semua/").substringBefore("/terbaru")
                        
                        if (genre.isNotEmpty() && key.isNotEmpty()) {
                            MangaTag(key, genre, source)
                        } else null
                    }.toSet()
            } else {
                tags
            }
        } catch (e: Exception) {
            setOf(
                MangaTag("action", "Action", source),
                MangaTag("comedy", "Comedy", source),
                MangaTag("drama", "Drama", source),
                MangaTag("fantasy", "Fantasy", source),
                MangaTag("horror", "Horror", source),
                MangaTag("manga", "Manga", source),
                MangaTag("manhwa", "Manhwa", source),
                MangaTag("manhua", "Manhua", source),
                MangaTag("romance", "Romance", source),
                MangaTag("school-life", "School Life", source),
                MangaTag("supernatural", "Supernatural", source),
                MangaTag("yuri", "Yuri", source)
            )
        }
    }
}
