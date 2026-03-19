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

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

    private suspend fun getApiHeaders(): okhttp3.Headers {
        val baseHeaders = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)",
            "Origin" to "https://$domain",
            "Referer" to "https://$domain/"
        ).toHeaders()

        var token = ""
        var sign = ""

        try {
            webClient.httpGet("https://$domain/", baseHeaders)
            
            val responseText = webClient.httpGet("https://$domain/api/sessions", baseHeaders).body?.string()
            if (!responseText.isNullOrEmpty()) {
                val json = JSONObject(responseText)
                token = json.optString("token", "")
                sign = json.optString("sign", "")
            }
        } catch (e: Exception) {}

        return mapOf(
            "Accept" to "application/json",
            "Origin" to "https://$domain",
            "Referer" to "https://$domain/",
            "X-Token" to token,
            "X-Sign" to sign,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        ).toHeaders()
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrEmpty()
        
        val url = "$apiUrl/komik".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "20")
            addQueryParameter("page", (page + 1).toString())
            if (isSearch) {
                addQueryParameter("name", filter.query)
                addQueryParameter("search", "true")
            } else {
                addQueryParameter("sortBy", if (order == SortOrder.POPULARITY) "popular" else "newKomik")
            }
        }.build()

        val jsonResponse = webClient.httpGet(url, getApiHeaders()).parseJson()
        
        val dataArray = jsonResponse.optJSONArray("data") 
            ?: jsonResponse.optJSONObject("data")?.optJSONArray("data") 
            ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until dataArray.length()) {
            val jo = dataArray.getJSONObject(i)
            val slug = jo.optString("title_slug", "")
            if (slug.isEmpty()) continue
            
            val img = jo.optString("gambar", "").removePrefix("/")

            mangaList.add(Manga(
                id = generateUid(slug),
                title = jo.optString("title", "Untitled").trim(),
                altTitles = emptySet(),
                url = "/komik/$slug",
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
        val authHeaders = getApiHeaders()
        val slug = manga.url.substringAfterLast("/")
        
        val detailUrl = "$apiUrl/komik/$slug"
        
        val detailResponse = webClient.httpGet(detailUrl, authHeaders).body?.string() ?: return manga
        val detailJson = try { JSONObject(detailResponse) } catch (e: Exception) { return manga }
        val detailData = detailJson.optJSONObject("data") ?: return manga
        
        val chapterUrl = "$apiUrl/komik/$slug/chapter?limit=9999"
        val chapterResponse = webClient.httpGet(chapterUrl, authHeaders).body?.string()
        val chaptersArray = try { JSONObject(chapterResponse ?: "{}").optJSONArray("chapter") ?: JSONArray() } catch (e: Exception) { JSONArray() }

        val tags = mutableSetOf<MangaTag>()
        detailData.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i)
                if (name.isNotBlank()) tags.add(MangaTag(name, name, source))
            }
        }

        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val chNumStr = ch.optString("chapter", "0")
            val chNum = chNumStr.toFloatOrNull() ?: 0f
            
            chapters.add(MangaChapter(
                id = generateUid("${manga.url}-$chNumStr"),
                title = "Chapter $chNumStr",
                url = "${manga.url}/chapter/$chNumStr",
                number = chNum,
                uploadDate = 0L,
                source = source,
                scanlator = "",
                branch = null,
                volume = 0
            ))
        }

        val statusStr = detailData.optString("status", "")
        val state = if (statusStr.equals("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED

        return manga.copy(
            description = detailData.optString("sinopsis", ""),
            tags = tags,
            authors = setOfNotNull(detailData.optString("author").takeIf { it.isNotBlank() }),
            state = state,
            chapters = chapters.sortedBy { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val authHeaders = getApiHeaders()
        
        val segments = chapter.url.split("/")
        if (segments.size < 4) return emptyList()
        val slug = segments[2]
        val chNum = segments[4]
        
        val imgApiUrl = "$apiUrl/komik/$slug/chapter/$chNum"
        
        val responseBody = webClient.httpGet(imgApiUrl, authHeaders).body?.string() ?: return emptyList()
        val jsonResponse = try { JSONObject(responseBody) } catch (e: Exception) { return emptyList() }
        
        val chapterData = jsonResponse.optJSONObject("data") ?: return emptyList()
        val imagesArray = chapterData.optJSONArray("imageSrc") ?: JSONArray()
        
        val isInter2 = chapterData.optBoolean("storageInter2", false)
        val host = if (isInter2) cdnUrls[3] else cdnUrls[1]

        val pages = mutableListOf<MangaPage>()
        for (i in 0 until imagesArray.length()) {
            val imgPath = imagesArray.getString(i).removePrefix("/")
            val fullUrl = "$host/$imgPath"
            pages.add(MangaPage(generateUid(fullUrl), fullUrl, null, source))
        }
        return pages
    }
}
