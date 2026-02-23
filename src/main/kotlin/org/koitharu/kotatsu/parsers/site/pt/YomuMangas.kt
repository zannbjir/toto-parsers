package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getLongOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("YOMUMANGAS", "Yomu Mangas", "pt")
internal class YomuMangas(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YOMUMANGAS, pageSize = 18) {

	@Volatile
	private var genreIndex: Map<String, MangaTag> = emptyMap()

	override val configKeyDomain = ConfigKey.Domain("yomumangas.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
		.add("Accept", ACCEPT_JSON)
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.PAUSED,
				MangaState.FINISHED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.COMICS,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val effectiveOrderBy = if (filter.isEmpty()) "updatedAt" else order.toApiOrderBy()
		val url = "$apiUrl/mangas".toHttpUrl().newBuilder().apply {
			addQueryParameter("query", filter.query.orEmpty())
			addQueryParameter("page", page.toString())
			addQueryParameter("orderBy", effectiveOrderBy)

			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genreIds", filter.tags.joinToString(",") { it.key })
				filter.tags.forEach { addQueryParameter("genreId", it.key) }
			}

			filter.states.firstOrNull()?.toApiSeriesStatus()?.let {
				addQueryParameter("seriesStatus", it)
			}

			filter.types.firstOrNull()?.toApiSeriesType()?.let {
				addQueryParameter("seriesType", it)
			}

			if (ContentRating.ADULT in filter.contentRating) {
				addQueryParameter("nsfwContent", "true")
				addQueryParameter("adultContent", "true")
			}
		}.build()

		val response = webClient.httpGet(url, getApiHeaders()).parseJson()
		val mangasArray = response.optJSONArray("mangas")
			?: response.optJSONArray("posts")
			?: response.optJSONArray("data")
			?: JSONArray()
		return mangasArray.mapJSONNotNull { series -> parseMangaFromSeries(series) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaApiId = extractMangaApiId(manga)
		val response = webClient.httpGet("$apiUrl/mangas/$mangaApiId", getApiHeaders()).parseJson()
		val series = response.optJSONObject("manga") ?: response
		val parsed = parseMangaFromSeries(series, fallbackUrl = manga.url) ?: manga
		val chapters = fetchChapters(series)

		return manga.copy(
			title = parsed.title.ifBlank { manga.title },
			url = parsed.url,
			publicUrl = parsed.publicUrl,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = parsed.largeCoverUrl ?: manga.largeCoverUrl,
			description = parsed.description ?: manga.description,
			altTitles = parsed.altTitles.ifEmpty { manga.altTitles },
			tags = if (parsed.tags.isNotEmpty()) parsed.tags else manga.tags,
			authors = if (parsed.authors.isNotEmpty()) parsed.authors else manga.authors,
			state = parsed.state ?: manga.state,
			contentRating = parsed.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		return doc.select("[class*=reader_Pages] img").mapNotNull { img ->
			val url = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url.toRelativeUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapters(series: JSONObject): List<MangaChapter> {
		val seriesId = series.optInt("id", 0)
		if (seriesId <= 0) {
			return emptyList()
		}
		val seriesSlug = series.getStringOrNull("slug")
		val seriesUrl = normalizeMangaUrl(
			series.getStringOrNull("url")
				?: series.getStringOrNull("path")
				?: seriesSlug?.let { "/mangas/$it/$seriesId" },
		)

		val chaptersResponse = webClient.httpGet("$apiUrl/mangas/$seriesId/chapters", getApiHeaders()).parseJson()
		val chaptersArray = chaptersResponse.optJSONArray("chapters")
			?: chaptersResponse.optJSONArray("data")
			?: JSONArray()

		return chaptersArray.mapJSONNotNull { ch ->
			val chapterValue = ch.opt("chapter")?.toString()?.replace(',', '.')
			val chapterNumber = chapterValue?.toFloatOrNull() ?: ch.optString("number").replace(',', '.').toFloatOrNull()
			val chapterUrl = resolveChapterUrl(ch, seriesSlug, seriesId, seriesUrl) ?: return@mapJSONNotNull null
			val title = ch.getStringOrNull("title")
				?: ch.getStringOrNull("name")
				?: chapterValue?.let { "Capítulo $it" }
				?: "Capítulo"
			MangaChapter(
				id = ch.getLongOrDefault("id", generateUid(chapterUrl)),
				title = title,
				number = chapterNumber ?: 0f,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = parseDate(
					ch.getStringOrNull("createdAt")
						?: ch.getStringOrNull("updatedAt")
						?: ch.getStringOrNull("date"),
				),
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	private fun resolveChapterUrl(
		chapter: JSONObject,
		seriesSlug: String?,
		seriesId: Int,
		seriesUrl: String?,
	): String? {
		val normalizedSlug = seriesSlug
			?.trim()
			?.takeIf { it.isNotBlank() }
			?: seriesUrl
				?.substringAfter("/mangas/", "")
				?.split('/')
				?.getOrNull(1)
				?.trim()
				?.takeIf { it.isNotBlank() }
			?: return null

		val chapterSegment = chapter.opt("chapter")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
			?: chapter.optString("number").trim().takeIf { it.isNotEmpty() }
			?: chapter.optString("slug").trim().takeIf { it.isNotEmpty() }
			?: chapter.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
			?: return null

		val normalizedChapter = chapterSegment
			.replace(',', '.')
			.removeSuffix(".0")

		return "/mangas/$seriesId/$normalizedSlug/$normalizedChapter"
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val fromArray = runCatching {
			webClient.httpGet("$apiUrl/genres", getApiHeaders()).parseJsonArray()
		}.getOrNull()
		if (fromArray != null) {
			val tags = parseTagsArray(fromArray)
			if (tags.isNotEmpty()) {
				genreIndex = tags.associateBy { it.key }
				return tags
			}
		}

		val fromObject = runCatching {
			webClient.httpGet("$apiUrl/genres", getApiHeaders()).parseJson()
		}.getOrNull()
		if (fromObject != null) {
			val tags = parseTagsArray(fromObject.optJSONArray("genres") ?: fromObject.optJSONArray("data") ?: JSONArray())
			if (tags.isNotEmpty()) {
				genreIndex = tags.associateBy { it.key }
				return tags
			}
		}
		return emptySet()
	}

	private fun parseTagsArray(array: JSONArray): Set<MangaTag> {
		return array.mapJSONNotNull { jo ->
			val key = jo.opt("id")?.toString()?.trim().orEmpty()
			val title = jo.getStringOrNull("name")
				?: jo.getStringOrNull("title")
			if (key.isEmpty() || title.isNullOrBlank()) {
				null
			} else {
				MangaTag(
					key = key,
					title = title,
					source = source,
				)
			}
		}.toSet()
	}

	private fun parseMangaFromSeries(series: JSONObject, fallbackUrl: String? = null): Manga? {
		val id = series.getLongOrDefault("id", 0L)
		val slug = series.getStringOrNull("slug")
		val url = when {
			id > 0L && !slug.isNullOrBlank() -> "/mangas/$id/$slug"
			else -> normalizeMangaUrl(
				series.getStringOrNull("url")
					?: series.getStringOrNull("path")
					?: fallbackUrl,
			) ?: return null
		}

		val tags = parseSeriesTags(series)

		val author = series.getStringOrNull("author")
		val artist = series.getStringOrNull("artist")
		val authors = LinkedHashSet<String>(8).apply {
			addAll(parsePeopleArray(series.optJSONArray("authors")))
			addAll(parsePeopleArray(series.optJSONArray("artists")))
			author?.let(::add)
			artist?.let(::add)
		}.filter { it.isNotBlank() }.toSet()

		val description = series.getStringOrNull("description")
			?: series.getStringOrNull("synopsis")
			?: series.getStringOrNull("postContent")

		val cover = parseAssetUrl(
			series.getStringOrNull("thumbnail")
			?: series.getStringOrNull("featuredImage")
			?: series.getStringOrNull("cover")
			?: series.getStringOrNull("poster")
			?: series.getStringOrNull("banner"),
		)

		val status = (series.getStringOrNull("seriesStatus")
			?: series.getStringOrNull("status"))
			?.uppercase(Locale.ROOT)

		val isAdult = series.getBooleanOrDefault("nsfw", false) ||
			series.getBooleanOrDefault("hentai", false)

		return Manga(
			id = if (id > 0L) id else generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			title = series.getStringOrNull("postTitle")
				?: series.getStringOrNull("title")
				?: series.getStringOrNull("name")
				?: slug
				?: return null,
			altTitles = setOfNotNull(series.getStringOrNull("alternativeTitles")),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			rating = RATING_UNKNOWN,
			tags = tags,
			authors = authors,
			state = when (status) {
				"ONGOING" -> MangaState.ONGOING
				"HIATUS" -> MangaState.PAUSED
				"COMPLETED", "COMPLETE", "FINISHED" -> MangaState.FINISHED
				"DROPPED", "CANCELLED" -> MangaState.ABANDONED
				"COMING_SOON" -> MangaState.UPCOMING
				else -> null
			},
			contentRating = if (isAdult) ContentRating.ADULT else null,
			source = source,
		)
	}

	private fun parseDate(raw: String?): Long {
		val value = raw?.trim().orEmpty()
		if (value.isEmpty()) {
			return 0L
		}
		value.toLongOrNull()?.let { epoch ->
			return if (epoch > 9_999_999_999L) epoch else epoch * 1000L
		}
		for (pattern in datePatterns) {
			val parsed = SimpleDateFormat(pattern, Locale.ENGLISH).parseSafe(value)
			if (parsed > 0L) {
				return parsed
			}
		}
		return 0L
	}

	private fun parseSeriesTags(series: JSONObject): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>(8)
		val genres = series.optJSONArray("genres")
		if (genres != null) {
			for (i in 0 until genres.length()) {
				when (val item = genres.opt(i)) {
					is JSONObject -> {
						val key = item.opt("id")?.toString()?.trim().orEmpty()
						val title = item.getStringOrNull("name") ?: item.getStringOrNull("title")
						if (key.isNotBlank() && !title.isNullOrBlank()) {
							result.add(MangaTag(key = key, title = title, source = source))
						}
					}

					is Number -> {
						val key = item.toString()
						genreIndex[key]?.let(result::add)
					}

					is String -> {
						val key = item.trim()
						if (key.isNotEmpty()) {
							genreIndex[key]?.let(result::add)
						}
					}
				}
			}
		}
		return result
	}

	private fun parsePeopleArray(array: JSONArray?): Set<String> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<String>(array.length())
		for (i in 0 until array.length()) {
			val name = array.optString(i).trim()
			if (name.isNotEmpty() && name != "null") {
				result.add(name)
			}
		}
		return result
	}

	private fun parseAssetUrl(raw: String?): String? {
		val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return when {
			value.startsWith("http://") || value.startsWith("https://") -> value
			value.startsWith("b2://") -> "$b2CdnUrl/${value.removePrefix("b2://")}"
			value.startsWith("s3://") -> "$cdnUrl/${value.removePrefix("s3://")}"
			value.startsWith("/") -> "$cdnUrl$value"
			else -> "$cdnUrl/$value"
		}
	}

	private fun extractMangaApiId(manga: Manga): Long {
		if (manga.id > 0L && manga.id < 1_000_000_000_000L) {
			return manga.id
		}
		val fromUrl = manga.url.substringAfter("/mangas/", "")
			.substringBefore('/')
			.toLongOrNull()
			?: manga.url.substringAfterLast('/').toLongOrNull()
		return fromUrl ?: error("Cannot extract manga ID from ${manga.url}")
	}

	private fun normalizeMangaUrl(value: String?): String? {
		val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		if (raw.startsWith("http://") || raw.startsWith("https://")) {
			return raw.toRelativeUrl(domain)
		}
		return if (raw.startsWith('/')) raw else "/$raw"
	}

	private fun SortOrder.toApiOrderBy(): String = when (this) {
		SortOrder.POPULARITY -> "totalViews"
		SortOrder.UPDATED -> "updatedAt"
		SortOrder.NEWEST -> "createdAt"
		SortOrder.ALPHABETICAL_DESC -> "postTitle"
		else -> "totalViews"
	}

	private fun MangaState.toApiSeriesStatus(): String? = when (this) {
		MangaState.ONGOING -> "ONGOING"
		MangaState.PAUSED -> "HIATUS"
		MangaState.FINISHED -> "COMPLETE"
		MangaState.ABANDONED -> "DROPPED"
		else -> null
	}

	private fun ContentType.toApiSeriesType(): String? = when (this) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		ContentType.COMICS -> "COMIC"
		else -> null
	}

	private companion object {
		private const val ACCEPT_JSON = "application/json"
		private const val apiUrl = "https://api.yomumangas.com"
		private const val cdnUrl = "https://s3.yomumangas.com"
		private const val b2CdnUrl = "https://b2.yomumangas.com"
		private val datePatterns = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ssXXX",
			"yyyy-MM-dd'T'HH:mm:ss",
			"yyyy-MM-dd HH:mm:ss",
			"yyyy-MM-dd",
		)
	}
}
