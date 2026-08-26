package eu.kanade.tachiyomi.extension.all.sexkomix2rbk

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
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

@Source
abstract class SexKomix2 : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(
        page = page,
        sort = "prosmotr",
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(
        page = page,
        sort = "date",
    )

    private suspend fun getListing(
        page: Int,
        sort: String,
    ): MangasPage {
        val url = "$baseUrl/home/"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lang", lang)
            .addQueryParameter("sort", sort)
            .apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = client.get(url).asJsoup()

        return parseMangasPage(document)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) {
            return MangasPage(
                mangas = emptyList(),
                hasNextPage = false,
            )
        }

        val url = "$baseUrl/search/"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lang", lang)
            .addQueryParameter("q", query.trim())
            .addQueryParameter("sort", "date")
            .apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = client.get(url).asJsoup()

        return parseMangasPage(document)
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val directory = mainDirectory(document)

        val mangas = directory
            ?.children()
            ?.asSequence()
            ?.filter { it.hasClass("comix") }
            ?.mapNotNull(::mangaFromElement)
            ?.toList()
            .orEmpty()

        val hasNextPage = document
            .selectFirst("#site_pages a.pstr-next[href]") != null

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun mainDirectory(document: Document): Element? = document
        .select("ul#comix_directory")
        .maxByOrNull { directory ->
            directory
                .children()
                .count { it.hasClass("comix") }
        }

    private fun mangaFromElement(element: Element): SManga? {
        val titleElement = element.selectFirst("div.comix_title")
        val link = titleElement?.selectFirst("a[href]")
            ?: element.selectFirst("a[href]")

        val title = titleElement
            ?.text()
            ?.trim()
            .orEmpty()

        val href = link
            ?.attr("href")
            ?.trim()
            .orEmpty()

        if (title.isBlank() || href.isBlank()) {
            return null
        }

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = element
                .selectFirst("img.comix_img")
                ?.imageUrl()
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        if (!url.encodedPath.startsWith(mangaPathPrefix)) {
            return null
        }

        val document = client.get(url).asJsoup()

        if (document.selectFirst("h1 > a[href]") == null) {
            return null
        }

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
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            manga = parseMangaDetails(document).apply {
                setUrlWithoutDomain(document.location())
            },
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document
            .selectFirst("h1 > a[href]")
            ?.text()
            ?.trim()
            .orEmpty()

        thumbnail_url = document
            .selectFirst("#comix_cover_img")
            ?.imageUrl()

        description = document
            .selectFirst("""meta[property="og:description"]""")
            ?.attr("content")
            ?.trim()
            ?.takeIf(String::isNotBlank)

        val studio = document
            .selectFirst("""div.link_button > a[href*="/studios/"]""")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)

        author = studio

        genre = document
            .select("""div.info_box > ul.tags_ul a[href*="/tag_pagex/"]""")
            .eachText()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        status = SManga.COMPLETED
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val date = document
            .select("div.info_box > ul.comix-info-set .cifra-info-set")
            .eachText()
            .firstOrNull(DATE_REGEX::matches)

        val pageCount = document
            .select("img.gallery-img")
            .size

        return listOf(
            SChapter.create().apply {
                name = chapterName
                scanlator = pageCountText(pageCount)
                setUrlWithoutDomain(document.location())
                date_upload = DATE_FORMAT.tryParseDate(date)
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document
            .select("img.gallery-img")
            .mapIndexedNotNull { index, image ->
                image.imageUrl()
                    ?.takeIf(String::isNotBlank)
                    ?.let { imageUrl ->
                        Page(
                            index = index,
                            imageUrl = imageUrl,
                        )
                    }
            }
    }

    private val mangaPathPrefix: String
        get() = when (lang) {
            "ru" -> "/comicsx/"
            else -> "/comicsx_$lang/"
        }

    private val chapterName: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "en" -> "Chapter"
            "pt" -> "Capítulo"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            else -> "Chapter"
        }

    private fun pageCountText(count: Int): String = when (lang) {
        "es" -> "$count ${if (count == 1) "página" else "páginas"}"
        "en" -> "$count ${if (count == 1) "page" else "pages"}"
        "pt" -> "$count ${if (count == 1) "página" else "páginas"}"
        "de" -> "$count ${if (count == 1) "Seite" else "Seiten"}"
        "ru" -> "$count ${russianPages(count)}"
        else -> "$count pages"
    }

    private fun russianPages(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10

        return when {
            mod100 in 11..14 -> "страниц"
            mod10 == 1 -> "страница"
            mod10 in 2..4 -> "страницы"
            else -> "страниц"
        }
    }

    private fun Element.imageUrl(): String? = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "src",
    ).firstNotNullOfOrNull { attribute ->
        val raw = attr(attribute)
            .trim()
            .takeIf(String::isNotBlank)
            ?: return@firstNotNullOfOrNull null

        when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> absUrl(attribute).ifBlank { raw }
        }
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
