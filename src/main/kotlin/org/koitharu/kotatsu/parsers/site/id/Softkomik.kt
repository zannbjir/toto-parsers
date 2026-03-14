package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverCdn = "https://cover.softdevices.my.id/softkomik-cover"
    private val cdnUrls = listOf(
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik"
    )

    private var sessionToken: String? = null
    private var sessionSign: String? = null
    private var sessionExpiry: Long = 0

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    private suspend fun updateSession() {
        if (System.currentTimeMillis() < sessionExpiry && sessionToken != null) return

        val headers = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$domain/"
        ).toHeaders()

        try {
            webClient.httpGet("https://$domain/", headers)
            val response = webClient.httpGet("https://$domain/api/sessions", headers).parseJson()
            sessionToken = response.getString("token")
            sessionSign = response.getString("sign")
            sessionExpiry = response.getLong("ex")
        } catch (e: Exception) {
            // Fallback jika session gagal
        }
    }

    private suspend fun getAuthHeaders(): okhttp3.Headers {
        updateSession()
        return mapOf(
            "Accept" to "application/json",
            "Origin" to "https://$domain",
            "Referer" to "https://$domain/",
            "X-Token" to (sessionToken ?: ""),
            "X-Sign" to (sessionSign ?: ""),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        ).toHeaders()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$apiUrl/komik".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "20")
            addQueryParameter("page", (page + 1).toString())
            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("name", filter.query)
                addQueryParameter("search", "true")
            } else {
                addQueryParameter("sortBy", if (order == SortOrder.POPULARITY) "popular" else "newKomik")
            }
        }.build()

        val jsonResponse = webClient.httpGet(url, getAuthHeaders()).parseJson()
        val data = jsonResponse.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val jo = data.getJSONObject(i)
            val slug = jo.getString("title_slug")
            val img = jo.getString("gambar").removePrefix("/")

            mangaList.add(Manga(
                id = generateUid(slug),
                title = jo.getString("title").trim(),
                altTitles = emptySet(),
                url = slug,
                publicUrl = "https://$domain/komik/$slug",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = "$coverCdn/$img",
                tags = emptySet(),
                state = if (jo.optString("status").contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
                authors = emptySet(),
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val authHeaders = getAuthHeaders()
        val detailJson = webClient.httpGet("$apiUrl/komik/${manga.url}", authHeaders).parseJson().optJSONObject("data") ?: return manga
        
        val chapterUrl = "$apiUrl/komik/${manga.url}/chapter?limit=9999"
        val chaptersArray = webClient.httpGet(chapterUrl, authHeaders).parseJson().optJSONArray("chapter") ?: JSONArray()

        val tags = mutableSetOf<MangaTag>()
        detailJson.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.getString(i)
                tags.add(MangaTag(name.lowercase().replace(" ", "-"), name, source))
            }
        }

        val chapters = (0 until chaptersArray.length()).map { i ->
            val ch = chaptersArray.getJSONObject(i)
            val chNum = ch.getString("chapter")
            MangaChapter(
                id = generateUid("${manga.url}-$chNum"),
                title = "Chapter $chNum",
                url = "/komik/${manga.url}/chapter/$chNum",
                number = chNum.toFloatOrNull() ?: 0f,
                uploadDate = 0L,
                source = source,
                scanlator = null,
                branch = null,
                volume = 0
            )
        }

        return manga.copy(
            description = detailJson.optString("sinopsis"),
            tags = tags,
            authors = setOf(detailJson.optString("author")),
            chapters = chapters.sortedByDescending { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val authHeaders = getAuthHeaders()
        val doc = webClient.httpGet("https://$domain${chapter.url}", authHeaders).parseHtml()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { JSONObject(it) } ?: return emptyList()
        val props = nextData.getJSONObject("props").getJSONObject("pageProps")
        val data = props.optJSONObject("data") ?: props

        var images = data.optJSONArray("imageSrc") ?: JSONArray()
        if (images.length() == 0) {
            val id = data.optString("_id")
            val slug = chapter.url.split("/")[2]
            val chNum = chapter.url.split("/")[4]
            val imgApiUrl = "$apiUrl/komik/$slug/chapter/$chNum/img/$id"
            images = webClient.httpGet(imgApiUrl, authHeaders).parseJson().optJSONArray("imageSrc") ?: JSONArray()
        }

        val host = if (data.optBoolean("storageInter2")) cdnUrls[3] else cdnUrls[1]

        return (0 until images.length()).map { i ->
            val fullUrl = "$host/${images.getString(i).removePrefix("/")}"
            MangaPage(generateUid(fullUrl), fullUrl, null, source)
        }
    }
}
