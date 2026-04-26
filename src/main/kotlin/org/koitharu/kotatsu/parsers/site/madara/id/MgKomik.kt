package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class MgKomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc") {

	// id.mgkomik.cc is a Madara WordPress site (mangaSubString=komik). The previous
	// MangaReaderParser config was wrong and produced an empty list once Cloudflare
	// cleared; the Tachiyomi-side keiyoushi extension confirms it's a Madara site.
	override val listUrl = "komik/"
	override val tagPrefix = "komik-genre/"
	override val datePattern = "dd MMM yy"

	// Cloudflare on this domain has a custom firewall rule that returns Error 1020
	// ("Sorry, you have been blocked") whenever a request carries an `X-Requested-With`
	// header (regardless of value). The previous keiyoushi-style trick of forging a
	// random `X-Requested-With` and stripping it later was actually triggering the rule
	// on the in-app webview challenge requests, which is why users still got blocked
	// despite the OkHttp interceptor scrubbing it. Send only the safe browser-shape
	// hints; everything else falls back to the parser default User-Agent.
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "same-origin")
		.add("Upgrade-Insecure-Requests", "1")
		.build()
}
