package eu.kanade.tachiyomi.extension.all.porncomicrbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.runWebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PornComic : KeiSource() {

    override val supportsLatest = false

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .build()
    }

    private val languageSlug: String?
        get() = when (lang) {
            "all" -> null
            "en" -> "english"
            "zh" -> "chinese"
            "ja" -> "japanese"
            "ko" -> "korean"
            "es" -> "spanish"
            "ru" -> "russian"
            "it" -> "italian"
            "fr" -> "french"
            "id" -> "indonesian"
            "ar" -> "arabic"
            "pl" -> "polish"
            "pt" -> "portuguese"
            "tl" -> "tagalog"
            "vi" -> "vietnamese"
            "uk" -> "ukrainian"
            "th" -> "thai"
            else -> null
        }

    private val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "en", "all" -> "Chapter"
            "pt" -> "Capítulo"
            "fr" -> "Chapitre"
            "de" -> "Kapitel"
            "it" -> "Capitolo"
            "ru" -> "Глава"
            "uk" -> "Розділ"
            "pl" -> "Rozdział"
            "id" -> "Bab"
            "tl" -> "Kabanata"
            "vi" -> "Chương"
            "th" -> "ตอน"
            "ar" -> "الفصل"
            "ja" -> "章"
            "ko" -> "챕터"
            "zh" -> "章节"
            else -> "Chapter"
        }

    private val pagesLabel: String
        get() = when (lang) {
            "es" -> "páginas"
            "en", "all" -> "pages"
            "pt" -> "páginas"
            "fr" -> "pages"
            "it" -> "pagine"
            "ru" -> "страниц"
            "uk" -> "сторінок"
            "pl" -> "stron"
            "id" -> "halaman"
            "tl" -> "pahina"
            "vi" -> "trang"
            "th" -> "หน้า"
            "ar" -> "صفحات"
            "ja" -> "ページ"
            "ko" -> "페이지"
            "zh" -> "页"
            else -> "pages"
        }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildListingUrl(page)
        val document = client.get(url).asJsoup()

        return parseListing(document, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    private fun buildListingUrl(page: Int): String {
        val slug = languageSlug

        return when {
            slug == null && page <= 1 -> "$baseUrl/"
            slug == null -> "$baseUrl/index-$page.html"
            page <= 1 -> "$baseUrl/language/$slug.html"
            else -> "$baseUrl/language/$slug-$page.html"
        }
    }

    private fun parseListing(
        document: Document,
        page: Int,
    ): MangasPage {
        val mangas = document
            .select("ul#list > li")
            .mapNotNull(::listingMangaFromElement)

        val nextPage = page + 1
        val hasNextPage = document
            .select(".page a[href], .bigpage a[href]")
            .any { link ->
                link.text().equals("Next", ignoreCase = true) ||
                    link.text() == nextPage.toString()
            }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun listingMangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.title[href]")
            ?: element.selectFirst("span > a[href]")
            ?: return null

        val href = link.attr("abs:href")
        val title = cleanTitle(link.text())

        if (href.isBlank() || title.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = normalizeThumbnailUrl(element.imageUrl())
        }
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return MangasPage(
                mangas = emptyList(),
                hasNextPage = false,
            )
        }

        val encodedQuery = java.net.URLEncoder
            .encode(cleanQuery, Charsets.UTF_8.name())
            .replace("+", "%20")

        val entryUrl = "$baseUrl/q/$encodedQuery-1.html"

        val tokenizedUrl = noRedirectClient
            .get(
                entryUrl,
                ensureSuccess = false,
            )
            .use { response ->
                if (response.code !in 300..399) {
                    error("Search redirect failed: HTTP ${response.code}")
                }

                response
                    .header("Location")
                    ?.replace(" ", "%20")
                    ?: error("Search redirect did not return Location")
            }

        val targetUrl = if (page <= 1) {
            tokenizedUrl
        } else {
            SEARCH_PAGE_REGEX.replace(tokenizedUrl) { match ->
                "-$page-${match.groupValues[1]}.html"
            }
        }

        val html = runWebView<String>(
            timeout = 60.seconds,
        ) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockImages = false

            poll(500.milliseconds) {
                evaluateJs(
                    "document.querySelectorAll('ul#list > li').length.toString()",
                ) { count ->
                    if (count.trim('"').toIntOrNull()?.let { it > 0 } != true) {
                        return@evaluateJs
                    }

                    evaluateJs(
                        "document.documentElement.outerHTML",
                    ) { pageHtml ->
                        val decodedHtml = runCatching {
                            Json.decodeFromString<String>(pageHtml)
                        }.getOrElse {
                            pageHtml
                                .removePrefix("\"")
                                .removeSuffix("\"")
                        }

                        resolve(decodedHtml)
                    }
                }
            }

            loadUrl(targetUrl)
        }

        val document = Jsoup.parse(
            html,
            targetUrl,
        )

        val mangas = document
            .select("ul#list > li")
            .mapNotNull(::searchMangaFromElement)

        val hasNextPage = document
            .select(".page a[href], .bigpage a[href]")
            .map { it.attr("href") }
            .any { href ->
                SEARCH_RESULT_PAGE_REGEX
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull() == page + 1
            }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun searchMangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.title[href]")
            ?: element.selectFirst("span > a[href]")
            ?: return null

        val href = link.attr("abs:href")
            .ifBlank { link.attr("href") }
            .let { raw ->
                when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("/") -> "$baseUrl$raw"
                    else -> raw
                }
            }

        val title = cleanTitle(link.text())

        if (href.isBlank() || title.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = normalizeThumbnailUrl(element.imageUrl())
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val baseHost = baseUrl.toHttpUrl().host

        if (
            url.host != baseHost ||
            !MANGA_PATH_REGEX.matches(url.encodedPath)
        ) {
            return null
        }

        val document = client.get(url).asJsoup()

        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(
                manga = manga,
                chapters = chapters,
            )
        }

        val document = client
            .get(getMangaUrl(manga))
            .asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document).apply {
                setUrlWithoutDomain(document.location())
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(parseChapter(document))
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = cleanTitle(
            document
                .selectFirst("h1.title")
                ?.text()
                .orEmpty(),
        )

        thumbnail_url = document
            .selectFirst(".manga-page .manga-picture img")
            ?.imageUrl()

        val artists = document
            .select(".summary a[href*=/artist/]")
            .eachText()
            .map(::capitalizeWords)
            .filter(String::isNotBlank)
            .distinct()

        author = artists
            .joinToString()
            .takeIf(String::isNotBlank)

        artist = null

        genre = document
            .select(".manga-tags a[href*=/tags/]")
            .eachText()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString()
            .takeIf(String::isNotBlank)

        val language = document
            .selectFirst(".info a.lang_cat")
            ?.text()
            ?.takeIf(String::isNotBlank)

        val parodies = document
            .select(".summary a[href*=/anime/]")
            .eachText()
            .filter(String::isNotBlank)
            .distinct()

        val characters = document
            .select(".summary a[href*=/characters/]")
            .eachText()
            .filter(String::isNotBlank)
            .distinct()

        val topics = document
            .select(".summary a[href*=/circle/]")
            .eachText()
            .filter(String::isNotBlank)
            .distinct()

        val hits = document
            .selectFirst(".info .hits")
            ?.text()
            ?.takeIf(String::isNotBlank)

        val pageCount = document.pageCount()

        description = buildList {
            language?.let { add("Language: $it") }
            if (parodies.isNotEmpty()) {
                add("Parodies: ${parodies.joinToString()}")
            }
            if (characters.isNotEmpty()) {
                add("Characters: ${characters.joinToString()}")
            }
            if (topics.isNotEmpty()) {
                add("Topics: ${topics.joinToString()}")
            }
            if (pageCount > 0) {
                add("Pages: $pageCount")
            }
            hits?.let { add("Hits: $it") }
        }
            .joinToString("\n")
            .takeIf(String::isNotBlank)

        status = SManga.COMPLETED
        initialized = true
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun parseChapter(document: Document): SChapter {
        val pageCount = document.pageCount()

        return SChapter.create().apply {
            name = chapterLabel
            chapter_number = 1f
            date_upload = parseDate(document)
            scanlator = if (pageCount > 0) {
                "$pageCount $pagesLabel"
            } else {
                null
            }
            setUrlWithoutDomain(document.location())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()

        val pageCount = document.pageCount()
        if (pageCount <= 0) return emptyList()

        val baseChapterUrl = normalizeMangaPageUrl(chapterUrl)

        return (1..pageCount).mapIndexed { index, pageNumber ->
            Page(
                index = index,
                url = if (pageNumber == 1) {
                    baseChapterUrl
                } else {
                    baseChapterUrl.removeSuffix(".html") +
                        "-$pageNumber.html"
                },
            )
        }
    }

    override suspend fun getImageUrl(page: Page): String = client
        .get(page.url)
        .asJsoup()
        .selectFirst(".manga-page .manga-picture img")
        ?.imageUrl()
        .orEmpty()

    private fun Document.pageCount(): Int = select("#pages a, #pages span")
        .mapNotNull { it.text().toIntOrNull() }
        .maxOrNull()
        ?: if (selectFirst(".manga-picture img") != null) 1 else 0

    private fun parseDate(document: Document): Long {
        val rawDate = document
            .select(".info span")
            .firstOrNull { it.text().startsWith("Date:", ignoreCase = true) }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            .orEmpty()

        return runCatching {
            LocalDate
                .parse(rawDate, DATE_FORMAT)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun normalizeMangaPageUrl(url: String): String = url.replace(MANGA_PAGE_SUFFIX_REGEX, ".html")

    private fun cleanTitle(value: String): String = value
        .replace(BRACKET_BLOCK_REGEX, " ")
        .replace(MULTIPLE_SPACES_REGEX, " ")
        .trim()
        .trim('|')
        .trim()

    private fun capitalizeWords(value: String): String = value
        .lowercase(Locale.getDefault())
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.getDefault())
                } else {
                    char.toString()
                }
            }
        }

    private fun normalizeThumbnailUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url

        val match = BROKEN_THUMBNAIL_REGEX.matchEntire(url)
            ?: return url

        val schemeHost = match.groupValues[1]
        val category = match.groupValues[2]
        val folder = match.groupValues[3]
        val filename = match.groupValues[4]

        return "$schemeHost/$category/$folder/$filename"
    }

    private fun Element.imageUrl(): String? {
        val picture = if (tagName() == "picture") {
            this
        } else {
            selectFirst("picture")
        }

        val preferredSource = picture
            ?.select("source[srcset]")
            ?.mapNotNull { source ->
                val type = source.attr("type").lowercase()
                val url = sequenceOf(
                    source.attr("abs:srcset"),
                    source.attr("srcset"),
                )
                    .map { value ->
                        value
                            .substringBefore(",")
                            .trim()
                            .substringBefore(" ")
                            .trim()
                    }
                    .firstOrNull(String::isNotBlank)
                    ?: return@mapNotNull null

                if (url.isSearchPlaceholder()) {
                    return@mapNotNull null
                }

                val priority = when (type) {
                    "image/avif" -> 0
                    "image/webp" -> 1
                    else -> 2
                }

                priority to url
            }
            ?.minByOrNull { it.first }
            ?.second

        if (!preferredSource.isNullOrBlank()) {
            return preferredSource
        }

        val img = if (tagName() == "img") {
            this
        } else {
            selectFirst("img")
        } ?: return null

        return sequenceOf(
            img.attr("abs:data-src"),
            img.attr("abs:data-original"),
            img.attr("abs:src"),
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("src"),
        )
            .firstOrNull(String::isNotBlank)
            ?.takeUnless { it.isSearchPlaceholder() }
    }

    private fun String.isSearchPlaceholder(): Boolean = contains("/statics/images/up_browser.", ignoreCase = true)

    private companion object {
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

        val MANGA_PATH_REGEX =
            Regex("""/h/\d+(?:-\d+)?\.html""")

        val MANGA_PAGE_SUFFIX_REGEX =
            Regex("""-\d+\.html$""")

        val SEARCH_PAGE_REGEX =
            Regex("""-\d+-([a-fA-F0-9]{32})\.html$""")

        val SEARCH_RESULT_PAGE_REGEX =
            Regex("""-(\d+)-[a-fA-F0-9]{32}\.html$""")

        val BROKEN_THUMBNAIL_REGEX =
            Regex("""^(https?://[^/]+)/re/(eh|nh2?)/(\d+)/thumb_500_425_(.+)$""")

        val BRACKET_BLOCK_REGEX =
            Regex("""\[[^\]]*]""")

        val MULTIPLE_SPACES_REGEX =
            Regex("""\s{2,}""")
    }
}
