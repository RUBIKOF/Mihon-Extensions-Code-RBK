package eu.kanade.tachiyomi.extension.all.hentaierarbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HentaiEra : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = if (lang == "all") {
            client.get(
                buildSearchUrl(
                    page = page,
                    query = "",
                    sortParam = "pp",
                    typeParams = ALL_TYPE_PARAMS,
                    languageParams = ALL_LANGUAGE_PARAMS,
                ),
            ).asJsoup()
        } else {
            client
                .get("$baseUrl/language/$languageSlug/popular/?page=$page")
                .asJsoup()
        }

        return document.toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = if (lang == "all") {
            client.get(
                buildSearchUrl(
                    page = page,
                    query = "",
                    sortParam = "lt",
                    typeParams = ALL_TYPE_PARAMS,
                    languageParams = ALL_LANGUAGE_PARAMS,
                ),
            ).asJsoup()
        } else {
            client
                .get("$baseUrl/language/$languageSlug/?page=$page")
                .asJsoup()
        }

        return document.toMangasPage()
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sortParam = filters
            .filterIsInstance<SortFilter>()
            .firstOrNull()
            ?.selectedParam
            ?: "lt"

        val selectedTypeParams = filters
            .filterIsInstance<TypeFilter>()
            .firstOrNull()
            ?.selectedParams
            ?.takeIf { it.isNotEmpty() }
            ?: ALL_TYPE_PARAMS

        val selectedLanguageParams = if (lang == "all") {
            filters
                .filterIsInstance<LanguageFilter>()
                .firstOrNull()
                ?.selectedParams
                ?.takeIf { it.isNotEmpty() }
                ?: ALL_LANGUAGE_PARAMS
        } else {
            listOf(sourceLanguageParam)
        }

        val document = client.get(
            buildSearchUrl(
                page = page,
                query = query,
                sortParam = sortParam,
                typeParams = selectedTypeParams,
                languageParams = selectedLanguageParams,
            ),
        ).asJsoup()

        return document.toMangasPage()
    }

    override fun getFilterList(data: JsonElement?): FilterList = if (lang == "all") {
        FilterList(
            SortFilter(),
            TypeFilter(),
            LanguageFilter(),
        )
    } else {
        FilterList(
            SortFilter(),
            TypeFilter(),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (
            url.host != baseUrl.toHttpUrl().host ||
            !url.encodedPath.startsWith("/gallery/")
        ) {
            return null
        }

        return client
            .get(url)
            .asJsoup()
            .parseMangaDetails()
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
            document.parseMangaDetails()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(document.parseChapter(manga.url))
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client
            .get(getChapterUrl(chapter))
            .asJsoup()

        val pageCount = document.pageCount()
        if (pageCount <= 0) return emptyList()

        val firstViewUrl = document
            .selectFirst("a[href*=/view/][href$=/1/]")
            ?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
            ?: document
                .selectFirst("a[href*=/view/]")
                ?.attr("abs:href")
                ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val firstViewDocument = client
            .get(firstViewUrl)
            .asJsoup()

        val firstImageUrl = firstViewDocument
            .selectFirst("img[class*=image_], img[alt*=page][alt*=full]")
            ?.imageUrl()
            ?: return emptyList()

        val imageBase = firstImageUrl.substringBeforeLast("/")
        val extension = firstImageUrl
            .substringAfterLast("/")
            .substringAfterLast(".", "")
            .substringBefore("?")
            .takeIf(String::isNotBlank)
            ?: return emptyList()

        return (1..pageCount).mapIndexed { index, page ->
            Page(
                index = index,
                imageUrl = "$imageBase/$page.$extension",
            )
        }
    }

    private fun Document.toMangasPage(): MangasPage {
        val mangas = select("div.thumbnail")
            .mapNotNull(::mangaFromElement)

        val hasNextPage = select(".pagination a[href], ul.pagination a[href]")
            .any { it.text().contains("next", ignoreCase = true) }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun mangaFromElement(element: Element): SManga? {
        val titleNode = element.selectFirst(".g_text")
            ?: return null

        val link = element.selectFirst(
            ".g_text a[href], .inner_thumb a[href]",
        ) ?: return null

        val title = titleNode.text().trim()
        if (title.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(link.attr("href"))
            thumbnail_url = element
                .selectFirst(".inner_thumb img")
                ?.imageUrl()
        }
    }

    private fun Document.parseMangaDetails(): SManga = SManga.create().apply {
        title = selectFirst("h1")
            ?.text()
            ?.trim()
            .orEmpty()

        thumbnail_url = selectFirst(
            "img[alt$=cover], img[src*=cover], img[data-src*=cover]",
        )?.imageUrl()

        val artists = select(
            "a[href^=/artist/], a[href*='hentaiera.com/artist/']",
        )
            .map { it.text().capitalizeName() }
            .filter(String::isNotBlank)
            .distinct()

        author = artists
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        artist = author

        genre = select(
            "a[href^=/tag/], a[href*='hentaiera.com/tag/']",
        )
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        description = null
        status = SManga.COMPLETED
        initialized = true
    }

    private fun Document.parseChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        url = mangaUrl
        name = chapterName
        chapter_number = 1f
        scanlator = localizedPages(pageCount())
        date_upload = 0L
    }

    private fun Document.pageCount(): Int {
        val info = selectFirst("ul.galleries_info")
            ?.text()
            .orEmpty()

        return PAGE_COUNT_REGEX
            .find(info)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun buildSearchUrl(
        page: Int,
        query: String,
        sortParam: String,
        typeParams: List<String>,
        languageParams: List<String>,
    ): HttpUrl {
        val builder = "$baseUrl/search/"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", query.trim())

        SORT_PARAMS.forEach { param ->
            builder.addQueryParameter(
                param,
                if (param == sortParam) "1" else "0",
            )
        }

        ALL_TYPE_PARAMS.forEach { param ->
            builder.addQueryParameter(
                param,
                if (param in typeParams) "1" else "0",
            )
        }

        ALL_LANGUAGE_PARAMS.forEach { param ->
            builder.addQueryParameter(
                param,
                if (param in languageParams) "1" else "0",
            )
        }

        builder.addQueryParameter("page", page.toString())

        return builder.build()
    }

    private val languageSlug: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "es" -> "spanish"
            "fr" -> "french"
            "ko" -> "korean"
            "de" -> "german"
            "ru" -> "russian"
            else -> error("Unsupported language: $lang")
        }

    private val sourceLanguageParam: String
        get() = when (lang) {
            "en" -> "en"
            "ja" -> "jp"
            "es" -> "es"
            "fr" -> "fr"
            "ko" -> "kr"
            "de" -> "de"
            "ru" -> "ru"
            else -> error("Unsupported language: $lang")
        }

    private val chapterName: String
        get() = when (lang) {
            "all" -> "Chapter"
            "en" -> "Chapter"
            "ja" -> "章"
            "es" -> "Capítulo"
            "fr" -> "Chapitre"
            "ko" -> "챕터"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            else -> "Chapter"
        }

    private fun localizedPages(count: Int): String = when (lang) {
        "all" -> "$count pages"
        "en" -> "$count pages"
        "ja" -> "$count ページ"
        "es" -> "$count páginas"
        "fr" -> "$count pages"
        "ko" -> "$count 페이지"
        "de" -> "$count Seiten"
        "ru" -> "$count страниц"
        else -> "$count pages"
    }

    private fun String.capitalizeName(): String = trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }

    private fun Element.imageUrl(): String? = listOf(
        "data-src",
        "data-lazy-src",
        "src",
    ).firstNotNullOfOrNull { attribute ->
        attr(attribute)
            .trim()
            .takeIf(String::isNotBlank)
    }

    private companion object {
        val SORT_PARAMS = listOf(
            "lt",
            "dl",
            "pp",
            "tr",
        )

        val ALL_TYPE_PARAMS = listOf(
            "mg",
            "dj",
            "ws",
            "is",
            "ac",
            "gc",
        )

        val ALL_LANGUAGE_PARAMS = listOf(
            "en",
            "jp",
            "es",
            "fr",
            "kr",
            "de",
            "ru",
        )

        val PAGE_COUNT_REGEX =
            Regex("""(\d+)\s+Pages?\b""", RegexOption.IGNORE_CASE)
    }
}
