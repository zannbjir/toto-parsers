package org.koitharu.kotatsu.parsers.site.mangareader.id

import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.WordSet
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.ownTextOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKCAST, "be.komikcast.fit", pageSize = 60, searchPageSize = 28) {

	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genreMap = fetchGenreMap()
		return MangaListFilterOptions(
			availableTags = genreMap.values.toSet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/series?page=")
			append(page + 1)
			append("&take=")
			append(if (filter.query.isNullOrEmpty()) pageSize else searchPageSize)
			append("&takeChapter=2")
			append("&includeMeta=true")

			if (filter.query.isNullOrEmpty()) {
				filter.types.oneOrThrowIfMany()?.let { contentType ->
					append("&type=")
					append(when (contentType) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					})
				}

				filter.tags.takeIf { it.isNotEmpty() }?.let { tags ->
					append("&genreIds=")
					append(tags.joinToString(",") { it.key })
				}

				if (filter.states.isNotEmpty()) {
					filter.states.oneOrThrowIfMany()?.let { state ->
						append("&status=")
						append(when (state) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "hiatus"
							else -> ""
						})
					}
				}
			} else {
				append("&filter=title=like=\"")
				append(filter.query.urlEncoded())
				append("\",nativeTitle=like=\"")
				append(filter.query.urlEncoded())
				append("\"")

				filter.types.oneOrThrowIfMany()?.let { contentType ->
					append("&type=")
					append(when (contentType) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					})
				}

				filter.tags.takeIf { it.isNotEmpty() }?.let { tags ->
					append("&genreIds=")
					append(tags.joinToString(",") { it.key })
				}
			}
		}

		return parseSeriesList(webClient.httpGet(url).body?.string() ?: "")
	}
			else -> {
				request.newBuilder()
					.header("Referer", "https://$domain/")
					.build()
			}
		}
		return chain.proceed(newRequest)
	}

	override val listUrl = "/daftar-komik"
	override val datePattern = "MMM d, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false
		)

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {

				!filter.query.isNullOrEmpty() -> {
					append("/page/")
					append(page.toString())
					append("/?s=")
					append(filter.query.urlEncoded())
				}

				else -> {
					append(listUrl)
					append("/page/")
					append(page.toString())
					append("/?")

					filter.types.oneOrThrowIfMany()?.let { contentType ->
						append("type=")
						append(when (contentType) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							else -> ""
						})
						append("&")
					}

					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "orderby=titleasc"
							SortOrder.ALPHABETICAL_DESC -> "orderby=titledesc"
							SortOrder.POPULARITY -> "orderby=popular"
							else -> "sortby=update"
						},
					)

					val tagKey = "genre[]".urlEncoded()
					val tagQuery =
						if (filter.tags.isEmpty()) ""
						else filter.tags.joinToString(separator = "&", prefix = "&") { "$tagKey=${it.key}" }
					append(tagQuery)

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append("&status=")
							append(
								when (it) {
									MangaState.ONGOING -> "Ongoing"
									MangaState.FINISHED -> "Completed"
									else -> ""
								}
							)
						}
					}
				}
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/series/")
		val url = buildString {
			append("https://")
			append(domain)
			append("/series/")
			append(slug)
			append("?includeMeta=true")
		}

		val json = webClient.httpGet(url).body?.string() ?: throw Exception("Failed to fetch manga details")
		val seriesData = parseSeriesJson(json)

		return manga.copy(
			title = seriesData.title,
			description = seriesData.synopsis,
			state = when (seriesData.status.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			authors = setOfNotNull(seriesData.author),
			tags = seriesData.genres.mapNotNull { fetchGenreMap()[it.name] },
			coverUrl = seriesData.coverImage,
			rating = if (seriesData.rating > 0) seriesData.rating / 10f else RATING_UNKNOWN,
			chapters = seriesData.chapters?.mapIndexedNotNull { index, chapterData ->
				val chapterUrl = "/series/$slug/chapters/${chapterData.index}"
				MangaChapter(
					id = generateUid(chapterUrl),
					title = chapterData.title,
					url = chapterUrl,
					number = chapterData.index.toFloat(),
					volume = 0,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}?.reversed() ?: emptyList(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.substringAfter("/series/").substringBefore("/chapters")
		val chapterIndex = chapter.url.substringAfterLast("/")

		val url = buildString {
			append("https://")
			append(domain)
			append("/series/")
			append(slug)
			append("/chapters/")
			append(chapterIndex)
		}

		val json = webClient.httpGet(url).body?.string() ?: throw Exception("Failed to fetch chapter pages")
		val chapterData = parseChapterDetailJson(json)

		val images = chapterData.dataImages.values.sortedBy { it.key.toIntOrNull() ?: 0 }
		return images.mapNotNull { imgUrl ->
			if (imgUrl.isNullOrEmpty()) return@mapNotNull null
			MangaPage(
				id = generateUid(imgUrl),
				url = if (imgUrl.startsWith("http")) imgUrl else imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	private val json by lazy { org.json.JSONObject() }

	private suspend fun fetchGenreMap(): Map<String, MangaTag> {
		val url = "https://$domain/genres"
		val json = webClient.httpGet(url).body?.string() ?: return emptyMap()

		val genreMap = mutableMapOf<String, MangaTag>()
		try {
			val dataArray = json(json).getJSONArray("data")
			for (i in 0 until dataArray.length()) {
				val genreObj = dataArray.getJSONObject(i)
				val name = genreObj.getJSONObject("data").getString("name")
				val id = genreObj.getInt("id")
				genreMap[name] = MangaTag(
					title = name,
					key = id.toString(),
					source = source,
				)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return genreMap
	}

	private fun parseSeriesList(json: String): List<Manga> {
		val result = mutableListOf<Manga>()
		try {
			val responseObj = json(json)
			val dataArray = responseObj.getJSONObject("data").getJSONArray("data")

			for (i in 0 until dataArray.length()) {
				val seriesObj = dataArray.getJSONObject(i)
				val data = seriesObj.getJSONObject("data")

				val slug = data.getString("slug")
				val relativeUrl = "/series/$slug"
				val rating = if (data.has("rating")) {
					data.getDouble("rating").toFloat() / 10f
				} else {
					RATING_UNKNOWN
				}

				val manga = Manga(
					id = generateUid(relativeUrl),
					url = relativeUrl,
					title = data.getString("title"),
					altTitles = emptySet(),
					publicUrl = "https://$domain$relativeUrl",
					rating = rating,
					contentRating = ContentRating.SAFE,
					coverUrl = data.getString("coverImage"),
					tags = emptySet(),
					state = when (data.optString("status").lowercase()) {
						"ongoing" -> MangaState.ONGOING
						"completed" -> MangaState.FINISHED
						else -> null
					},
					authors = setOfNotNull(data.optString("author").takeIf { it.isNotEmpty() }),
					source = source,
				)

				if (data.has("chapters")) {
					val chaptersArray = data.getJSONArray("chapters")
					val chapters = mutableListOf<MangaChapter>()
					for (j in 0 until chaptersArray.length()) {
						val chapterObj = chaptersArray.getJSONObject(j)
						val chapterData = chapterObj.getJSONObject("data")
						val chapterIndex = chapterData.getInt("index")

						val chapter = MangaChapter(
							id = generateUid("$relativeUrl/chapters/$chapterIndex"),
							title = chapterData.optString("title"),
							url = "$relativeUrl/chapters/$chapterIndex",
							number = (chaptersArray.length() - j).toFloat(),
							volume = 0,
							scanlator = null,
							uploadDate = parseChapterDate(chapterData.optString("createdAt")),
							branch = null,
							source = source,
						)
						chapters.add(chapter)
					}
					result.add(manga.copy(chapters = chapters.reversed()))
				} else {
					result.add(manga)
				}
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return result
	}

	private fun parseSeriesJson(json: String): SeriesData {
		val responseObj = json(json)
		val dataObj = responseObj.getJSONObject("data").getJSONObject("data")

		val genreIds = dataObj.getJSONArray("genreIds")
		val genres = mutableListOf<GenreData>()
		for (i in 0 until genreIds.length()) {
			genres.add(GenreData(
				id = genreIds.getInt(i),
				name = "",
			))
		}

		val chapters = if (dataObj.has("chapters") && !dataObj.isNull("chapters")) {
			val chaptersArray = dataObj.getJSONArray("chapters")
			val chapterList = mutableListOf<ChapterData>()
			for (i in 0 until chaptersArray.length()) {
				val chapterObj = chaptersArray.getJSONObject(i)
				val chapterData = chapterObj.getJSONObject("data")
				chapterList.add(ChapterData(
					index = chapterData.getInt("index"),
					title = chapterData.optString("title"),
					createdAt = chapterData.optString("createdAt"),
				))
			}
			chapterList
		} else {
			null
		}

		return SeriesData(
			title = dataObj.getString("title"),
			synopsis = dataObj.optString("synopsis"),
			status = dataObj.getString("status"),
			author = dataObj.optString("author").takeIf { it.isNotEmpty() },
			coverImage = dataObj.getString("coverImage"),
			rating = if (dataObj.has("rating")) dataObj.getDouble("rating").toFloat() else 0f,
			genres = genres,
			chapters = chapters,
		)
	}

	private fun parseChapterDetailJson(json: String): ChapterDetailData {
		val responseObj = json(json)
		val dataObj = responseObj.getJSONObject("data").getJSONObject("data")

		val imagesMap = mutableMapOf<String, String>()
		if (dataObj.has("dataImages") && !dataObj.isNull("dataImages")) {
			val imagesObj = dataObj.getJSONObject("dataImages")
			val keys = imagesObj.keys()
			for (key in keys) {
				imagesMap[key] = imagesObj.getString(key)
			}
		}

		return ChapterDetailData(
			dataImages = imagesMap,
		)
	}

	private fun parseChapterDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0
		return try {
			java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH).parse(dateStr).time
		} catch (e: Exception) {
			0L
		}
	}

	private data class SeriesData(
		val title: String,
		val synopsis: String,
		val status: String,
		val author: String?,
		val coverImage: String,
		val rating: Float,
		val genres: List<GenreData>,
		val chapters: List<ChapterData>?,
	)

	private data class GenreData(
		val id: Int,
		val name: String,
	)

	private data class ChapterData(
		val index: Int,
		val title: String,
		val createdAt: String,
	)

	private data class ChapterDetailData(
		val dataImages: Map<String, String>,
	)
}
