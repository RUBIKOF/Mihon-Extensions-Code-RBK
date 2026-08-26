package eu.kanade.tachiyomi.extension.all.hentaienvyrbk

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HentaiEnvy : KeiSource() {

    override val supportsLatest: Boolean
        get() = lang != "all"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (lang == "all") {
            baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
        } else {
            "$baseUrl/language/$languageSlug/popular/".toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
        }
        return client.get(url).asJsoup().toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (lang == "all") return MangasPage(emptyList(), false)

        val url = "$baseUrl/language/$languageSlug/".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return client.get(url).asJsoup().toMangasPage()
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)

        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("s_key", query.trim())
            .apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()

        return client.get(url).asJsoup().toMangasPage()
    }

    private fun Document.toMangasPage(): MangasPage {
        val mangas = select(".overview_thumbs .thumb, .box_thumbs .thumb")
            .mapNotNull(::mangaFromElement)

        val hasNextPage = select("ul.pagination a[href], a.page-link[href]")
            .any { it.text().trim().equals("Next", ignoreCase = true) }

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst(
            "a[href*=/gallery/][title], a[href*=/gallery/]",
        ) ?: return null

        val title = link.attr("title").trim().ifBlank {
            link.selectFirst(".title")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = link.selectFirst("img")?.imageUrl()
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (
            url.host != baseUrl.toHttpUrl().host ||
            !url.encodedPath.startsWith("/gallery/")
        ) {
            return null
        }

        return client.get(url).asJsoup().parseMangaDetails().apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = if (fetchDetails) {
            document.parseMangaDetails().apply { url = manga.url }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(document.parseChapter(manga.url))
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun Document.parseMangaDetails(): SManga = SManga.create().apply {
        title = selectFirst("head > title")
            ?.text()
            ?.replace(
                Regex("""\s*-\s*HentaiEnvy\s*$""", RegexOption.IGNORE_CASE),
                "",
            )
            ?.trim()
            .orEmpty()
            .ifBlank { selectFirst("h1")?.text()?.trim().orEmpty() }

        thumbnail_url = selectFirst(
            "img[data-src*=cover], img[src*=cover]",
        )?.imageUrl()

        val authors = select("a[href^=/artist/]")
            .map { capitalizeName(it.ownText()) }
            .filter(String::isNotBlank)
            .distinct()

        val groups = select("a[href^=/group/]")
            .map { capitalizeName(it.ownText()) }
            .filter(String::isNotBlank)
            .distinct()

        when {
            authors.isNotEmpty() -> {
                author = authors.joinToString(", ")
                artist = groups.joinToString(", ").takeIf(String::isNotBlank)
            }
            groups.isNotEmpty() -> {
                author = groups.joinToString(", ")
                artist = null
            }
            else -> {
                author = null
                artist = null
            }
        }

        genre = select("a.gp_tag[href^=/tag/]")
            .map { it.ownText().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        val language = selectFirst(
            "a[aria-label=g_language][href^=/language/]",
        )
            ?.attr("href")
            ?.substringAfter("/language/", "")
            ?.substringBefore("/")
            ?.takeIf(String::isNotBlank)
            ?.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }

        val category = selectFirst(
            ".gallery_info .category a[href^=/category/], " +
                ".g_info .category a[href^=/category/], " +
                ".category a[href^=/category/]",
        )
            ?.ownText()
            ?.trim()
            ?.takeIf(String::isNotBlank)

        description = buildList {
            language?.let { add("$languageLabel: $it") }
            category?.let { add("$categoryLabel: $it") }
        }
            .joinToString("\n")
            .takeIf(String::isNotBlank)

        status = SManga.COMPLETED
        initialized = true
    }

    private fun Document.parseChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        url = mangaUrl
        name = chapterLabel
        chapter_number = 1f
        scanlator = pageCount().takeIf { it > 0 }?.let(::localizedPages)
        date_upload = 0L
    }

    private fun Document.pageCount(): Int = selectFirst("#load_pages")?.attr("value")?.toIntOrNull() ?: 0

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val response = client.get(chapterUrl)
        val body = response.use { it.body.string() }
        val document = Jsoup.parse(body, chapterUrl)

        val server = document.selectFirst("#load_server")?.attr("value")?.trim().orEmpty()
        val directory = document.selectFirst("#load_dir")?.attr("value")?.trim().orEmpty()
        val loadId = document.selectFirst("#load_id")?.attr("value")?.trim().orEmpty()
        val pageCount = document.selectFirst("#load_pages")?.attr("value")?.toIntOrNull()
            ?: return emptyList()

        if (
            server.isBlank() ||
            directory.isBlank() ||
            loadId.isBlank() ||
            pageCount <= 0
        ) {
            return emptyList()
        }

        val gThJson = G_TH_BLOCK_REGEX
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val formats = G_TH_ENTRY_REGEX.findAll(gThJson).associate { match ->
            match.groupValues[1].toInt() to match.groupValues[2].lowercase()
        }

        val mediaHost = "https://m$server.${baseUrl.toHttpUrl().host}"

        return (1..pageCount).mapNotNull { pageNumber ->
            val extension = when (formats[pageNumber]) {
                "j" -> "jpg"
                "w" -> "webp"
                else -> null
            } ?: return@mapNotNull null

            Page(
                index = pageNumber - 1,
                imageUrl = "$mediaHost/$directory/$loadId/$pageNumber.$extension",
            )
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private val languageSlug: String
        get() = when (lang) {
            "en" -> "english"
            "fr" -> "french"
            "es" -> "spanish"
            "ja" -> "japanese"
            "de" -> "german"
            "ru" -> "russian"
            "ko" -> "korean"
            else -> error("Unsupported language: $lang")
        }

    private val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "fr" -> "Chapitre"
            "ja" -> "章"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            "ko" -> "챕터"
            else -> "Chapter"
        }

    private val languageLabel: String
        get() = when (lang) {
            "es" -> "Idioma"
            "fr" -> "Langue"
            "ja" -> "言語"
            "de" -> "Sprache"
            "ru" -> "Язык"
            "ko" -> "언어"
            else -> "Language"
        }

    private val categoryLabel: String
        get() = when (lang) {
            "es" -> "Categoría"
            "fr" -> "Catégorie"
            "ja" -> "カテゴリー"
            "de" -> "Kategorie"
            "ru" -> "Категория"
            "ko" -> "카테고리"
            else -> "Category"
        }

    private fun localizedPages(count: Int): String = when (lang) {
        "es" -> "$count páginas"
        "fr" -> "$count pages"
        "ja" -> "$count ページ"
        "de" -> "$count Seiten"
        "ru" -> "$count страниц"
        "ko" -> "$count 페이지"
        else -> "$count pages"
    }

    private fun capitalizeName(name: String): String = name.trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase() else c.toString()
            }
        }

    private fun Element.imageUrl(): String? = listOf(
        "data-src",
        "data-lazy-src",
        "data-original",
        "src",
    ).firstNotNullOfOrNull { attr ->
        absUrl(attr).trim().takeIf(String::isNotBlank)
    }

    private companion object {
        val G_TH_BLOCK_REGEX = Regex(
            """var\s+g_th\s*=\s*\$\.parseJSON\(\s*'(\{.*?\})'\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val G_TH_ENTRY_REGEX = Regex(
            """"(\d+)":"([A-Za-z]),(\d+),(\d+)"""",
        )
    }
}
