package eu.kanade.tachiyomi.extension.all.simplyhentairbk

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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

@Source
abstract class SimplyHentai : KeiSource() {

    private val apiUrl = "https://api-v3.simply-hentai.com/v3"

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(
        path = "/language/$languageSlug/page-$page",
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(
        path = "/language/$languageSlug/sort-newest/page-$page",
    )

    private suspend fun getListing(path: String): MangasPage {
        val document = client
            .get("$baseUrl$path")
            .asJsoup()

        val mangas = document
            .select("article.object-container.manga-container")
            .mapNotNull(::mangaFromElement)

        val hasNextPage = document
            .select("a.btn.btn-default.icon-right[href]")
            .any { it.text().trim().equals("Next", ignoreCase = true) }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.with-fade.content-link[href]")
            ?: return null

        val title = element
            .selectFirst("h3.title")
            ?.text()
            ?.trim()
            .orEmpty()

        if (title.isBlank()) return null

        val image = element.selectFirst("img")

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(link.attr("abs:href"))
            thumbnail_url = image?.imageUrl()
        }
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

        val url = "$apiUrl/search/complex"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("filter[language][0]", languageName)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("query", query.trim())
            .build()

        val data = client
            .get(url)
            .parseAs<SearchResponseDto>()

        return MangasPage(
            mangas = data.data.map { it.toSManga() },
            hasNextPage = data.pagination.current < data.pagination.pages,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val sourceHost = baseUrl.toHttpUrl().host

        if (url.host != sourceHost || url.pathSegments.size != 2) {
            return null
        }

        val nextData = client
            .get(url)
            .extractNextJs<DetailNextDto>()
            ?: return null

        val detail = nextData.props.pageProps.manga

        if (detail.language.slug != languageSlug) {
            return null
        }

        return detail.toSManga()
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

        val nextData = client
            .get(getMangaUrl(manga))
            .extractNextJs<DetailNextDto>()
            ?: return SMangaUpdate(
                manga = manga,
                chapters = chapters,
            )

        val detail = nextData.props.pageProps.manga

        val updatedManga = if (fetchDetails) {
            detail.toSManga()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(detail.toSChapter())
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val nextData = client
            .get(getChapterUrl(chapter))
            .extractNextJs<AllPagesNextDto>()
            ?: return emptyList()

        val data = nextData.props.pageProps.data

        return data.pages
            .sortedBy { it.pageNum }
            .mapIndexed { index, page ->
                Page(
                    index = index,
                    imageUrl = page.sizes.full,
                )
            }
    }

    private fun MangaDetailDto.toSManga(): SManga = SManga.create().apply {
        url = "/${series.slug}/$slug"
        title = this@toSManga.title

        val normalizedArtists = artists
            .map { it.title.capitalizeName() }
            .filter(String::isNotBlank)
            .distinct()

        author = normalizedArtists
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        artist = author

        genre = tags
            .map { it.title.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        description = this@toSManga.description
            ?.trim()
            ?.takeIf(String::isNotBlank)

        thumbnail_url = preview.sizes.giantThumb
            .ifBlank { preview.sizes.thumb }

        status = SManga.COMPLETED
        initialized = true
    }

    private fun MangaDetailDto.toSChapter(): SChapter = SChapter.create().apply {
        url = "/${series.slug}/$slug/all-pages"
        name = chapterName
        chapter_number = 1f
        scanlator = localizedPages(imageCount)
        date_upload = DATE_FORMAT.tryParseDate(createdAt)
    }

    private fun SearchObjectDto.toSManga(): SManga = SManga.create().apply {
        url = "/${objectData.series.slug}/${objectData.slug}"
        title = objectData.title
        thumbnail_url = objectData.preview.sizes.giantThumb
            .ifBlank { objectData.preview.sizes.thumb }
    }

    private val languageSlug: String
        get() = when (lang) {
            "en" -> "english"
            "fr" -> "french"
            "it" -> "italian"
            "es" -> "spanish"
            "pl" -> "polish"
            "ja" -> "japanese"
            "de" -> "german"
            "ru" -> "russian"
            "ko" -> "korean"
            "zh" -> "chinese"
            else -> error("Unsupported language: $lang")
        }

    private val languageName: String
        get() = when (lang) {
            "en" -> "English"
            "fr" -> "French"
            "it" -> "Italian"
            "es" -> "Spanish"
            "pl" -> "Polish"
            "ja" -> "Japanese"
            "de" -> "German"
            "ru" -> "Russian"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            else -> error("Unsupported language: $lang")
        }

    private val chapterName: String
        get() = when (lang) {
            "en" -> "Chapter"
            "fr" -> "Chapitre"
            "it" -> "Capitolo"
            "es" -> "Capítulo"
            "pl" -> "Rozdział"
            "ja" -> "章"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            "ko" -> "챕터"
            "zh" -> "章节"
            else -> "Chapter"
        }

    private fun localizedPages(count: Int): String = when (lang) {
        "en" -> "$count pages"
        "fr" -> "$count pages"
        "it" -> "$count pagine"
        "es" -> "$count páginas"
        "pl" -> "$count stron"
        "ja" -> "$count ページ"
        "de" -> "$count Seiten"
        "ru" -> "$count страниц"
        "ko" -> "$count 페이지"
        "zh" -> "$count 页"
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
        val raw = attr(attribute)
            .trim()
            .takeIf(String::isNotBlank)
            ?: return@firstNotNullOfOrNull null

        when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> absUrl(attribute).ifBlank { raw }
        }
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}

@Serializable
class SearchResponseDto(
    val pagination: SearchPaginationDto,
    val data: List<SearchObjectDto>,
)

@Serializable
class SearchPaginationDto(
    val current: Int,
    val pages: Int,
)

@Serializable
class SearchObjectDto(
    @SerialName("object")
    val objectData: SearchMangaDto,
)

@Serializable
class SearchMangaDto(
    val slug: String,
    val title: String,
    val preview: ImageDto,
    val series: SeriesDto,
)

@Serializable
class DetailNextDto(
    val props: DetailPropsDto,
)

@Serializable
class DetailPropsDto(
    val pageProps: DetailPagePropsDto,
)

@Serializable
class DetailPagePropsDto(
    val manga: MangaDetailDto,
)

@Serializable
class MangaDetailDto(
    val artists: List<NamedDto>,
    @SerialName("created_at")
    val createdAt: String,
    val description: String?,
    @SerialName("image_count")
    val imageCount: Int,
    val language: LanguageDto,
    val preview: ImageDto,
    val series: SeriesDto,
    val slug: String,
    val tags: List<NamedDto>,
    val title: String,
)

@Serializable
class NamedDto(
    val title: String,
)

@Serializable
class LanguageDto(
    val slug: String,
)

@Serializable
class SeriesDto(
    val slug: String,
)

@Serializable
class ImageDto(
    val sizes: ImageSizesDto,
)

@Serializable
class ImageSizesDto(
    val full: String,
    val thumb: String,
    @SerialName("giant_thumb")
    val giantThumb: String,
)

@Serializable
class AllPagesNextDto(
    val props: AllPagesPropsDto,
)

@Serializable
class AllPagesPropsDto(
    val pageProps: AllPagesPagePropsDto,
)

@Serializable
class AllPagesPagePropsDto(
    val data: AllPagesDataDto,
)

@Serializable
class AllPagesDataDto(
    val pages: List<ReaderPageDto>,
)

@Serializable
class ReaderPageDto(
    @SerialName("page_num")
    val pageNum: Int,
    val sizes: ImageSizesDto,
)
