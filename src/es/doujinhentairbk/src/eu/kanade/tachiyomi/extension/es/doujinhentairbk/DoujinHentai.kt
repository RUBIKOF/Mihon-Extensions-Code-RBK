package eu.kanade.tachiyomi.extension.es.doujinhentairbk

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
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class DoujinHentai : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = getCatalog(
        path = "/lista-manga-hentai",
        page = page,
        orderBy = "views",
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getCatalog(
        path = "/lista-manga-hentai",
        page = page,
        orderBy = "last",
    )

    private suspend fun getCatalog(
        path: String,
        page: Int,
        orderBy: String? = null,
    ): MangasPage {
        val url = "$baseUrl$path"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                orderBy?.let {
                    addQueryParameter("orderby", it)
                }
            }
            .build()

        val response = client.get(url)
        val document = response.asJsoup()

        return parseCatalog(
            document = document,
            page = page,
        )
    }

    private fun parseCatalog(
        document: Document,
        page: Int,
    ): MangasPage {
        val mangas = document
            .select("div.group")
            .mapNotNull(::catalogMangaFromElement)
            .distinctBy { it.url }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage(document, page),
        )
    }

    private fun catalogMangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a[href*=/manga-hentai/]") ?: return null
        val title = element.selectFirst("h3")?.text()?.trim().orEmpty()

        if (title.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(link.attr("abs:href"))
            thumbnail_url = element
                .selectFirst("img")
                ?.imageUrl()
        }
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isNotBlank()) {
            return getTextSearch(
                page = page,
                query = query.trim(),
            )
        }

        val config = Filters.parse(filters)

        if (config.genreMode) {
            val slug = config.genreSlug
                ?: return MangasPage(
                    mangas = emptyList(),
                    hasNextPage = false,
                )

            return getCatalog(
                path = "/lista-manga-hentai/category/$slug",
                page = page,
            )
        }

        return getCatalog(
            path = config.typePath,
            page = page,
            orderBy = config.orderBy,
        )
    }

    private suspend fun getTextSearch(
        page: Int,
        query: String,
    ): MangasPage {
        val url = "$baseUrl/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url)
        val document = response.asJsoup()

        val mangas = document
            .select("a[href*=/manga-hentai/]")
            .mapNotNull(::searchMangaFromElement)
            .distinctBy { it.url }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage(document, page),
        )
    }

    private fun searchMangaFromElement(element: Element): SManga? {
        val image = element.selectFirst("img[src*=/cover/]") ?: return null
        val href = element.attr("abs:href")

        if (href.isBlank()) return null

        val title = image
            .attr("alt")
            .trim()
            .removePrefix("Leer ")
            .replace(
                Regex(
                    """\s+-\s+(?:Manga|Doujin|Comic).*?$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()

        if (title.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = image.imageUrl()
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val response = client.get(url)
        val document = response.asJsoup()

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

        val response = client.get(getMangaUrl(manga))
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document).apply {
                setUrlWithoutDomain(getMangaUrl(manga))
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapters(document)
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document
            .selectFirst("h1")
            ?.text()
            .orEmpty()

        thumbnail_url = document
            .selectFirst("img[src*=/cover/]")
            ?.imageUrl()

        val infoText = document
            .selectFirst("div.sticky.top-4")
            ?.text()
            .orEmpty()

        status = when {
            infoText.contains("Complete", ignoreCase = true) -> SManga.COMPLETED
            infoText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        val authors = document
            .select("h3")
            .firstOrNull {
                it.text().trim().equals("Autor(es)", ignoreCase = true)
            }
            ?.parent()
            ?.nextElementSibling()
            ?.select("a[href*='/author/']")
            ?.eachText()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { capitalizeName(it) }
            ?.distinct()
            .orEmpty()

        val artists = document
            .select("h3")
            .firstOrNull {
                it.text().trim().equals("Artista(s)", ignoreCase = true)
            }
            ?.parent()
            ?.nextElementSibling()
            ?.select("a[href*='/artist/']")
            ?.eachText()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { capitalizeName(it) }
            ?.distinct()
            .orEmpty()

        val authorText = authors
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        val artistText = artists
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        when {
            authorText != null && artistText != null -> {
                if (authorText.equals(artistText, ignoreCase = true)) {
                    author = authorText
                    artist = null
                } else {
                    author = authorText
                    artist = artistText
                }
            }

            authorText != null -> {
                author = authorText
                artist = null
            }

            artistText != null -> {
                author = artistText
                artist = null
            }

            else -> {
                author = null
                artist = null
            }
        }

        description = extractSynopsis(document)

        val categories = document
            .select("a[href*=/category/]")
            .eachText()
            .map(String::trim)
            .filter(String::isNotBlank)

        val tags = document
            .select("a[href*=/tag/]")
            .eachText()
            .map { it.trim().removePrefix("#").trim() }
            .filter(String::isNotBlank)

        genre = (tags)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotBlank)
    }

    private fun capitalizeName(value: String): String = value
        .trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }

    private fun extractSynopsis(document: Document): String? {
        val heading = document
            .select("h2")
            .firstOrNull {
                it.text().trim().equals("Sinopsis", ignoreCase = true)
            }
            ?: return null

        val container = heading.parent() ?: return null

        return container
            .children()
            .filter { it != heading }
            .joinToString("\n") { it.text().trim() }
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val infoText = document
            .selectFirst("div.sticky.top-4")
            ?.text()
            .orEmpty()

        val contentType = when {
            infoText.contains("Doujin", ignoreCase = true) -> "Doujin"
            infoText.contains("Manga", ignoreCase = true) -> "Manga"
            infoText.contains("Comic", ignoreCase = true) -> "Comic"
            else -> null
        }

        return document
            .select("div.flex.items-center.gap-4.p-3.mb-2.border.rounded-lg")
            .mapNotNull { chapterFromElement(it, contentType) }
    }

    private fun chapterFromElement(
        element: Element,
        contentType: String?,
    ): SChapter? {
        val link = element
            .selectFirst("a[href*=/manga-hentai/]")
            ?: return null

        val chapterNumber = element
            .selectFirst("a.flex.items-center.justify-center.w-10.h-10")
            ?.text()
            ?.trim()
            ?.replace(',', '.')
            ?.toFloatOrNull()

        val titleBlock = element.selectFirst("div.flex-1")

        val chapterTitle = titleBlock
            ?.children()
            ?.getOrNull(1)
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return SChapter.create().apply {
            name = chapterTitle
                ?: chapterNumber?.let {
                    "Capítulo ${it.toString().removeSuffix(".0")}"
                }
                ?: link.text().trim()

            setUrlWithoutDomain(link.attr("abs:href"))

            chapter_number = chapterNumber ?: -1f

            scanlator = contentType

            date_upload = parseDate(
                element
                    .selectFirst("div.text-sm.text-right")
                    ?.children()
                    ?.lastOrNull()
                    ?.text()
                    ?.trim(),
            )
        }
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L

        return listOf(
            "dd MMM. yyyy",
            "dd MMM yyyy",
        ).firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH)
                    .apply { isLenient = false }
                    .parse(value)
                    ?.time
            }.getOrNull()
        } ?: 0L
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val html = response.body.string()

        val normalizedHtml = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")

        return pageRegex
            .findAll(normalizedHtml)
            .map { it.value }
            .filter {
                it.contains("/uploads/manga/", ignoreCase = true) &&
                    it.contains("/chapters/", ignoreCase = true)
            }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(
                    index = index,
                    imageUrl = imageUrl,
                )
            }
            .toList()
    }

    private fun hasNextPage(
        document: Document,
        page: Int,
    ): Boolean {
        if (document.selectFirst("a[rel=next]") != null) return true

        return document
            .select("a[href*=page=]")
            .any { link ->
                link.attr("abs:href")
                    .toHttpUrlOrNull()
                    ?.queryParameter("page")
                    ?.toIntOrNull() == page + 1
            }
    }

    private fun Element.imageUrl(): String? = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "src",
    ).firstNotNullOfOrNull { key ->
        attr(key)
            .trim()
            .takeIf(String::isNotBlank)
    }?.let { raw ->
        when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> raw
        }
    }

    private companion object {
        val pageRegex = Regex(
            """https?://[^"'\\\s<>]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
