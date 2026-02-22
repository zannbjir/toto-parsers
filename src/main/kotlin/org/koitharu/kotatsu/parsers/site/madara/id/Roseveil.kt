package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*

@MangaSourceParser("ROSEVEIL", "Roseveil", "id")
internal class Roseveil(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ROSEVEIL, "roseveil.org") {
    override val listUrl = "comic"
    override val tagPrefix = "manga-genre/"
    override val datePattern = "MMMM dd, yyyy"
}
