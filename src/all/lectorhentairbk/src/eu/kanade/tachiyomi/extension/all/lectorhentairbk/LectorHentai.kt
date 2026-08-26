package eu.kanade.tachiyomi.extension.all.lectorhentairbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class LectorHentai : KeiSource() {

    private val pageSize = 25


    override suspend fun getPopularManga(page: Int): MangasPage = getListing(
        page = page,
        popular = true,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(
        page = page,
        popular = false,
    )

    private suspend fun getListing(
        page: Int,
        popular: Boolean,
    ): MangasPage {
        val url = "$baseUrl/tipo/all"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lenguaje", siteLanguage)
            .apply {
                if (popular) {
                    addQueryParameter("order", "popular")
                }

                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = Jsoup.parse(
            client.get(url).body.string(),
            url.toString(),
        )

        val mangas = document
            .select("div.listupd div.bs")
            .mapNotNull(::parseListingCard)

        return MangasPage(
            mangas = mangas,
            hasNextPage = mangas.size == pageSize,
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = "$baseUrl/tipo/all"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("s", query.trim())
            .addQueryParameter("lenguaje", siteLanguage)
            .apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = Jsoup.parse(
            client.get(url).body.string(),
            url.toString(),
        )

        val mangas = document
            .select("div.listupd div.bs")
            .mapNotNull(::parseListingCard)

        return MangasPage(
            mangas = mangas,
            hasNextPage = mangas.size == pageSize,
        )
    }

    private fun parseListingCard(card: Element): SManga? {
        val link = card.selectFirst("a[href*=/manga/]")
            ?: return null

        val titleContainer = card.selectFirst(".bigor .tt")
            ?: card.selectFirst(".tt")
            ?: return null

        val cleanTitle = titleContainer
            .clone()
            .apply {
                select(".type, .epxdate, .uncensored").remove()
            }
            .text()
            .trim()

        if (cleanTitle.isBlank()) {
            return null
        }

        val image = card.selectFirst("img")

        val thumbnail = image
            ?.attr("data-src")
            ?.ifBlank { image.attr("data-lazy-src") }
            ?.ifBlank { image.attr("src") }
            .orEmpty()

        return SManga.create().apply {
            title = cleanTitle
            setUrlWithoutDomain(link.attr("href"))
            thumbnail_url = normalizeUrl(thumbnail)
        }
    }

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        if (
            url.host != baseUrl.toHttpUrl().host ||
            !url.encodedPath.startsWith("/manga/")
        ) {
            return null
        }

        val response = client.get(url)
        val document = Jsoup.parse(
            response.body.string(),
            url.toString(),
        )

        return parseMangaDetails(document)
            .takeIf { it.title.isNotBlank() }
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

        val mangaUrl = getMangaUrl(manga)
        val response = client.get(mangaUrl)
        val document = Jsoup.parse(
            response.body.string(),
            mangaUrl,
        )

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapter(document)
                ?.let(::listOf)
                ?: emptyList()
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    private fun parseMangaDetails(
        document: Document,
    ): SManga = SManga.create().apply {
        setUrlWithoutDomain(document.location())

        title = document
            .selectFirst(".infox > .wd-full > span")
            ?.text()
            ?.trim()
            .orEmpty()

        author = document
            .select("a[href*='searchBy=artista']")
            .eachText()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")

        val genres = document
            .select("a[href*='genre[]=']")
            .eachText()

        val tags = document
            .select("a[href*='tags[]=']")
            .eachText()

        genre = (genres + tags)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")

        thumbnail_url = normalizeUrl(
            document
                .selectFirst(".thumb img")
                ?.let { image ->
                    image.attr("data-src")
                        .ifBlank { image.attr("src") }
                }
                .orEmpty(),
        )

        val type = document
            .selectFirst(".tsinfo .imptdt a[href*='/tipo/']")
            ?.text()
            ?.trim()
            .orEmpty()

        val language = document
            .selectFirst(".tsinfo .imptdt i")
            ?.text()
            ?.trim()
            .orEmpty()

        val dates = document
            .select(".infox .fmed time")

        val published = dates
            .getOrNull(0)
            ?.text()
            ?.trim()
            .orEmpty()

        val updated = dates
            .getOrNull(1)
            ?.text()
            ?.trim()
            .orEmpty()

        description = buildList {
            if (type.isNotBlank()) {
                add("${labels.type}: $type")
            }

            if (language.isNotBlank()) {
                add("${labels.language}: $language")
            }

            if (published.isNotBlank()) {
                add("${labels.published}: $published")
            }

            if (updated.isNotBlank()) {
                add("${labels.updated}: $updated")
            }
        }.joinToString("\n")

        status = SManga.COMPLETED
        initialized = true
    }

    private fun parseChapter(
        document: Document,
    ): SChapter? {
        val readerUrl = document
            .selectFirst("a.leer")
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return null

        val publishedDate = document
            .select(".infox .fmed time")
            .getOrNull(0)
            ?.attr("datetime")
            .orEmpty()

        return SChapter.create().apply {
            name = chapterLabel
            chapter_number = 1f
            date_upload = parseDate(publishedDate)
            setUrlWithoutDomain(readerUrl)
        }
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val html = response.body.string()

        val imagesBlock = imagesRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        return imageUrlRegex
            .findAll(imagesBlock)
            .mapIndexed { index, match ->
                Page(
                    index = index,
                    imageUrl = normalizeUrl(match.groupValues[1]),
                )
            }
            .toList()
    }

    private fun normalizeUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L

        return runCatching {
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US,
            ).parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private val siteLanguage: String
        get() = when (lang) {
            "all" -> "all"
            "es" -> "Español"
            "en" -> "Ingles"
            "pt" -> "Português"
            "fr" -> "Français"
            "ru" -> "Pусский"
            else -> "all"
        }

    private val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "en" -> "Chapter"
            "pt" -> "Capítulo"
            "fr" -> "Chapitre"
            "ru" -> "Глава"
            else -> "Chapter"
        }

    private val labels: Labels
        get() = when (lang) {
            "es" -> Labels(
                type = "Tipo",
                language = "Idioma",
                published = "Publicado",
                updated = "Actualizado",
            )

            "pt" -> Labels(
                type = "Tipo",
                language = "Idioma",
                published = "Publicado",
                updated = "Atualizado",
            )

            "fr" -> Labels(
                type = "Type",
                language = "Langue",
                published = "Publié",
                updated = "Mis à jour",
            )

            "ru" -> Labels(
                type = "Тип",
                language = "Язык",
                published = "Опубликовано",
                updated = "Обновлено",
            )

            else -> Labels(
                type = "Type",
                language = "Language",
                published = "Published",
                updated = "Updated",
            )
        }

    private data class Labels(
        val type: String,
        val language: String,
        val published: String,
        val updated: String,
    )

    private companion object {
        val imagesRegex = Regex(
            """"images"\s*:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val imageUrlRegex = Regex(
            """["']((?:https?:)?//[^"']+)["']""",
        )
    }
}
