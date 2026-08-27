package eu.kanade.tachiyomi.extension.all.hentairunrbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.runWebView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HentaiRun : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", CLOUDFLARE_USER_AGENT)

    private val languageSlug: String?
        get() = Filters.languageFromSourceLang(lang)

    private val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "en", "all" -> "Chapter"
            "fr" -> "Chapitre"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            "ja" -> "章"
            "ko" -> "챕터"
            else -> "Chapter"
        }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val slug = languageSlug

        val url = if (slug == null) {
            "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("sort", "popular")
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }
                .build()
        } else {
            val path = if (page == 1) {
                "$baseUrl/language/$slug/popular/"
            } else {
                "$baseUrl/language/$slug/popular/page/$page/"
            }
            path.toHttpUrl()
        }

        return parseMangasPage(webViewListingDocument(url.toString()), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val slug = languageSlug

        val path = when {
            slug == null && page == 1 -> "$baseUrl/"
            slug == null -> "$baseUrl/page/$page/"
            page == 1 -> "$baseUrl/language/$slug/"
            else -> "$baseUrl/language/$slug/page/$page/"
        }

        return parseMangasPage(webViewListingDocument(path), page)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val config = Filters.parse(filters, lang)

        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query.trim())
                }

                addQueryParameter("sort", config.sort)

                if (config.categories.isNotEmpty()) {
                    addQueryParameter(
                        "categories",
                        config.categories.joinToString(","),
                    )
                }

                if (config.languages.isNotEmpty()) {
                    addQueryParameter(
                        "langs",
                        config.languages.joinToString(","),
                    )
                }

                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        return parseMangasPage(webViewListingDocument(url.toString()), page)
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList(lang)

    private fun parseMangasPage(
        document: Document,
        page: Int,
    ): MangasPage {
        val mangas = document
            .select("""a[href*="/gallery/"]""")
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }

        val nextPage = page + 1

        val hasNextPageByLink = document
            .select("a[href]")
            .any { element ->
                val href = element.attr("href")
                href.contains("/page/$nextPage/") ||
                    href.contains("page=$nextPage")
            }

        val hasNextPageByButton = document
            .select("nav button")
            .any { button ->
                val text = button.text().trim()

                (!button.hasAttr("disabled") && text.equals("Next ›", ignoreCase = true)) ||
                    (text.toIntOrNull()?.let { it > page } == true)
            }

        val hasNextPage = hasNextPageByLink || hasNextPageByButton

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun mangaFromElement(element: Element): SManga? {
        val image = element.selectFirst("img") ?: return null
        val href = element.attr("abs:href")

        if (!href.contains("/gallery/")) return null

        val rawTitle = image.attr("alt")
            .ifBlank { element.attr("aria-label") }
            .ifBlank { element.text() }
            .trim()

        if (rawTitle.isBlank()) return null

        val imageUrl = element.imageUrl()
            ?: image.imageUrl()

        return SManga.create().apply {
            title = rawTitle.cleanTitle()
            setUrlWithoutDomain(href)
            thumbnail_url = imageUrl?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (!url.encodedPath.startsWith("/gallery/")) return null

        val document = webViewDetailDocument(url.toString())
        if (document.selectFirst("h1") == null) return null

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

        val document = webViewDetailDocument(getMangaUrl(manga))

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document).apply {
                url = manga.url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOfNotNull(parseChapter(document))
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    private fun parseMangaDetails(document: Document): SManga {
        val webArtists = document
            .select("""a[href^="/artist/"]""")
            .mapNotNull(::metadataName)
            .map { it.capitalizedName() }
            .distinctBy { it.lowercase() }

        val webGroups = document
            .select("""a[href^="/group/"]""")
            .mapNotNull(::metadataName)
            .map { it.capitalizedName() }
            .distinctBy { it.lowercase() }

        val finalAuthors: List<String>
        val finalArtists: List<String>

        if (webArtists.isEmpty() && webGroups.isNotEmpty()) {
            finalAuthors = webGroups
            finalArtists = emptyList()
        } else {
            finalAuthors = webArtists
            val authorKeys = finalAuthors.map { it.lowercase() }.toSet()
            finalArtists = webGroups.filterNot { it.lowercase() in authorKeys }
        }

        val tags = document
            .select("""a[href^="/tag/"]""")
            .mapNotNull(::metadataName)
            .map { it.capitalizedName() }
            .distinctBy { it.lowercase() }

        return SManga.create().apply {
            title = document
                .selectFirst("h1")
                ?.text()
                ?.cleanTitle()
                .orEmpty()

            thumbnail_url = document
                .selectFirst("""meta[property="og:image"]""")
                ?.attr("content")
                ?.takeIf(String::isNotBlank)

            author = finalAuthors
                .joinToString(", ")
                .takeIf(String::isNotBlank)

            artist = finalArtists
                .joinToString(", ")
                .takeIf(String::isNotBlank)

            genre = tags
                .joinToString(", ")
                .takeIf(String::isNotBlank)

            val language = document
                .selectFirst("""a[href^="/language/"]""")
                ?.let(::metadataName)
                ?.capitalizedName()

            val category = document
                .selectFirst("""a[href^="/category/"]""")
                ?.let(::metadataName)
                ?.capitalizedName()

            description = buildList {
                if (lang == "all") {
                    language?.let { add("$languageLabel: $it") }
                }

                category?.let { add("$categoryLabel: $it") }
            }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }

            status = SManga.COMPLETED
            initialized = true
        }
    }

    private fun parseChapter(document: Document): SChapter? {
        val html = document.html()

        val galleryNumericId = readerDataRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: document.location()
                .substringAfter("/gallery/", "")
                .substringBefore("/")
                .takeIf(String::isNotBlank)
            ?: return null

        val pageCount = readerDataRegex
            .find(html)
            ?.groupValues
            ?.get(5)
            ?.toIntOrNull()
            ?: pagesCountRegex.find(html)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            ?: 0

        return SChapter.create().apply {
            name = chapterLabel
            chapter_number = 1f
            scanlator = localizedPages(pageCount)
            setUrlWithoutDomain("$baseUrl/view/$galleryNumericId/1/")
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val html = webViewHtml(
            url = chapterUrl.toString(),
            readySelector = "#reader-scroll-root",
        )

        val galleryId = viewerGalleryIdRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val imgDir = viewerImgDirRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val server = viewerServerRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val pages = pageEntryRegex
            .findAll(html)
            .map { match ->
                match.groupValues[1].toInt() to match.groupValues[2]
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()

        return pages.mapIndexed { index, (page, ext) ->
            Page(
                index = index,
                imageUrl = "https://m$server.hentairun.com/$imgDir/$galleryId/$page.$ext",
            )
        }
    }

    private suspend fun webViewListingDocument(url: String): Document = Jsoup.parse(
        webViewHtml(
            url = url,
            readySelector = """a[href*="/gallery/"] img""",
        ),
        url,
    )

    private suspend fun webViewDetailDocument(url: String): Document = Jsoup.parse(
        webViewHtml(
            url = url,
            readySelector = "h1",
        ),
        url,
    )

    private suspend fun webViewHtml(
        url: String,
        readySelector: String,
    ): String = runWebView<String>(
        timeout = 60.seconds,
    ) {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockImages = false
        userAgent = CLOUDFLARE_USER_AGENT

        poll(500.milliseconds) {
            evaluateJs(
                """
                document.querySelectorAll(${Json.encodeToString(readySelector)}).length.toString()
                """.trimIndent(),
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

        loadUrl(url)
    }

    private fun Element.imageUrl(): String? {
        val picture = if (tagName() == "picture") {
            this
        } else {
            parent()
                ?.takeIf { it.tagName() == "picture" }
                ?: selectFirst("picture")
        }

        val sourceUrl = picture
            ?.select("source[srcset]")
            ?.asSequence()
            ?.mapNotNull { source ->
                sequenceOf(
                    source.attr("abs:srcset"),
                    source.attr("srcset"),
                )
                    .map { srcset ->
                        srcset
                            .substringBefore(",")
                            .trim()
                            .substringBefore(" ")
                            .trim()
                    }
                    .firstOrNull { it.isNotBlank() }
            }
            ?.firstOrNull()

        if (!sourceUrl.isNullOrBlank()) {
            return sourceUrl
        }

        val img = when {
            tagName() == "img" -> this
            else -> selectFirst("img")
        } ?: return null

        return sequenceOf(
            img.attr("abs:data-src"),
            img.attr("abs:data-original"),
            img.attr("abs:data-lazy-src"),
            img.attr("abs:src"),
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("data-lazy-src"),
            img.attr("src"),
        )
            .firstOrNull { it.isNotBlank() }
    }

    private val languageLabel: String
        get() = when (lang) {
            "es" -> "Idioma"
            "fr" -> "Langue"
            "de" -> "Sprache"
            "ru" -> "Язык"
            "ja" -> "言語"
            "ko" -> "언어"
            else -> "Language"
        }

    private val categoryLabel: String
        get() = when (lang) {
            "es" -> "Categoría"
            "fr" -> "Catégorie"
            "de" -> "Kategorie"
            "ru" -> "Категория"
            "ja" -> "カテゴリー"
            "ko" -> "카테고리"
            else -> "Category"
        }

    private fun metadataName(element: Element): String? {
        val firstSpan = element.selectFirst("span")
            ?.text()
            ?.trim()

        if (!firstSpan.isNullOrBlank()) {
            return firstSpan
        }

        return element
            .ownText()
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun String.cleanTitle(): String {
        var result = trim()

        result = result.replace(
            BRACKET_REGEX,
            " ",
        )

        result = result.replace(PAREN_REGEX) { match ->
            val content = match.groupValues[1].trim()

            if (TRANSLATION_NOISE_REGEX.containsMatchIn(content)) {
                " "
            } else {
                "($content)"
            }
        }

        return result
            .replace(WHITESPACE_REGEX, " ")
            .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "$1")
            .trim()
    }

    private fun String.capitalizedName(): String = trim()
        .split(WHITESPACE_REGEX)
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            word.replace(CAPITALIZE_WORD_REGEX) { match ->
                match.value.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
        }

    private fun localizedPages(count: Int): String = when (lang) {
        "es" -> "$count páginas"
        "fr" -> "$count pages"
        "de" -> "$count Seiten"
        "ru" -> "$count страниц"
        "ja" -> "$count ページ"
        "ko" -> "$count 페이지"
        else -> "$count pages"
    }

    private companion object {
        const val CLOUDFLARE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/152.0.0.0 Mobile Safari/537.36"

        val BRACKET_REGEX = Regex("""\s*\[[^\]]*]\s*""")
        val PAREN_REGEX = Regex("""\(([^()]*)\)""")
        val WHITESPACE_REGEX = Regex("""\s+""")
        val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("""\s+([,.;:!?])""")
        val CAPITALIZE_WORD_REGEX = Regex("""[^\s\-_/]+""")

        val TRANSLATION_NOISE_REGEX = Regex(
            """\b(?:translated|translation|translate|traslated|traslation|traslate|english\s*trans(?:lation|lated)?|eng\s*trans(?:lation|lated)?|spanish\s*trans(?:lation|lated)?|esp\s*trans(?:lation|lated)?)\b""",
            RegexOption.IGNORE_CASE,
        )

        // Detail: id, server, img_dir, gallery_id, pages.
        val readerDataRegex = Regex(
            """\\"id\\":\\"?(\d+)\\"?.{0,300}?\\"server\\":(\d+).{0,120}?\\"img_dir\\":\\"([^"]+)\\".{0,120}?\\"gallery_id\\":\\"([^"]+)\\".{0,120}?\\"pages\\":(\d+)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        val pagesCountRegex = Regex(
            """\\"pages\\":(\d+)""",
        )

        val viewerGalleryIdRegex = Regex(
            """\\"galleryId\\":\\"([^"]+)\\"""",
        )

        val viewerImgDirRegex = Regex(
            """\\"imgDir\\":\\"([^"]+)\\"""",
        )

        val viewerServerRegex = Regex(
            """\\"server\\":(\d+)""",
        )

        val pageEntryRegex = Regex(
            """\\"page\\":(\d+),\\"ext\\":\\"([^"]+)\\"""",
        )
    }
}
