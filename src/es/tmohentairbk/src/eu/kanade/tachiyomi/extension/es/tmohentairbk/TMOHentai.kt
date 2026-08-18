package eu.kanade.tachiyomi.extension.es.tmohentairbk

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class TMOHentai :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/biblioteca?order_item=creation&order_dir=desc"
        } else {
            "$baseUrl/biblioteca?order_item=creation&order_dir=desc&page=$page"
        }

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/biblioteca?order_item=creation&order_dir=desc"
        } else {
            "$baseUrl/biblioteca?order_item=creation&order_dir=desc&page=$page"
        }

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a.manga-card").mapNotNull { element ->
            val image = element.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt").trim()
            val href = element.attr("abs:href")

            if (title.isBlank() || href.isBlank()) {
                return@mapNotNull null
            }

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = image.attr("abs:src")
            }
        }

        val currentPage = response.request.url.queryParameter("page")
            ?.toIntOrNull()
            ?: 1

        val nextPage = currentPage + 1

        val hasNextPage = document
            .select(".pagination a[href]")
            .any { element ->
                element.attr("href").contains("page=$nextPage")
            }

        return MangasPage(mangas, hasNextPage)
    }

    // construye la url de donde se sacara lo que se busca
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val url = "$baseUrl/biblioteca?order_item=likes_count&order_dir=desc"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("title", query)
            .apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        return GET(url, headers)
    }

    // encuentra la informacion de la pagina de busqueda que se mostrara
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a.manga-card").mapNotNull { element ->
            val image = element.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt").trim()
            val href = element.attr("abs:href")

            if (title.isBlank() || href.isBlank()) {
                return@mapNotNull null
            }

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = image.attr("abs:src")
            }
        }

        val currentPage = response.request.url.queryParameter("page")
            ?.toIntOrNull()
            ?: 1

        val nextPage = currentPage + 1

        val hasNextPage = document
            .select(".pagination a[href]")
            .any { element ->
                element.attr("href").contains("page=$nextPage")
            }

        return MangasPage(mangas, hasNextPage)
    }

    // obtiene los detalles, titulo, tags, autores, etc
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("#md-title")
                ?.text()
                .orEmpty()

            thumbnail_url = document.selectFirst("#md-cover")
                ?.attr("abs:src")

            description = document.selectFirst(
                ".md-info-row--synopsis .md-info-row__value",
            )?.text()

            author = document.selectFirst(".md-badge--author")
                ?.text()
                ?.trim()

            artist = author

            genre = document.select("#md-tags-list .label-info")
                .joinToString(", ") { it.text().trim() }

            status = when {
                document.selectFirst(".md-badge--completed") != null ->
                    SManga.COMPLETED

                document.selectFirst(".md-badge--ongoing") != null ->
                    SManga.ONGOING

                document.selectFirst(".md-badge--hiatus") != null ->
                    SManga.ON_HIATUS

                document.selectFirst(".md-badge--cancelled") != null ->
                    SManga.CANCELLED

                else ->
                    SManga.UNKNOWN
            }

            initialized = true
        }
    }

    // crea el item del capitulo
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val readerUrl = document.selectFirst(".md-preview-read-btn")
            ?.attr("abs:href")
            ?: return emptyList()

        println("RBK_READER_URL: $readerUrl")

        return listOf(
            SChapter.create().apply {
                name = "Leer"
                setUrlWithoutDomain(readerUrl)

                println("RBK_CHAPTER_URL: $url")
            },
        )
    }

    // obtiene imagenes para vista del capitulo
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("#reader-wrap .reader-img-wrap img")
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.attr("src")
                    .ifBlank { element.attr("data-src") }

                if (imageUrl.isBlank()) {
                    return@mapIndexedNotNull null
                }

                Page(
                    index = index,
                    imageUrl = imageUrl,
                )
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
        val filterList = mutableListOf(
            Filter.Header("NOTA: Se ignoran si se usa el buscador"),
            Filter.Separator(),
            SortBy(),
            StatusFilter(),
            TypeFilter(),
            GenreFilter(),
        )

        if (!hideNSFWContent()) {
            filterList.add(AdultContentFilter())
        }
        return FilterList(filterList)
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val contentPref = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = CONTENT_PREF
            title = CONTENT_PREF_TITLE
            summary = CONTENT_PREF_SUMMARY
            setDefaultValue(CONTENT_PREF_DEFAULT_VALUE)
        }

        screen.addPreference(contentPref)
    }

    private fun hideNSFWContent(): Boolean = preferences.getBoolean(CONTENT_PREF, CONTENT_PREF_DEFAULT_VALUE)

    companion object {
        private const val CONTENT_PREF = "showNSFWContent"
        private const val CONTENT_PREF_TITLE = "Ocultar contenido +18"
        private const val CONTENT_PREF_SUMMARY = "Ocultar el contenido erótico en mangas populares y filtros, no funciona en los mangas recientes ni búsquedas textuales."
        private const val CONTENT_PREF_DEFAULT_VALUE = false
    }
}
