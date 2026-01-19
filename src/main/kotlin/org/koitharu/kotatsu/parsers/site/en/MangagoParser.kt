package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("MANGAGO", "Mangago", "en")
internal class MangagoParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("www.mangago.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("Yaoi", "Yaoi", source),
        MangaTag("Doujinshi", "Doujinshi", source),
        MangaTag("Shounen Ai", "Shounen Ai", source),
        MangaTag("Shoujo", "Shoujo", source),
        MangaTag("Yuri", "Yuri", source),
        MangaTag("Romance", "Romance", source),
        MangaTag("Fantasy", "Fantasy", source),
        MangaTag("Comedy", "Comedy", source),
        MangaTag("Smut", "Smut", source),
        MangaTag("Adult", "Adult", source),
        MangaTag("School Life", "School Life", source),
        MangaTag("Mystery", "Mystery", source),
        MangaTag("One Shot", "One Shot", source),
        MangaTag("Ecchi", "Ecchi", source),
        MangaTag("Shounen", "Shounen", source),
        MangaTag("Martial Arts", "Martial Arts", source),
        MangaTag("Shoujo Ai", "Shoujo Ai", source),
        MangaTag("Supernatural", "Supernatural", source),
        MangaTag("Drama", "Drama", source),
        MangaTag("Action", "Action", source),
        MangaTag("Adventure", "Adventure", source),
        MangaTag("Harem", "Harem", source),
        MangaTag("Historical", "Historical", source),
        MangaTag("Horror", "Horror", source),
        MangaTag("Josei", "Josei", source),
        MangaTag("Mature", "Mature", source),
        MangaTag("Mecha", "Mecha", source),
        MangaTag("Psychological", "Psychological", source),
        MangaTag("Sci-fi", "Sci-fi", source),
        MangaTag("Seinen", "Seinen", source),
        MangaTag("Slice Of Life", "Slice Of Life", source),
        MangaTag("Sports", "Sports", source),
        MangaTag("Gender Bender", "Gender Bender", source),
        MangaTag("Tragedy", "Tragedy", source),
        MangaTag("Bara", "Bara", source),
        MangaTag("Shotacon", "Shotacon", source),
        MangaTag("Webtoons", "Webtoons", source),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            val url = buildString {
                append("https://")
                append(domain)
                append("/r/l_search?name=")
                append(filter.query.urlEncoded())
                append("&page=")
                append(page)
            }
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        val url = when (order) {
            SortOrder.NEWEST -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/list/new/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/")
                }
            }
            else -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/genre/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/?")
                    val states = filter.states
                    val showFinished = states.isEmpty() || states.contains(MangaState.FINISHED)
                    val showOngoing = states.isEmpty() || states.contains(MangaState.ONGOING)
                    append("f=").append(if (showFinished) "1" else "0")
                    append("&o=").append(if (showOngoing) "1" else "0")
                    append("&sortby=")
                    when (order) {
                        SortOrder.POPULARITY -> append("view")
                        SortOrder.UPDATED -> append("update_date")
                        else -> append("update_date")
                    }
                    append("&e=")
                    if (filter.tagsExclude.isNotEmpty()) {
                        filter.tagsExclude.joinTo(this, ",") { it.key }
                    }
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".box, .updatesli, .pic_list > li").mapNotNull { element ->
            val linkElement = element.selectFirst(".thm-effect") ?: return@mapNotNull null
            val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifEmpty {
                linkElement.selectFirst("h2 a")?.text() ?: return@mapNotNull null
            }
            val thumbnailElem = linkElement.selectFirst("img") ?: return@mapNotNull null
            val thumbnailUrl = thumbnailElem.attr("abs:data-src").ifEmpty {
                thumbnailElem.attr("abs:src")
            }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = thumbnailUrl,
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val infoBlock = doc.requireElementById("information")

        val title = doc.selectFirstOrThrow(".w-title h1").text()
        val thumbnail = infoBlock.selectFirstOrThrow("img").absUrl("src")

        var description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
            summary.selectFirst("font")?.remove()
            summary.text()
        }.orEmpty()

        var author: String? = null
        var genres = emptySet<MangaTag>()
        var state: MangaState? = null

        infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
            when (el.selectFirst("b, label")?.text()?.lowercase()) {
                "alternative:" -> description += "\n\n${el.text()}"
                "status:" -> state = when (el.selectFirstOrThrow("span").text().lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                }
                "author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
                "genre(s):" -> genres = el.select("a").mapToSet {
                    MangaTag(
                        key = it.text(),
                        title = it.text(),
                        source = source,
                    )
                }
            }
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        return doc.select("table#chapter_table > tbody > tr, table.uk-table > tbody > tr")
            .mapIndexedNotNull { index, element ->
                val link = element.selectFirst("a.chico") ?: return@mapIndexedNotNull null
                val url = link.attrAsAbsoluteUrlOrNull("href") ?: return@mapIndexedNotNull null
                val name = link.text().trim()

                val dateText = element.select("td:last-child").text().trim()
                val dateUpload = runCatching {
                    dateFormat.parse(dateText)?.time
                }.getOrNull() ?: 0L

                val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
                    ?.ifEmpty { null }

                MangaChapter(
                    id = generateUid(url),
                    url = url,
                    title = name,
                    number = index + 1f,
                    volume = 0,
                    uploadDate = dateUpload,
                    scanlator = scanlator,
                    branch = null,
                    source = source,
                )
            }
            .reversed()
            .filterDuplicateChapters()
    }

    private fun List<MangaChapter>.filterDuplicateChapters(): List<MangaChapter> {
        val chapterMap = mutableMapOf<Float, MangaChapter>()

        for (chapter in this) {
            val chapterNumber = if (chapter.title != null) {
                extractChapterNumber(chapter.title) ?: chapter.number
            } else {
                chapter.number
            }

            if (!chapterMap.containsKey(chapterNumber)) {
                chapterMap[chapterNumber] = chapter
            } else {
                val existing = chapterMap[chapterNumber]!!
                val newTitle = chapter.title.orEmpty()
                val existingTitle = existing.title.orEmpty()
                val newHasMoreInfo = newTitle.length > existingTitle.length

                if (newHasMoreInfo) {
                    chapterMap[chapterNumber] = chapter
                }
            }
        }

        return chapterMap.values.toList()
    }

    private fun extractChapterNumber(title: String): Float? {
        return try {
            val regex = Regex("""(?:ch\.?|chapter|vol\.?\s*\d+\s+ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            val match = regex.find(title)
            match?.groupValues?.get(1)?.toFloat()
        } catch (e: Exception) {
            null
        }
    }

    // Cache for deobfuscated JS to avoid re-fetching in getPageUrl
    // Map of chapterJsUrl -> Pair(deobfuscatedJS, timestamp)
    private val jsCache = mutableMapOf<String, Pair<String, Long>>()
    private val maxCacheTime = 1000 * 60 * 5 // 5 minutes

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // Attempt to find encrypted images immediately. This works for Desktop and Mobile (if data is present)
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()

        if (imgsrcsScript != null) {
            println("[MANGAGO] Found imgsrcs in main page, decrypting all images...")
            // We found the data, decrypt it once and return all image URLs
            val images = decryptImageList(doc, fullUrl)
            val cols = getColsFromDoc(doc) ?: ""
            val js = getDeobfuscatedJS(doc) ?: throw Exception("Could not get JS")

            return images.mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink")) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }

                if (url.isBlank()) {
                    throw Exception("Final image URL at index $index is blank after processing")
                }

                MangaPage(
                    id = generateUid("$fullUrl-$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        // Fallback: Strict Mobile Mode (No script in main page)
        // We must generate page URLs and fetch each one later in getPageUrl
        println("[MANGAGO] No imgsrcs in main page, assuming strict mobile mode.")
        val pageDropdown = doc.select("div.controls ul#dropdown-menu-page")
        if (pageDropdown.isNotEmpty()) {
            val pagesCount = pageDropdown.select("li").size
            // Use doc.location() as base, similar to Mihon reference
            val location = doc.location()
            if (location.isEmpty()) {
                throw Exception("Could not determine document location for mobile mode")
            }
            val pageUrl = location.removeSuffix("/").substringBeforeLast("-")
            return (1..pagesCount).map { pageNum ->
                MangaPage(
                    id = generateUid("$pageUrl-$pageNum"),
                    url = "$pageUrl-$pageNum/",
                    preview = null,
                    source = source,
                )
            }
        }

        throw Exception("Could not find pages")
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        // If the URL doesn't end with / or contains fragment, it's likely already an image URL (or resolved)
        if (!page.url.endsWith("/") || page.url.contains("#desckey=")) {
            if (page.url.isBlank()) {
                throw Exception("Page URL is blank in getPageUrl")
            }
            return page.url
        }

        // It is an HTML page URL (Mobile mode). Fetch and resolve.
        println("[MANGAGO] Resolving mobile page: ${page.url}")
        val doc = webClient.httpGet(page.url).parseHtml()

        // Extract page number from URL
        val cleanUrl = page.url.removeSuffix("/")
        val pageNumber = cleanUrl.substringAfterLast("-").toIntOrNull()
            ?: throw Exception("Could not parse page number from URL")

        // Decrypt images from this specific page
        val images = decryptImageList(doc, page.url)

        if (pageNumber < 1 || pageNumber > images.size) {
            throw Exception("Page number $pageNumber out of bounds (1-${images.size})")
        }

        val imageUrl = images[pageNumber - 1]

        if (imageUrl.isBlank()) {
            throw Exception("Decrypted image URL at index ${pageNumber - 1} is blank")
        }

        // Add fragment if needed
        if (imageUrl.contains("cspiclink")) {
            val js = getDeobfuscatedJS(doc) ?: throw Exception("Could not get JS")
            val cols = getColsFromDoc(doc) ?: ""
            val descramblingKey = getDescramblingKey(js, imageUrl)
            return "$imageUrl#desckey=$descramblingKey&cols=$cols"
        }

        return imageUrl
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // --- Decryption & Helper Functions ---

    private suspend fun decryptImageList(doc: Document, sourceUrl: String): List<String> {
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs")

        val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")

        val imgsrcs = context.decodeBase64(imgsrcRaw)

        val deobfChapterJs = getDeobfuscatedJS(doc) ?: throw Exception("Could not deobfuscate JS")

        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(imgsrcs)

        var imageList = String(decryptedBytes, Charsets.UTF_8).trimEnd('\u0000')
        println("[MANGAGO] Decrypted raw string (first 200 chars): ${imageList.take(200)}")
        println("[MANGAGO] Decrypted string length: ${imageList.length}")
        println("[MANGAGO] Number of commas in decrypted string: ${imageList.count { it == ',' }}")

        imageList = unscrambleImageList(imageList, deobfChapterJs)

        val images = imageList.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        println("[MANGAGO] Decrypted ${images.size} images")
        return images
    }



    private suspend fun getDeobfuscatedJS(doc: Document): String? {
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src") ?: return null

        val now = System.currentTimeMillis()
        val cached = jsCache[chapterJsUrl]
        if (cached != null && now - cached.second < maxCacheTime) {
            return cached.first
        }

        println("[MANGAGO] Fetching and deobfuscating chapter.js: $chapterJsUrl")
        val obfuscatedChapterJs = webClient.httpGet(chapterJsUrl).parseRaw()
        val deobf = deobfuscateSoJsonV4(obfuscatedChapterJs)

        jsCache[chapterJsUrl] = Pair(deobf, now)
        return deobf
    }

    private fun getColsFromDoc(doc: Document): String? {
        // Get cols from cached JS for the current chapter's script URL
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src")
        val cached = if (chapterJsUrl != null) jsCache[chapterJsUrl] else null
        val js = cached?.first ?: return null
        return COLS_REGEX.find(js)?.groupValues?.get(1)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment

        if (fragment == null || !fragment.contains("desckey=")) {
            return response
        }

        return context.redrawImageResponse(response) { bitmap ->
            val key = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=").toIntOrNull() ?: return@redrawImageResponse bitmap
            unscrambleImage(bitmap, key, cols)
        }
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String, cols: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val result = context.createBitmap(width, height)

        val unitWidth = width / cols
        val unitHeight = height / cols

        val keyArray = key.split("a")

        for (idx in 0 until cols * cols) {
            val keyval = keyArray.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val heightY = keyval / cols
            val dy = heightY * unitHeight
            val dx = (keyval - heightY * cols) * unitWidth

            val widthY = idx / cols
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth

            val w = min(unitWidth, width - dx)
            val h = min(unitHeight, height - dy)

            val srcRect = Rect(sx, sy, sx + w, sy + h)
            val dstRect = Rect(dx, dy, dx + w, dy + h)

            result.drawBitmap(bitmap, srcRect, dstRect)
        }

        return result
    }

    private fun deobfuscateSoJsonV4(jsf: String): String {
        if (!jsf.startsWith("['sojson.v4']")) {
            // If it's not sojson.v4, maybe it's already deobfuscated or a different obfuscation?
            // Based on Mihon code, it expects sojson.v4
            // If the site changed, this might crash, but we try to parse as is or throw
            // For now, let's assume if it starts differently, it might not need deobfuscation or format changed.
            // But given the error, we should stick to the logic.
            // If the format changed, this regex logic might need update.
            // However, let's return the string if it looks like JS code?
            // For safety, throw if header mismatch to alert user.
            throw Exception("Obfuscated code header mismatch. Expected sojson.v4")
        }

        val splitRegex = Regex("[a-zA-Z]+")
        val args = jsf.substring(240, jsf.length - 59).split(splitRegex)

        return args.map { it.toInt().toChar() }.joinToString("")
    }

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1)
            ?: throw Exception("Could not find variable: $variable")
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun unscrambleImageList(imageList: String, js: String): String {
        var imgList = imageList

        // Check if unscrambling is needed - only if key locations exist and characters at those positions are digits
        val keyLocations = KEY_LOCATION_REGEX.findAll(js)
            .map { it.groupValues[1].toInt() }
            .distinct()
            .sorted()
            .toList()

        if (keyLocations.isEmpty()) {
            println("[MANGAGO] Unscramble: no key locations found, returning as-is")
            return imgList
        }

        println("[MANGAGO] Unscramble: found ${keyLocations.size} unique key locations")
        println("[MANGAGO] Unscramble: original image list length = ${imgList.length}")
        println("[MANGAGO] Unscramble: keyLocations = $keyLocations")
        println("[MANGAGO] Unscramble: first 100 chars of original: ${imgList.take(100)}")

        val unscrambleKey = keyLocations.mapNotNull { loc ->
            if (loc < imgList.length) {
                val char = imgList[loc]
                println("[MANGAGO] Unscramble: position $loc = '$char' (code: ${char.code})")
                char.code
            } else {
                println("[MANGAGO] Unscramble: position $loc is beyond string length ${imgList.length}")
                null
            }
        }.toList()

        println("[MANGAGO] Unscramble: extracted key = $unscrambleKey")

        // Remove characters - need to account for index shifts as we remove characters
        keyLocations.filter { it < imgList.length }.forEachIndexed { idx, loc ->
            imgList = imgList.removeRange((loc - idx)..(loc - idx))
        }

        println("[MANGAGO] Unscramble: after removing chars, length = ${imgList.length}")
        println("[MANGAGO] Unscramble: first 200 chars = ${imgList.take(200)}")
        println("[MANGAGO] Unscramble: string before split: ${imgList.take(500)}")

        imgList = imgList.unscramble(unscrambleKey)

        println("[MANGAGO] Unscramble: final length = ${imgList.length}")
        println("[MANGAGO] Unscramble: string after unscramble before split: ${imgList.take(500)}")
        return imgList
    }

    private fun String.unscramble(keys: List<Int>): String {
        var s = this
        keys.reversed().forEach { key ->
            for (i in s.length - 1 downTo key) {
                if (i % 2 != 0) {
                    val temp = s[i - key]
                    s = s.replaceRange(i - key..i - key, s[i].toString())
                    s = s.replaceRange(i..i, temp.toString())
                }
            }
        }
        return s
    }

    private suspend fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
        val imgkeys = deobfChapterJs
            .substringAfter("var renImg = function(img,width,height,id){", "")
            .substringBefore("key = key.split(", "")
            .split("\n")
            .filter { line -> JS_FILTERS.none { filter -> line.contains(filter) } }
            .joinToString("\n")
            .replace("img.src", "url")

        if (imgkeys.isEmpty()) {
            throw Exception("Failed to extract image key extraction code from chapter.js")
        }

        val js = """
            function replacePos(strObj, pos, replacetext) {
                var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                return str;
            }
            function getDescramblingKey(url) { $imgkeys; return key; }
            getDescramblingKey("$imageUrl");
        """.trimIndent()

        val result = context.evaluateJs("https://$domain/", js, 10000L)

        if (result.isNullOrEmpty()) {
            throw Exception("Failed to evaluate JavaScript to get descrambling key. The image format or site structure may have changed.")
        }

        return result
    }

    private companion object {
        private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
        private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        private val JS_FILTERS = listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")
    }
}
