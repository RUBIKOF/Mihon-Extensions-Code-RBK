package eu.kanade.tachiyomi.extension.es.tmohentairbk

import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class TMOHentai :
    KeiSource(),
    ConfigurableSource {

    // carga la lista principal/popular.Reutiliza la misma lógica que recientes
    override suspend fun getPopularManga(page: Int): MangasPage = getListing(page)

    // carga los elementos más recientes
    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(page)

    // helper interno para no repetir código; arma la petición de listado, parsea tarjetas y calcula si hay siguiente página
    private suspend fun getListing(page: Int): MangasPage {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("order_item", "creation")
            .addQueryParameter("order_dir", "desc")
            .apply {
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = client.get(url).asJsoup()

        val mangas = document.select("a.manga-card").mapNotNull { element ->
            val image = element.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt")
            val href = element.attr("abs:href")

            if (title.isEmpty() || href.isEmpty()) {
                return@mapNotNull null
            }

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = image.attr("abs:src")
            }
        }

        val nextPage = page + 1
        val hasNextPage = document
            .select(".pagination a[href]")
            .any { element ->
                element.attr("href").contains("page=$nextPage")
            }

        return MangasPage(mangas, hasNextPage)
    }

    // hace la búsqueda. Usa el texto escrito, aplica el filtro de orden y devuelve los resultados.
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.firstInstanceOrNull<SortBy>()
        val content = filters.firstInstanceOrNull<ContentFilter>()

        val (orderItem, orderDir) = when (sort?.state) {
            1 -> "score" to "desc"
            2 -> "alphabetically" to "asc"
            else -> "creation" to "desc"
        }

        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("order_item", orderItem)
            .addQueryParameter("order_dir", orderDir)
            .apply {
                if (query.isNotEmpty()) {
                    addQueryParameter("title", query)
                }

                content?.toUriPart()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("content", it) }

                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }
            }
            .build()

        val document = client.get(url).asJsoup()

        val mangas = document.select("a.manga-card").mapNotNull { element ->
            val image = element.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt")
            val href = element.attr("abs:href")

            if (title.isEmpty() || href.isEmpty()) {
                return@mapNotNull null
            }

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = image.attr("abs:src")
            }
        }

        val nextPage = page + 1
        val hasNextPage = document
            .select(".pagination a[href]")
            .any { element ->
                element.attr("href").contains("page=$nextPage")
            }

        return MangasPage(mangas, hasNextPage)
    }

    // permite que una URL pegada/abierta directamente se convierta en una entrada reconocible por la fuente
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val document = client.get(url).asJsoup()
        val title = document.selectFirst("#md-title")?.text() ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
            this.title = title
            thumbnail_url = document.selectFirst("#md-cover")?.attr("abs:src")
        }
    }

    // carga la página de detalle y devuelve de una sola vez los datos del manga y la lista de capítulos/lector.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = SManga.create().apply {
            url = manga.url

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

            artist = author

            genre = document.select("#md-tags-list .label-info")
                .joinToString { it.text() }

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
        }

        val readerUrl = document.selectFirst(".md-preview-read-btn")
            ?.attr("abs:href")

        val updatedChapters = if (readerUrl.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(
                SChapter.create().apply {
                    name = "Leer"
                    setUrlWithoutDomain(readerUrl)
                },
            )
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    // abre el capítulo/lector y extrae todas las imágenes que Mihon mostrará como páginas.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document.select("#reader-wrap .reader-img-wrap img")
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.attr("src")
                    .ifBlank { element.attr("data-src") }

                if (imageUrl.isEmpty()) {
                    return@mapIndexedNotNull null
                }

                Page(
                    index = index,
                    imageUrl = imageUrl,
                )
            }
    }

    // define qué filtros ve el usuario en la búsqueda; ahora mismo el ordenamiento,contenido
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortBy(),
        ContentFilter(),
    )

    // crea las opciones de configuración propias de la extensión
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val contentPref = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = CONTENT_PREF
            title = CONTENT_PREF_TITLE
            summary = CONTENT_PREF_SUMMARY
            setDefaultValue(CONTENT_PREF_DEFAULT_VALUE)
        }

        screen.addPreference(contentPref)
    }

    companion object {
        private const val CONTENT_PREF = "showNSFWContent"
        private const val CONTENT_PREF_TITLE = "Ocultar contenido +18"
        private const val CONTENT_PREF_SUMMARY =
            "Ocultar el contenido erótico en mangas populares y filtros, no funciona en los mangas recientes ni búsquedas textuales."
        private const val CONTENT_PREF_DEFAULT_VALUE = false
    }
}
