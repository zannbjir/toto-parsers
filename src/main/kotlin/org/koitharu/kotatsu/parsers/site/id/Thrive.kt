package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers.Companion.toHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://thrive.moe/"
    ).toHeaders()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search?route=${filter.query.urlEncoded()}"
        } else {
            if (page > 0) "https://$domain/?page=${page + 1}" else "https://$domain/"
        }

        val doc = webClient.httpGet(url, headers).parseHtml()
        val nextData = JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList())
        val pageProps = nextData.getJSONObject("props").getJSONObject("pageProps")
        
        val mangaArray = pageProps.optJSONArray("terbaru") ?: pageProps.optJSONArray("res") ?: return emptyList()

        return (0 until mangaArray.length()).mapNotNull { i ->
            val jo = mangaArray.getJSONObject(i)
            val id = jo.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Manga(
                id = generateUid(id),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                title = jo.optString("title", "Untitled"),
                coverUrl = jo.optString("cover"),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, headers).parseHtml()
        val data = JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").getJSONObject("props").getJSONObject("pageProps")

        val chapters = mutableListOf<MangaChapter>()
        data.optJSONArray("chapterlist")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val chId = ch.getString("chapter_id")
                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = ch.optString("chapter_title").ifEmpty { "Chapter ${ch.optString("chapter_number")}" },
                    url = "/read/$chId",
                    number = ch.optString("chapter_number").toFloatOrNull() ?: 0f,
                    uploadDate = parseDate(ch.optString("created_at")),
                    source = source
                ))
            }
        }

        return manga.copy(
            description = data.optString("desc_ID").ifEmpty { data.optJSONObject("desc")?.optString("id") },
            state = if (data.optString("status").contains("completed", true)) MangaState.FINISHED else MangaState.ONGOING,
            chapters = chapters.sortedByDescending { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", headers).parseHtml()
        val data = JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: "").getJSONObject("props").getJSONObject("pageProps")
        
        val prefix = data.optString("prefix")
        val images = data.getJSONArray("image")

        return (0 until images.length()).map { i ->
            val imageUrl = "https://cdn.thrive.moe/data/$prefix/${images.getString(i)}"
            MangaPage(generateUid(imageUrl), imageUrl, null, source)
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
