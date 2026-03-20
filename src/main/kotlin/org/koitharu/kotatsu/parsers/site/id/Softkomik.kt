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

    private val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/"
    ).toHeaders()

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search?q=${filter.query.urlEncoded()}" + if (page > 0) "&page=${page + 1}" else ""
        } else {
            "https://$domain/komik/library".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", (page + 1).toString())
                addQueryParameter("sortBy", if (order == SortOrder.POPULARITY) "popular" else "newKomik")
            }.build().toString()
        }

        try { webClient.httpGet("https://$domain/", baseHeaders) } catch (e: Exception) {}
        
        val doc = webClient.httpGet(url, baseHeaders).parseHtml()
        
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val pageProps = try { JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList() } catch (e: Exception) { return emptyList() }

        val mangaArray = pageProps.optJSONArray("res") 
            ?: pageProps.optJSONArray("data") 
            ?: pageProps.optJSONArray("terbaru")
            ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until mangaArray.length()) {
            val jo = mangaArray.getJSONObject(i)
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
        val doc = webClient.httpGet(manga.publicUrl, baseHeaders).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga
        val pageProps = try { JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return manga } catch (e: Exception) { return manga }

        val detailData = pageProps.optJSONObject("manga") ?: pageProps.optJSONObject("res") ?: pageProps
        val chaptersArray = pageProps.optJSONArray("chapterlist") ?: pageProps.optJSONArray("chapters") ?: JSONArray()

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
            chapters = chapters.sortedByDescending { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val rscHeaders = baseHeaders.newBuilder().add("rsc", "1").build()
        val doc = webClient.httpGet("https://$domain${chapter.url}", rscHeaders).parseHtml()
        
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val nextData = try { JSONObject(scriptData) } catch (e: Exception) { return emptyList() }
        
        val pageProps = nextData.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
        val chapterData = pageProps.optJSONObject("data") ?: pageProps

        var imagesArray = chapterData.optJSONArray("imageSrc") ?: JSONArray()
        
        // Minta gambar dari API jika kosong
        if (imagesArray.length() == 0) {
            val id = chapterData.optString("_id")
            val segments = chapter.url.split("/")
            if (segments.size >= 4) {
                val slug = segments[2]
                val chNum = segments[4]
                val imgApiUrl = "$apiUrl/komik/$slug/chapter/$chNum/img/$id"
                
                try {
                    var token = ""
                    var sign = ""
                    val res = webClient.httpGet("https://$domain/api/sessions", mapOf("Accept" to "application/json").toHeaders()).body?.string()
                    if (!res.isNullOrEmpty()) {
                        val json = JSONObject(res)
                        token = json.optString("token", "")
                        sign = json.optString("sign", "")
                    }
                    
                    val apiHeaders = mapOf(
                        "Accept" to "application/json",
                        "X-Token" to token,
                        "X-Sign" to sign,
                        "Referer" to "https://$domain/"
                    ).toHeaders()
                    
                    val imgRes = webClient.httpGet(imgApiUrl, apiHeaders).body?.string()
                    val imgJson = JSONObject(imgRes ?: "{}")
                    imagesArray = imgJson.optJSONArray("imageSrc") ?: JSONArray()
                } catch (e: Exception) { }
            }
        }

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
