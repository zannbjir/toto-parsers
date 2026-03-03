package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Request
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.GET
import org.koitharu.kotatsu.parsers.site.PagedMangaParser
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.THRIVE) {

    override val configKeyDomain = "thrive.moe"

    private fun getNextData(document: Document): JSONObject {
        val script = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Gagal mendapatkan data JSON")
        return JSONObject(script).getJSONObject("props").getJSONObject("pageProps")
    }

    override fun getListPage(page: Int, filter: MangaFilters): Request {
        return GET("https://$domain", headers)
    }

    override fun getSearchPage(page: Int, query: String, filter: MangaFilters): Request {
        return GET("https://$domain/search?q=${query.urlEncoded()}", headers)
    }

    override fun parseMangaList(document: Document): List<MangaCard> {
        val props = getNextData(document)
        val array = props.optJSONArray("terbaru")
            ?: props.optJSONArray("res")
            ?: props.optJSONArray("data")
            ?: return emptyList()

        val list = mutableListOf<MangaCard>()
        for (i in 0 until array.length()) {
            val jo = array.getJSONObject(i)
            val id = jo.getString("id")
            list.add(MangaCard(
                id = generateUid(id),
                title = jo.getString("title").trim(),
                url = "/title/$id",
                coverUrl = jo.optString("cover"),
                source = source
            ))
        }
        return list
    }

    override fun getDetailsRequest(manga: MangaCard): Request {
        return GET("https://$domain${manga.url}", headers)
    }

    override fun parseMangaDetails(document: Document, manga: MangaCard): MangaDetails {
        val props = getNextData(document)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val chaptersArray = props.optJSONArray("chapterlist")
        val chapters = mutableListOf<MangaChapter>()

        if (chaptersArray != null) {
            for (i in 0 until chaptersArray.length()) {
                val ch = chaptersArray.getJSONObject(i)
                val chId = ch.getString("chapter_id")
                val dateStr = ch.optString("created_at").substringBefore(".")
                val chNum = ch.optString("chapter_number").replace(",", ".").toFloatOrNull() ?: 0f

                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = "Chapter ${ch.optString("chapter_number")}",
                    url = "/read/$chId",
                    number = chNum,
                    uploadDate = try { sdf.parse(dateStr)?.time ?: 0L } catch (e: Exception) { 0L },
                    scanlator = ch.optString("scanlator", "Thrive"),
                    source = source,
                    volume = -1,
                    branch = null
                ))
            }
        }

        return MangaDetails(
            title = props.optString("title", manga.title),
            description = props.optString("desc_ID", props.optString("desc_EN", "")),
            coverUrl = props.optString("cover", manga.coverUrl),
            chapters = chapters,
            author = props.optString("author", "Unknown"),
            artist = props.optString("artist", "Unknown"),
            tags = props.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { MangaTag(arr.getString(it), arr.getString(it), source) }.toSet()
            } ?: emptySet(),
            state = if (props.optString("status").contains("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            source = source,
            isNsfw = false
        )
    }

    override fun getPagesRequest(chapter: MangaChapter): Request {
        return GET("https://$domain${chapter.url}", headers)
    }

    override fun parsePages(document: Document): List<String> {
        val props = getNextData(document)
        val prefix = props.optString("prefix")
        val images = props.optJSONArray("image") ?: return emptyList()

        val pages = mutableListOf<String>()
        for (i in 0 until images.length()) {
            pages.add("https://cdn.thrive.moe/data/$prefix/${images.getString(i)}")
        }
        return pages
    }
}
