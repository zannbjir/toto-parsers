package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("mangakuri", "Mangakuri", "id")
class Mangakuri(context: MangaLoaderContext) : HttpMangaParser(context) {

    override val baseUrl = "https://mangakuri.org"
    private val apiUrl = "https://api.mangakuri.org/api"

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ================= FILTERS (mirip Mihon) =================
    private val orderFilter = SelectFilter(
        "Order",
        listOf(
            FilterValue("DESC", "desc"),
            FilterValue("ASC", "asc"),
        )
    )

    private val sortFilter = SelectFilter(
        "Sort By",
        listOf(
            FilterValue("New", "new"),
            FilterValue("Top Views", "views"),
            FilterValue("Top Rate", "rate"),
            FilterValue("Top Bookmark", "bookmark"),
            FilterValue("Title A-Z", "az"),
            FilterValue("Title Z-A", "za"),
        )
    )

    private val statusFilter = SelectFilter(
        "Status",
        listOf(
            FilterValue("All", ""),
            FilterValue("Ongoing", "ONGOING"),
            FilterValue("Completed", "COMPLETED"),
            FilterValue("Hiatus", "HIATUS"),
        )
    )

    private val typeFilter = SelectFilter(
        "Type",
        listOf(
            FilterValue("All", ""),
            FilterValue("Manga", "MANGA"),
            FilterValue("Manhwa", "MANHWA"),
            FilterValue("Manhua", "MANHUA"),
        )
    )

    private val colorFilter = SelectFilter(
        "Color",
        listOf(
            FilterValue("All", ""),
            FilterValue("Full Color", "FULL_COLOR"),
            FilterValue("B&W", "BW"),
        )
    )

    private val readingFilter = SelectFilter(
        "Reading",
        listOf(
            FilterValue("All", ""),
            FilterValue("Vertical Scroll", "VERTICAL_SCROLL"),
            FilterValue("Page", "PAGE"),
        )
    )

    private val genreFilter = SelectFilter(
        "Genre",
        listOf(
            FilterValue("All", ""),
            FilterValue("Action", "action"),
            FilterValue("Adult", "adult"),
            FilterValue("Adventure", "adventure"),
            FilterValue("Aksi", "aksi"),
            FilterValue("Arts", "arts"),
            FilterValue("Bl", "bl"),
            FilterValue("Boys Love", "boys-love"),
            FilterValue("Boyslove", "boyslove"),
            FilterValue("Comedy", "comedy"),
            FilterValue("Crybaby Seme", "crybaby-seme"),
            FilterValue("Dll", "dll"),
            FilterValue("Drama", "drama"),
            FilterValue("Fantasi Modern", "fantasi-modern"),
            FilterValue("Fantasy", "fantasy"),
            FilterValue("Gender Bender", "gender-bender"),
            FilterValue("Gong Bucin", "gong-bucin"),
            FilterValue("Gong Lebih Tua", "gong-lebih-tua"),
            FilterValue("Historical", "historical"),
            FilterValue("Horror", "horror"),
            FilterValue("Investigasi Kasus", "investigasi-kasus"),
            FilterValue("Investigasikasus", "investigasikasus"),
            FilterValue("Isekai", "isekai"),
            FilterValue("Martial", "martial"),
            FilterValue("Martial Arts", "martial-arts"),
            FilterValue("Mature", "mature"),
            FilterValue("Mystery", "mystery"),
            FilterValue("Napolitana Ghost Story", "napolitana-ghost-story"),
            FilterValue("Office", "office"),
            FilterValue("Okultisme", "okultisme"),
            FilterValue("Psychological", "psychological"),
            FilterValue("Reincarnation", "reincarnation"),
            FilterValue("Revenge", "revenge"),
            FilterValue("Romance", "romance"),
            FilterValue("Royalty", "royalty"),
            FilterValue("Salvation", "salvation"),
            FilterValue("School Life", "school-life"),
            FilterValue("Sci-Fi", "sci-fi"),
            FilterValue("Shounen", "shounen"),
            FilterValue("Shounen Ai", "shounen-ai"),
            FilterValue("Slice Of Life", "slice-of-life"),
            FilterValue("Smut", "smut"),
            FilterValue("Smut Supernatural Yaoi", "smut-supernatural-yaoi"),
            FilterValue("Su Aktif", "su-aktif"),
            FilterValue("Su Cinta Bertepuk Sebelah Tangan", "su-cinta-bertepuk-sebelah-tangan"),
            FilterValue("Su Luka Masa Lalu", "su-luka-masa-lalu"),
            FilterValue("Su Menggemaskan", "su-menggemaskan"),
            FilterValue("Supernatural", "supernatural"),
            FilterValue("Xianxia", "xianxia"),
            FilterValue("Yaoi", "yaoi"),
            FilterValue("Yaoi Bl", "yaoi-bl"),
        )
    )

    // ================= LIST (Popular / Latest / Search + Filter) =================
    override suspend fun getListPage(page: Int, query: String?, filters: List<Filter>): List<Manga> {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("limit", "20")
            .addQueryParameter("page", page.toString())

        if (!query.isNullOrEmpty()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> {
                    val value = filter.selectedValue()
                    when (filter.name) {
                        "Sort By" -> url.addQueryParameter("sort", value)
                        "Order" -> url.addQueryParameter("order", value)
                        "Status" -> if (value.isNotEmpty()) url.addQueryParameter("status", value)
                        "Genre" -> if (value.isNotEmpty()) url.addQueryParameter("genre", value)
                        "Type" -> if (value.isNotEmpty()) url.addQueryParameter("comic_type", value)
                        "Color" -> if (value.isNotEmpty()) url.addQueryParameter("color_format", value)
                        "Reading" -> if (value.isNotEmpty()) url.addQueryParameter("reading_format", value)
                    }
                }
                is TextFilter -> {
                    if (filter.state.isNotEmpty()) url.addQueryParameter(filter.queryKey, filter.state)
                }
            }
        }

        val response = webClient.httpGet(url.build()).parseJson<SearchResponseDto>()
        return response.data.map { manga ->
            Manga(
                id = generateUid(manga.slug),
                title = manga.title,
                url = "/comic/${manga.slug}",
                publicUrl = "$baseUrl/comic/${manga.slug}",
                coverUrl = manga.posterImageUrl,
                source = source,
            )
        }
    }

    // ================= DETAIL =================
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/")
        val url = "$apiUrl/series/$slug".toHttpUrl()
        val dto = webClient.httpGet(url).parseJson<SeriesDetailDto>()

        return manga.copy(
            title = dto.title,
            coverUrl = dto.posterImageUrl,
            author = dto.authorName,
            artist = dto.artistName,
            description = dto.synopsis?.let { Jsoup.parse(it).text() },
            tags = dto.genres.map { it.name }.toSet(),
            status = when (dto.comicStatus?.uppercase()) {
                "ONGOING" -> MangaStatus.ONGOING
                "COMPLETED" -> MangaStatus.COMPLETED
                "HIATUS" -> MangaStatus.ON_HIATUS
                else -> MangaStatus.UNKNOWN
            }
        )
    }

    // ================= CHAPTERS =================
    override suspend fun getChapters(manga: Manga): List<Chapter> {
        val slug = manga.url.removePrefix("/comic/")
        val url = "$apiUrl/series/$slug".toHttpUrl()
        val dto = webClient.httpGet(url).parseJson<SeriesDetailDto>()

        return dto.units.map { chapter ->
            Chapter(
                id = generateUid(chapter.slug),
                title = "Chapter ${formatChapterNumber(chapter.number)}",
                number = chapter.number.toFloatOrNull() ?: 0f,
                url = "/comic/${dto.slug}/chapter/${chapter.slug}",
                publicUrl = "$baseUrl/comic/${dto.slug}/chapter/${chapter.slug}",
                uploadDate = dateFormat.tryParse(chapter.createdAt),
                source = source,
            )
        }
    }

    private fun formatChapterNumber(number: String): String = number.removeSuffix(".00")

    // ================= PAGES =================
    override suspend fun getPages(chapter: Chapter): List<MangaPage> {
        val url = "$apiUrl/series${chapter.url}".toHttpUrl()
        val dto = webClient.httpGet(url).parseJson<ChapterDetailDto>()

        return dto.chapter.pages.map { page ->
            MangaPage(
                url = page.imageUrl,
                referer = baseUrl
            )
        }
    }

    // ================= DTOs (sama seperti Mihon) =================
    @Serializable
    class SearchResponseDto(
        val data: List<MangaDto>,
        @SerialName("total_pages") val totalPages: Int,
    )

    @Serializable
    class MangaDto(
        val title: String,
        val slug: String,
        @SerialName("poster_image_url") val posterImageUrl: String? = null,
    )

    @Serializable
    class SeriesDetailDto(
        val title: String,
        val slug: String,
        val synopsis: String? = null,
        @SerialName("poster_image_url") val posterImageUrl: String? = null,
        @SerialName("comic_status") val comicStatus: String? = null,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        val genres: List<GenreDto> = emptyList(),
        val units: List<ChapterDto> = emptyList(),
    )

    @Serializable
    class GenreDto(val name: String)

    @Serializable
    class ChapterDto(
        val slug: String,
        val number: String,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    class ChapterDetailDto(
        val chapter: ChapterPagesDto,
    )

    @Serializable
    class ChapterPagesDto(
        val pages: List<PageDto>,
    )

    @Serializable
    class PageDto(
        @SerialName("image_url") val imageUrl: String,
    )
}
