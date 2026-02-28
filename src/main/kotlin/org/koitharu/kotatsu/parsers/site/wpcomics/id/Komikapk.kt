package org.koitharu.kotatsu.parsers.site.wpcomics.id

import androidx.collection.ArrayMap
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikApk", "id")
internal class Komikapk(context: MangaLoaderContext) :
    WpComicsParser(context, MangaParserSource.KOMIKAPK, "komikapk.app") {

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override val listUrl = "/pustaka"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/pencarian?q=${filter.query.urlEncoded()}&is-adult=on"
        } else {
            val tag = filter.tags.firstOrNull()?.key ?: "semua"
            val sort = when (order) {
                SortOrder.POPULARITY -> "terpopuler"
                else -> "terbaru"
            }
            "https://$domain$listUrl/semua/$tag/$sort/$page?include_adult=true"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc, ArrayMap())
    }

    override fun parseMangaList(doc: Document, tagMap: ArrayMap<String, MangaTag>): List<Manga> {
        return doc.select(".listupd .bs, .grid-container .bs, .grid-item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val mangaUrl = a.attrAsRelativeUrl("href")
            
            if (!mangaUrl.contains("/komik/")) return@mapNotNull null

            Manga(
                id = generateUid(mangaUrl),
                title = el.select(".tt, .title, h3").text().trim(),
                altTitles = emptySet(),
                url = mangaUrl,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = el.selectFirst("img")?.src().orEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        
        val chapters = doc.select(".clist a, #chapterlist a, a[href*='/kmapk/']").mapIndexed { i, el ->
            val href = el.attrAsRelativeUrl("href")
            MangaChapter(
                id = generateUid(href),
                title = el.text().trim(),
                url = href,
                number = el.text().replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (i + 1f),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.filterNotNull().reversed()

        return manga.copy(
            description = doc.select(".entry-content p, .desc, .synopsis").text().trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        return doc.select("#readerarea img, .reader-area img").mapNotNull { img ->
            val url = img.attr("data-src").takeIf { it.isNotEmpty() } 
                      ?: img.attr("src") 
                      ?: return@mapNotNull null
            
            if (url.contains("logo") || url.isBlank()) return@mapNotNull null
            
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getOrCreateTagMap(): ArrayMap<String, MangaTag> {
        val genreList = setOf(
            "3d", "action", "adventure", "anime", "comedy", "crime", "drama", "ecchi", "fantasy", 
            "film", "game", "horror", "historical", "isekai", "magic", "martial-arts", "mecha", 
            "medical", "military", "music", "mystery", "philosophical", "psychology", "psychological", 
            "reincarnation", "romance", "romantic", "sci-fi", "shoujo", "shounen", "seinen", "josei", 
            "slice-of-life", "sports", "supernatural", "thriller", "tragedy", "wuxia",
            "doujin", "doujinshi", "manga", "manhua", "manhwa", "webtoon", "parody", "indie",
            "action-adventure", "body-swap", "drama-romance", "game", "school-life", "school-uniform",
            "story-arc", "tankoubon",
            "shoujo-ai", "shounen-ai", "boys-love", "girls-love", "yaoi", "yuri",
            "elf", "demon", "demon-girl", "monster", "monster-girl", "monster-girls", "ghost", 
            "robot", "furry", "kemonomimi", "kemomimi", "cat-girl", "catgirl", "fox-girl", 
            "mouse-girl", "bunny-girl", "angel", "vampire", "succubus", "slime", "doll",
            "aunt", "cousin", "daughter", "family", "girlfriend", "friends", "mother", "niece", 
            "sister", "uncle", "wife", "housewife", "house-wife", "office-lady", "teacher", 
            "maid", "nurse", "nun", "princess", "bride", "prostitution",
            "bald", "short-hair", "light-hair", "curly-hair", "long-hair", "ponytail", "twintail", 
            "twintails", "pony-tail", "glasses", "eyepatch", "fangs", "horns", "tail", "hairy", 
            "dark-skin", "darkskin", "tanlines", "beauty-mark", "beautymark", "piercing",
            "big-breast", "big-breasts", "big-ass", "big-dick", "big-cock", "big-penis", 
            "small-breast", "small-breasts", "busty", "chubby", "muscle", "muscles", "petite", 
            "huge-breast", "huge-breasts", "huge-boobs", "bbw", "amputee",
            "apron", "bikini", "kimono", "lingerie", "lingeri", "pantyhose", "swimsuit", 
            "stocking", "stockings", "school-girl-uniform", "schoolgirl-outfit", "business-suit", 
            "bodysuit", "leotard", "garter-belt", "hotpants", "gym-outfit", "cosplay",
            "adult", "hentai", "smut", "uncensored", "uncensore", "uncencored", "creampie", 
            "anal", "anal-intercourse", "blowjob", "blow-job", "bondage", "bdsm", "domination", 
            "femdom", "handjob", "footjob", "paizuri", "rimjob", "facesitting", "cowgirl", 
            "mating-press", "nakadashi", "deepthroat", "gang-bang", "gangbang", "group-sex", 
            "threesome", "foursome", "orgy", "masturbation", "masturbasi", "fingering", 
            "cunnilingus", "breast-feeding", "lactation", "squirting", "kissing", "licking", 
            "biting", "sweating", "tentacles", "impregnation", "impregnate", "pregnant",
            "feet", "foot-fetish", "guro", "scat", "inflation", "semen", "bukkake", "ejaculation", 
            "double-penetration", "fisting", "rough-sex", "harsh", "mindbreak", "corruption", 
            "enslavement", "slavery", "slave",
            "bisexual", "homosexual", "lesbian", "lesbi", "gay",
            "tsundere", "kuudere", "yandere", "deredere", "osananajimi", "ojousama", "gyaru",
            "chains", "collar", "leash", "whip", "spanking", "slapping", "choking", 
            "gagging", "blindfold", "blinfold",
            "beach", "bathroom", "hot-spring", "hotspring", "love-hotel", "hotel", "school", 
            "highschool", "school-life", "outdoors", "public", "hidden-sex",
            "loli", "lolicon", "lolipai", "shota", "shotacon",
            "affair", "cheating", "netorare", "netorase", "netori", "ntr", "voyeurism", 
            "exhibitionism", "blackmail", "rape", "forced", "non-consent", "drugs", "drunk", "sleep", 
            "sleeping", "hypnosis", "hipnotis", "mind-control", "mind-break", "invisible",
            "full-color", "full-colour", "rough-translation", "sub-indo"
        )
        val uniqueGenres = genreList.toSet().toList()
        val result = ArrayMap<String, MangaTag>(uniqueGenres.size)
        for (genre in genreList) {
            val key = genre.lowercase().replace(" ", "-")
            result[genre] = MangaTag(title = genre, key = key, source = source)
        }
        return result
    }
}
