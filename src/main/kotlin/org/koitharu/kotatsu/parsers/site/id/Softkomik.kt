package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"

    private val cdnUrls = listOf(
        "https://psy1.komik.im",
        "https://image.komik.im/softkomik",
        "https://cdn1.softkomik.online/softkomik",
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    // ── Session cache ─────────────────────────────────────────────────────────

    private val sessionCache = ConcurrentHashMap<String, SessionDto>()

    private suspend fun getSession(endpoint: String): SessionDto {
        sessionCache[endpoint]?.takeIf { it.ex > System.currentTimeMillis() }?.let { return it }

        // Warm up cookies dulu
        val siteHeaders = Headers.Builder()
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
        runCatching { webClient.httpGet("https://$domain", siteHeaders) }

        val apiHeaders = Headers.Builder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "https://$domain/")
            .build()
        val res = webClient.httpGet("https://$domain$endpoint", apiHeaders).parseJson()
        val session = SessionDto(
            ex = res.optLong("ex", System.currentTimeMillis() + 7_200_000L),
            token = res.optString("token", ""),
            sign = res.optString("sign", ""),
        )
        sessionCache[endpoint] = session
        return session
    }

    private suspend fun getChapterListSession(): SessionDto = getSession("/api/sessions/kajsijas")
    private suspend fun getChapterImageSession(): SessionDto = getSession("/api/sessions/chapter")

    // ── LIST PAGE ─────────────────────────────────────────────────────────────

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Search menggunakan API langsung
        if (!filter.query.isNullOrEmpty()) {
            return searchByQuery(filter.query, page)
        }

        val sortBy = if (order == SortOrder.POPULARITY) "popular" else "newKomik"
        val url = "https://$domain/komik/library?sortBy=$sortBy&page=$page"

        val headers = Headers.Builder()
            .add("rsc", "1")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()

        val responseText = webClient.httpGet(url, headers).body?.string() ?: return emptyList()

        // Next.js RSC response — cari JSON embedded di dalamnya
        val libData = extractNextJsData(responseText, "data", "maxPage") ?: return emptyList()

        val dataArray = libData.optJSONArray("data") ?: return emptyList()
        return dataArray.mapJSON { jo ->
            val slug = jo.optString("title_slug", "").ifEmpty { jo.optString("id", "") }
            if (slug.isEmpty()) return@mapJSON null
            val gambar = jo.optString("gambar", "").removePrefix("/")
            Manga(
                id = generateUid(slug),
                title = jo.optString("title", "Untitled"),
                altTitles = emptySet(),
                url = "/komik/$slug",
                publicUrl = "https://$domain/komik/$slug",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else "",
                tags = emptySet(),
                state = parseStatus(jo.optString("status")),
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    private suspend fun searchByQuery(query: String, page: Int): List<Manga> {
        val session = getChapterListSession()
        val url = "$apiUrl/komik?name=${query.urlEncoded()}&search=true&limit=20&page=$page"
        val headers = Headers.Builder()
            .add("X-Token", session.token)
            .add("X-Sign", session.sign)
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .build()
        val json = webClient.httpGet(url, headers).parseJson()
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        return dataArray.mapJSON { jo ->
            val slug = jo.optString("title_slug", "")
            if (slug.isEmpty()) return@mapJSON null
            val gambar = jo.optString("gambar", "").removePrefix("/")
            Manga(
                id = generateUid(slug),
                title = jo.optString("title", "Untitled"),
                altTitles = emptySet(),
                url = "/komik/$slug",
                publicUrl = "https://$domain/komik/$slug",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else "",
                tags = emptySet(),
                state = parseStatus(jo.optString("status")),
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    // ── DETAILS ───────────────────────────────────────────────────────────────

    override suspend fun getDetails(manga: Manga): Manga {
        val rscHdrs = Headers.Builder()
            .add("rsc", "1")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
        val responseText = webClient.httpGet(manga.publicUrl, rscHdrs).body?.string() ?: return manga

        val detail = extractNextJsData(responseText, "title", "sinopsis") ?: return manga

        val title = detail.optString("title", manga.title)
        val gambar = detail.optString("gambar", "").removePrefix("/")
        val coverImg = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else manga.coverUrl
        val description = detail.optString("sinopsis", "").takeIf { it.isNotBlank() }
        val author = detail.optString("author", "").takeIf { it.isNotBlank() }
        val state = parseStatus(detail.optString("status"))

        val tags = LinkedHashSet<MangaTag>()
        detail.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) tags.add(MangaTag(name, name.lowercase(), source))
            }
        }

        // Chapter list dari API
        val slug = manga.url.removePrefix("/komik/").removeSuffix("/")
        val chapters = fetchChapterList(slug, manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverImg,
            description = description,
            tags = tags,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapterList(slug: String, mangaUrl: String): List<MangaChapter> {
        val session = getChapterListSession()
        val url = "$apiUrl/komik/$slug/chapter?limit=9999999"
        val headers = Headers.Builder()
            .add("X-Token", session.token)
            .add("X-Sign", session.sign)
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .build()

        val json = webClient.httpGet(url, headers).parseJson()
        val chapterArray = json.optJSONArray("chapter") ?: return emptyList()

        val chapters = ArrayList<MangaChapter>(chapterArray.length())
        for (i in 0 until chapterArray.length()) {
            val ch = chapterArray.optJSONObject(i) ?: continue
            val chStr = ch.optString("chapter", "")
            if (chStr.isEmpty()) continue
            val number = chStr.substringBefore(".").toFloatOrNull() ?: continue
            chapters.add(
                MangaChapter(
                    id = generateUid("$mangaUrl/chapter/$chStr"),
                    title = "Chapter ${formatChapterDisplay(chStr)}",
                    url = "$mangaUrl/chapter/$chStr",
                    number = number,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                ),
            )
        }
        chapters.sortByDescending { it.number }
        return chapters
    }

    // ── PAGES ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val rscHdrs = Headers.Builder()
            .add("rsc", "1")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
        val responseText = webClient.httpGet(
            "https://$domain${chapter.url}", rscHdrs,
        ).body?.string() ?: return emptyList()

        val data = extractNextJsData(responseText, "_id", "imageSrc") ?: return emptyList()

        var imageSrc = data.optJSONArray("imageSrc") ?: JSONArray()

        // Kalau kosong, fetch dari API gambar
        if (imageSrc.length() == 0) {
            val id = data.optString("_id", "")
            val parts = chapter.url.removePrefix("/komik/").split("/chapter/")
            if (parts.size == 2 && id.isNotEmpty()) {
                val chSlug = parts[0]
                val chNum = parts[1]
                imageSrc = fetchChapterImages(chSlug, chNum, id)
            }
        }

        if (imageSrc.length() == 0) return emptyList()

        val isInter2 = data.optBoolean("storageInter2", false)
        val host = if (isInter2) cdnUrls[2] else cdnUrls[0]

        return (0 until imageSrc.length()).mapNotNull { i ->
            val path = imageSrc.optString(i, "").removePrefix("/")
            if (path.isEmpty()) return@mapNotNull null
            MangaPage(
                id = generateUid(path),
                url = "$host/$path",
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchChapterImages(slug: String, chapter: String, id: String): JSONArray {
        return try {
            val session = getChapterImageSession()
            val url = "$apiUrl/komik/$slug/chapter/$chapter/img/$id"
            val headers = Headers.Builder()
                .add("X-Token", session.token)
                .add("X-Sign", session.sign)
                .add("Accept", "application/json")
                .add("Referer", "https://$domain/")
                .build()
            val json = webClient.httpGet(url, headers).parseJson()
            json.optJSONArray("imageSrc") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun parseStatus(text: String?): MangaState? = when {
        text == null -> null
        text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        text.contains("tamat", ignoreCase = true) ||
            text.contains("completed", ignoreCase = true) -> MangaState.FINISHED
        else -> null
    }

    private fun formatChapterDisplay(chStr: String): String {
        val parts = chStr.split(".")
        val numPart = parts[0].toFloatOrNull()
            ?: return chStr
        val formatted = if (numPart == numPart.toLong().toFloat()) {
            numPart.toLong().toString()
        } else {
            numPart.toString().trimEnd('0').trimEnd('.')
        }
        val suffix = parts.drop(1).joinToString(".")
        return if (suffix.isNotEmpty()) "$formatted.$suffix" else formatted
    }

    private fun extractNextJsData(text: String, vararg requiredKeys: String): JSONObject? {
        val nextDataRegex = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        nextDataRegex.find(text)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                if (pageProps != null && requiredKeys.all { pageProps.has(it) }) return pageProps
                // Coba di pageProps.data atau pageProps.manga
                pageProps?.optJSONObject("manga")?.let { m ->
                    if (requiredKeys.all { m.has(it) }) return m
                }
                pageProps?.optJSONObject("res")?.let { r ->
                    if (requiredKeys.all { r.has(it) }) return r
                }
            }
        }

        // Fallback: cari JSON object di RSC payload yang berisi semua required key
        // RSC format: setiap baris bisa berupa "0:{...}" atau "1:{...}" dsb
        val jsonObjectRegex = Regex("""\{[^{}]*"${requiredKeys.first()}"[^{}]*\}""")
        for (match in jsonObjectRegex.findAll(text)) {
            runCatching {
                val obj = JSONObject(match.value)
                if (requiredKeys.all { obj.has(it) }) return obj
            }
        }

        // Coba parse seluruh blob JSON besar yang sering ada di RSC response
        val bigJsonRegex = Regex("""(\{".{1,30}":"[^"]{0,200}",".{1,30}":.+\})""")
        for (match in bigJsonRegex.findAll(text)) {
            runCatching {
                val obj = JSONObject(match.value)
                if (requiredKeys.all { obj.has(it) }) return obj
            }
        }

        return null
    }
}

private data class SessionDto(
    val ex: Long,
    val token: String,
    val sign: String,
)
