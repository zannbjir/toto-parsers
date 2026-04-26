package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat

@MangaSourceParser("MANHWALAND", "ManhwaLand.vip", "id", ContentType.HENTAI)
internal class ManhwaLand(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWALAND, "www.manhwaland.baby", pageSize = 20, searchPageSize = 10) {

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isTagsExclusionSupported = false)

    override val datePattern = "MMM d, yyyy"

    // The /manga/ archive on this domain is hard-blocked by Cloudflare while the homepage and
    // /page/N/ pagination are not. Override the list page builder so list/search work without
    // hitting the blocked archive.
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            if (page > 1) {
                append("/page/")
                append(page)
                append("/")
            } else {
                append('/')
            }
            if (!filter.query.isNullOrEmpty()) {
                append("?s=")
                append(filter.query.urlEncoded())
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    // Default MangaReaderParser fetches `/manga` to build the tag map during getDetails().
    // That URL is hard-blocked by Cloudflare on this domain. Skip it.
    override suspend fun getOrCreateTagMap(): Map<String, MangaTag> = emptyMap()

    // The `/manga/<slug>/` detail page is gated behind Cloudflare on this domain. When the
    // in-app webview interceptor just cleared CF the page loads normally; otherwise we get
    // a 403 / archive listing back. Try the HTML path first so we get full metadata
    // (description, status, genres, real chapter titles + dates) when possible, and fall
    // back to the AJAX search endpoint (which is never CF-gated) to at least recover a
    // chapter list and basic info when the HTML detail page fails.
    override suspend fun getDetails(manga: Manga): Manga {
        val htmlDetails = runCatching {
            val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
            val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
            val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
                val href = element.selectFirst("a")?.attrAsRelativeUrlOrNull("href")
                    ?: return@mapChapters null
                MangaChapter(
                    id = generateUid(href),
                    title = element.selectFirst(".chapternum")?.textOrNull(),
                    url = href,
                    number = index + 1f,
                    volume = 0,
                    scanlator = null,
                    uploadDate = dateFormat.parseSafe(element.selectFirst(".chapterdate")?.text()),
                    branch = null,
                    source = source,
                )
            }
            check(chapters.isNotEmpty()) { "no chapters in html" }
            parseInfo(docs, manga, chapters)
        }.getOrNull()
        if (htmlDetails != null) return htmlDetails

        // Fallback path: AJAX live-search bypasses CF and exposes total chapter count,
        // status, genres and post type. Build a synthetic chapter list from the slug.
        val slug = manga.url.trim('/').removePrefix("manga/").trim('/')
        val rawTitle = manga.title.ifBlank { slug.replace('-', ' ') }
        // ts_ac_do_search ignores everything past ~40 chars; trim the query so long titles
        // don't get cut at a word boundary that breaks matching.
        val query = rawTitle.take(40).urlEncoded()
        val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php"
        val payload = "action=ts_ac_do_search&ts_ac_query=$query"
        val response = runCatching {
            webClient.httpPost(ajaxUrl, payload).parseJson()
        }.getOrNull() ?: return manga

        // Walk every entry in the response, prefer the one whose post_link slug matches
        // exactly. If none matches, fall back to the first entry so the user still gets a
        // chapter list even when slug heuristics miss.
        var matched: org.json.JSONObject? = null
        var firstAny: org.json.JSONObject? = null
        val series = response.optJSONArray("series")
        outer@ for (i in 0 until (series?.length() ?: 0)) {
            val all = series!!.optJSONObject(i)?.optJSONArray("all") ?: continue
            for (j in 0 until all.length()) {
                val entry = all.getJSONObject(j)
                if (firstAny == null) firstAny = entry
                val link = entry.optString("post_link")
                if (link.contains("/manga/$slug/") || link.endsWith("/$slug/")) {
                    matched = entry
                    break@outer
                }
            }
        }
        val entry = matched ?: firstAny

        val totalChapters = entry?.optString("post_latest")?.toIntOrNull() ?: 0
        val entryTitle = entry?.optString("post_title")?.ifBlank { manga.title } ?: manga.title
        val entryGenres = entry?.optString("post_genres").orEmpty()
        val entryStatus = entry?.optString("post_status").orEmpty()
        val entryType = entry?.optString("post_type").orEmpty()
        val entryImage = entry?.optString("post_image")?.takeIf { it.isNotBlank() }

        val mangaState = when (entryStatus.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed", "finished", "end" -> MangaState.FINISHED
            "hiatus", "paused" -> MangaState.PAUSED
            "dropped", "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }

        // Build a synthetic description from the available metadata. The detail page is the
        // only place that has the real synopsis and we cannot reach it; expose what we know
        // so the user at least sees genres / type / status instead of a blank screen.
        val description = buildString {
            if (entryType.isNotBlank()) append(entryType)
            if (entryStatus.isNotBlank()) {
                if (isNotEmpty()) append(" \u2022 ")
                append(entryStatus)
            }
            if (entryGenres.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(entryGenres)
            }
        }.ifBlank { null }

        val chapters = (1..totalChapters).map { num ->
            val urlPath = "/$slug-chapter-$num/"
            MangaChapter(
                id = generateUid(urlPath),
                title = "Chapter $num",
                url = urlPath,
                number = num.toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            title = entryTitle,
            description = description,
            state = mangaState,
            coverUrl = entryImage ?: manga.coverUrl,
            chapters = chapters,
        )
    }

    // Paksa semua gambar pakai HTTPS
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val pages = super.getPages(chapter)
        return pages.map { page ->
            page.copy(url = page.url.replace("http://", "https://"))
        }
    }

    // Image hotlink protection: explicitly forge headers for image requests so they load.
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("manhwaland")) {
            val builder = request.newBuilder()
            builder.header("Referer", "https://$domain/")
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            )
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            builder.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            builder.header("Origin", "https://$domain")
            builder.header("Sec-Fetch-Site", "cross-site")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Dest", "image")
            builder.header("Sec-Ch-Ua", "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"")
            builder.header("Sec-Ch-Ua-Mobile", "?0")
            builder.header("Sec-Ch-Ua-Platform", "\"Windows\"")
            builder.header("Cache-Control", "no-cache")
            return chain.proceed(builder.build())
        }
        return chain.proceed(request)
    }
}
