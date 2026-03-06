package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANGATOON", "MangaToon", "id", ContentType.HENTAI)
internal class Mangatoon(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGATOON, "komik6.mangatoon.cc", pageSize = 20, searchPageSize = 10) {

    override val configKeyDomain = ConfigKey.Domain(
        "komik6.mangatoon.cc",
        "komik5.mangatoon.cc",
        "komik4.mangatoon.cc",
        "komik3.mangatoon.cc",
        "komik2.mangatoon.cc",
        "komik1.mangatoon.cc"
    )

    override val datePattern = "MMM d, yyyy"
}
