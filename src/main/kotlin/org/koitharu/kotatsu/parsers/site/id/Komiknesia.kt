package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.*
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@MangaSourceParser("KOMIKNESIA", "KomikNesia", "id")
internal class Komiknesia(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKNESIA, pageSize = 40) {

	override val configKeyDomain = ConfigKey.Domain("02.komiknesia.asia")

	private val apiBase = "https://api-be.komiknesia.my.id/api"

	private val apiClient: WebClient by lazy {
		val newClient = context.httpClient.newBuilder()
			.sslSocketFactory(KomiknesiaSSL.sslSocketFactory, KomiknesiaSSL.trustManager)
			.build()
		OkHttpWebClient(newClient, source)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.build()

	private fun apiHeaders(): Headers = Headers.Builder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

	private suspend fun fetchTags(): Set<MangaTag> {
		tagsCache["all"]?.let { return it }
		return runCatching {
			val arr = apiClient.httpGet("$apiBase/contents/genres", apiHeaders()).parseJson()
				.optJSONArray("data") ?: return@runCatching emptySet<MangaTag>()
			val tags = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val jo = arr.optJSONObject(i) ?: continue
				val id = jo.optInt("id", 0)
				val name = jo.optString("name").ifBlank { continue }
				if (id <= 0) continue
				tags.add(MangaTag(title = name, key = id.toString(), source = source))
			}
			tags
		}.getOrDefault(emptySet()).also { tagsCache["all"] = it }
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append(apiBase).append("/contents?page=").append(page)

			filter.query?.takeIf { it.isNotBlank() }?.let {
				append("&q=").append(it.urlEncoded())
			}

			filter.tags.forEach { tag ->
				append("&genre%5B%5D=").append(tag.key)
			}

			filter.states.oneOrThrowIfMany()?.let { state ->
				val v = when (state) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					MangaState.PAUSED -> "Hiatus"
					else -> null
				}
				if (v != null) append("&status=").append(v)
			}

			val orderValue = when (order) {
				SortOrder.NEWEST -> "Added"
				SortOrder.POPULARITY -> "Popular"
				SortOrder.ALPHABETICAL -> "Az"
				SortOrder.ALPHABETICAL_DESC -> "Za"
				SortOrder.UPDATED -> ""
				else -> ""
			}
			if (orderValue.isNotEmpty()) append("&orderBy=").append(orderValue)
		}

		val json = apiClient.httpGet(url, apiHeaders()).parseJson()
		val arr = json.optJSONArray("data") ?: return emptyList()
		val result = ArrayList<Manga>(arr.length())
		for (i in 0 until arr.length()) {
			val item = arr.optJSONObject(i) ?: continue
			parseListItem(item)?.let { result.add(it) }
		}
		return result
	}

	private fun parseListItem(item: JSONObject): Manga? {
		val slug = item.optString("slug").ifBlank { return null }
		val title = item.optString("title").ifBlank { return null }
		val url = "/komik/$slug"
		val cover = item.optString("thumbnail").ifBlank { item.optString("cover") }
		val rating = item.optDouble("rating", Double.NaN)
			.takeIf { !it.isNaN() }
			?.let { (it / 10f).toFloat().coerceIn(0f, 1f) }
			?: RATING_UNKNOWN
		val state = parseState(item.optString("status"))
		val tagsArr = item.optJSONArray("genres")
		val tags = if (tagsArr != null) {
			val s = LinkedHashSet<MangaTag>(tagsArr.length())
			for (i in 0 until tagsArr.length()) {
				val g = tagsArr.optJSONObject(i) ?: continue
				val id = g.optInt("id", 0)
				val name = g.optString("name").ifBlank { continue }
				if (id <= 0) continue
				s.add(MangaTag(title = name, key = id.toString(), source = source))
			}
			s
		} else {
			emptySet()
		}
		val isSafe = optBoolOrInt(item, "is_safe", true)
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = setOfNotNull(item.optString("alternative_name").takeIf { it.isNotBlank() && it != "null" }),
			url = url,
			publicUrl = "https://$domain$url",
			rating = rating,
			contentRating = if (isSafe) ContentRating.SAFE else ContentRating.SUGGESTIVE,
			coverUrl = cover,
			tags = tags,
			state = state,
			authors = setOfNotNull(item.optString("author").takeIf { it.isNotBlank() && it != "Unknown" }),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val json = apiClient.httpGet("$apiBase/comic/$slug", apiHeaders()).parseJson()
		val data = json.optJSONObject("data") ?: return manga

		val title = data.optString("title").ifBlank { manga.title }
		val sinopsis = data.optString("sinopsis").takeIf { it.isNotBlank() }
			?.replace(Regex("</?p\\s*/?>"), "")?.trim()
		val altName = data.optString("alternative_name").takeIf { it.isNotBlank() && it != "null" }
		val description = buildString {
			sinopsis?.let { append(it) }
			if (!altName.isNullOrBlank()) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative Names:")
				altName.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { append("\n").append(it) }
			}
		}.takeIf { it.isNotBlank() }

		val cover = data.optString("thumbnail").ifBlank { data.optString("cover") }
		val state = parseState(data.optString("status"))
		val author = data.optString("author").takeIf { it.isNotBlank() && it != "Unknown" }
		val rating = data.optDouble("rating", Double.NaN)
			.takeIf { !it.isNaN() }
			?.let { (it / 10f).toFloat().coerceIn(0f, 1f) }
			?: RATING_UNKNOWN

		val tags = LinkedHashSet<MangaTag>()
		data.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				val g = arr.optJSONObject(i) ?: continue
				val id = g.optInt("id", 0)
				val name = g.optString("name").ifBlank { continue }
				if (id > 0) tags.add(MangaTag(title = name, key = id.toString(), source = source))
			}
		}

		val chaptersArr = data.optJSONArray("chapters")
		val chapters = if (chaptersArr != null) {
			val list = ArrayList<MangaChapter>(chaptersArr.length())
			for (i in 0 until chaptersArr.length()) {
				val c = chaptersArr.optJSONObject(i) ?: continue
				val numStr = c.optString("number")
				val number = numStr.toFloatOrNull() ?: continue
				val chTitle = c.optString("title").ifBlank { "Chapter $numStr" }
				val chSlug = c.optString("slug").ifBlank { continue }
				val chUrl = "/chapter/$chSlug"
				val date = c.optJSONObject("created_at")?.optLong("time", 0L)?.let { it * 1000L } ?: 0L
				list.add(
					MangaChapter(
						id = generateUid(chUrl),
						title = chTitle,
						url = chUrl,
						number = number,
						volume = 0,
						scanlator = null,
						uploadDate = date,
						branch = null,
						source = source,
					),
				)
			}
			list.sortedBy { it.number }
		} else {
			emptyList()
		}

		val isSafe = optBoolOrInt(data, "is_safe", true)

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altName),
			description = description ?: manga.description,
			coverUrl = cover ?: manga.coverUrl,
			state = state,
			authors = setOfNotNull(author),
			tags = if (tags.isEmpty()) manga.tags else tags,
			rating = rating,
			contentRating = if (isSafe) ContentRating.SAFE else ContentRating.SUGGESTIVE,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.trimEnd('/').substringAfterLast('/')
		val json = apiClient.httpGet("$apiBase/chapters/slug/$slug", apiHeaders()).parseJson()
		val data = json.optJSONObject("data") ?: return emptyList()
		val arr = data.optJSONArray("images") ?: return emptyList()
		val pages = ArrayList<MangaPage>(arr.length())
		for (i in 0 until arr.length()) {
			val url = arr.optString(i).ifBlank { continue }
			pages.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private fun optBoolOrInt(jo: JSONObject, key: String, default: Boolean): Boolean {
		val v = jo.opt(key) ?: return default
		return when (v) {
			is Boolean -> v
			is Number -> v.toInt() != 0
			is String -> v.equals("true", true) || v == "1"
			else -> default
		}
	}

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed", "tamat", "finished", "end" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}
}

private object KomiknesiaSSL {

	// Let's Encrypt R12 intermediate (issued by ISRG Root X1; valid until 2027-03).
	// Bundled here because api-be.komiknesia.my.id only sends the leaf cert,
	// causing "Trust anchor for certification path not found" on older Android.
	private const val LE_R12_PEM = "-----BEGIN CERTIFICATE-----\n" +
		"MIIFBjCCAu6gAwIBAgIRAMISMktwqbSRcdxA9+KFJjwwDQYJKoZIhvcNAQELBQAw\n" +
		"TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
		"cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMjQwMzEzMDAwMDAw\n" +
		"WhcNMjcwMzEyMjM1OTU5WjAzMQswCQYDVQQGEwJVUzEWMBQGA1UEChMNTGV0J3Mg\n" +
		"RW5jcnlwdDEMMAoGA1UEAxMDUjEyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n" +
		"CgKCAQEA2pgodK2+lP474B7i5Ut1qywSf+2nAzJ+Npfs6DGPpRONC5kuHs0BUT1M\n" +
		"5ShuCVUxqqUiXXL0LQfCTUA83wEjuXg39RplMjTmhnGdBO+ECFu9AhqZ66YBAJpz\n" +
		"kG2Pogeg0JfT2kVhgTU9FPnEwF9q3AuWGrCf4yrqvSrWmMebcas7dA8827JgvlpL\n" +
		"Thjp2ypzXIlhZZ7+7Tymy05v5J75AEaz/xlNKmOzjmbGGIVwx1Blbzt05UiDDwhY\n" +
		"XS0jnV6j/ujbAKHS9OMZTfLuevYnnuXNnC2i8n+cF63vEzc50bTILEHWhsDp7CH4\n" +
		"WRt/uTp8n1wBnWIEwii9Cq08yhDsGwIDAQABo4H4MIH1MA4GA1UdDwEB/wQEAwIB\n" +
		"hjAdBgNVHSUEFjAUBggrBgEFBQcDAgYIKwYBBQUHAwEwEgYDVR0TAQH/BAgwBgEB\n" +
		"/wIBADAdBgNVHQ4EFgQUALUp8i2ObzHom0yteD763OkM0dIwHwYDVR0jBBgwFoAU\n" +
		"ebRZ5nu25eQBc4AIiMgaWPbpm24wMgYIKwYBBQUHAQEEJjAkMCIGCCsGAQUFBzAC\n" +
		"hhZodHRwOi8veDEuaS5sZW5jci5vcmcvMBMGA1UdIAQMMAowCAYGZ4EMAQIBMCcG\n" +
		"A1UdHwQgMB4wHKAaoBiGFmh0dHA6Ly94MS5jLmxlbmNyLm9yZy8wDQYJKoZIhvcN\n" +
		"AQELBQADggIBAI910AnPanZIZTKS3rVEyIV29BWEjAK/duuz8eL5boSoVpHhkkv3\n" +
		"4eoAeEiPdZLj5EZ7G2ArIK+gzhTlRQ1q4FKGpPPaFBSpqV/xbUb5UlAXQOnkHn3m\n" +
		"FVj+qYv87/WeY+Bm4sN3Ox8BhyaU7UAQ3LeZ7N1X01xxQe4wIAAE3JVLUCiHmZL+\n" +
		"qoCUtgYIFPgcg350QMUIWgxPXNGEncT921ne7nluI02V8pLUmClqXOsCwULw+PVO\n" +
		"ZCB7qOMxxMBoCUeL2Ll4oMpOSr5pJCpLN3tRA2s6P1KLs9TSrVhOk+7LX28NMUlI\n" +
		"usQ/nxLJID0RhAeFtPjyOCOscQBA53+NRjSCak7P4A5jX7ppmkcJECL+S0i3kXVU\n" +
		"y5Me5BbrU8973jZNv/ax6+ZK6TM8jWmimL6of6OrX7ZU6E2WqazzsFrLG3o2kySb\n" +
		"zlhSgJ81Cl4tv3SbYiYXnJExKQvzf83DYotox3f0fwv7xln1A2ZLplCb0O+l/AK0\n" +
		"YE0DS2FPxSAHi0iwMfW2nNHJrXcY3LLHD77gRgje4Eveubi2xxa+Nmk/hmhLdIET\n" +
		"iVDFanoCrMVIpQ59XWHkzdFmoHXHBV7oibVjGSO7ULSQ7MJ1Nz51phuDJSgAIU7A\n" +
		"0zrLnOrAj/dfrlEWRhCvAgbuwLZX1A2sjNjXoPOHbsPiy+lO1KF8/XY7\n" +
		"-----END CERTIFICATE-----\n"

	val trustManager: X509TrustManager by lazy { buildTrustManager() }

	val sslSocketFactory: javax.net.ssl.SSLSocketFactory by lazy {
		SSLContext.getInstance("TLS").apply {
			init(null, arrayOf<TrustManager>(trustManager), null)
		}.socketFactory
	}

	private fun buildTrustManager(): X509TrustManager {
		val cf = CertificateFactory.getInstance("X.509")
		val r12 = ByteArrayInputStream(LE_R12_PEM.toByteArray()).use {
			cf.generateCertificate(it) as X509Certificate
		}

		val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
		systemTmf.init(null as KeyStore?)
		val systemTm = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

		val ks = KeyStore.getInstance(KeyStore.getDefaultType())
		ks.load(null, null)
		systemTm.acceptedIssuers.forEachIndexed { i, c -> ks.setCertificateEntry("system-$i", c) }
		ks.setCertificateEntry("le-r12", r12)

		val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
		tmf.init(ks)
		return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
	}
}
