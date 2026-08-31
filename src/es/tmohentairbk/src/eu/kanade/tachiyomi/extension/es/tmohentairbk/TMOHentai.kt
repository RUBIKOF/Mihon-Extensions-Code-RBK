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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Source
abstract class TMOHentai :
    KeiSource(),
    ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(ReaderPreloadInterceptor())

    private data class CachedImage(
        val bytes: ByteArray,
        val contentType: String?,
    )

    private class PreloadSlot {
        val ready = CountDownLatch(1)

        @Volatile
        var image: CachedImage? = null
    }

    private val preloadExecutor = Executors.newFixedThreadPool(PRELOAD_WINDOW)
    private val preloadSlots = ConcurrentHashMap<String, PreloadSlot>()
    private val preloadCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val preloadLock = Any()

    @Volatile
    private var preloadUrls: List<String> = emptyList()

    @Volatile
    private var preloadOrder: List<Int> = emptyList()

    @Volatile
    private var nextPreloadOrderIndex = 0

    @Volatile
    private var preloadGeneration = 0L

    @Volatile
    private var lastReaderIndex: Int? = null

    @Volatile
    private var readerDirection = 1

    private inner class ReaderPreloadInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()

            if (original.header(PRELOAD_HEADER) != null) {
                return chain.proceed(
                    original.newBuilder()
                        .removeHeader(PRELOAD_HEADER)
                        .build(),
                )
            }

            val url = original.url.toString()
            val index = preloadUrls.indexOf(url)

            if (index >= 0) {
                updateReaderPosition(index)
            }

            val slot = preloadSlots[url]

            if (slot == null) {
                if (index >= 0) {
                }
                return chain.proceed(original)
            }

            val waitStart = System.currentTimeMillis()

            val ready = runCatching {
                slot.ready.await(PRELOAD_WAIT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)

            val cached = if (ready) slot.image else null
            val waitMs = System.currentTimeMillis() - waitStart

            if (cached == null) {
                preloadSlots.remove(url, slot)
                scheduleNextPreload()
                return chain.proceed(original)
            }

            preloadSlots.remove(url, slot)
            scheduleNextPreload()

            val mediaType = cached.contentType
                ?.takeIf { it.isNotBlank() }
                ?.toMediaTypeOrNull()

            return Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(cached.bytes.toResponseBody(mediaType))
                .build()
        }
    }

    private fun preparePreload(urls: List<String>) {
        synchronized(preloadLock) {
            cancelActivePreloadsLocked()
            preloadSlots.clear()
            preloadUrls = urls
            lastReaderIndex = null
            readerDirection = 1
            setPreloadOrderLocked(anchor = 0, direction = 1)

            repeat(minOf(PRELOAD_WINDOW, preloadOrder.size)) {
                scheduleNextPreloadLocked()
            }
        }
    }

    private fun updateReaderPosition(index: Int) {
        synchronized(preloadLock) {
            val previous = lastReaderIndex

            if (previous == null) {
                lastReaderIndex = index
                return
            }

            val delta = index - previous
            if (delta == 0) return

            val newDirection = if (delta > 0) 1 else -1
            val directionChanged = newDirection != readerDirection
            val jumped = kotlin.math.abs(delta) > 1
            val targetAlreadyTracked = preloadSlots.containsKey(preloadUrls[index])

            lastReaderIndex = index

            if (directionChanged || jumped || !targetAlreadyTracked) {
                readerDirection = newDirection
                cancelActivePreloadsLocked()
                preloadSlots.clear()
                setPreloadOrderLocked(anchor = index, direction = newDirection)

                repeat(minOf(PRELOAD_WINDOW, preloadOrder.size)) {
                    scheduleNextPreloadLocked()
                }
            }
        }
    }

    private fun setPreloadOrderLocked(anchor: Int, direction: Int) {
        preloadGeneration++

        preloadOrder = if (direction > 0) {
            (anchor until preloadUrls.size).toList()
        } else {
            (anchor downTo 0).toList()
        }

        nextPreloadOrderIndex = 0
    }

    private fun cancelActivePreloadsLocked() {
        preloadCalls.values.forEach { it.cancel() }
        preloadCalls.clear()
    }

    private fun scheduleNextPreload() {
        synchronized(preloadLock) {
            scheduleNextPreloadLocked()
        }
    }

    private fun scheduleNextPreloadLocked() {
        while (nextPreloadOrderIndex < preloadOrder.size) {
            val index = preloadOrder[nextPreloadOrderIndex++]
            val url = preloadUrls[index]
            val slot = PreloadSlot()
            val generation = preloadGeneration

            if (preloadSlots.putIfAbsent(url, slot) != null) {
                continue
            }

            preloadExecutor.execute {
                val startedAt = System.currentTimeMillis()

                if (generation != preloadGeneration) {
                    preloadSlots.remove(url, slot)
                    slot.ready.countDown()
                    return@execute
                }

                val parsedUrl = runCatching { url.toHttpUrl() }.getOrNull()

                if (parsedUrl == null) {
                    preloadSlots.remove(url, slot)
                    slot.ready.countDown()
                    scheduleNextPreload()
                    return@execute
                }

                var call: okhttp3.Call? = null

                try {
                    val request = Request.Builder()
                        .url(parsedUrl)
                        .headers(headers)
                        .header(PRELOAD_HEADER, "1")
                        .get()
                        .build()

                    call = client.newCall(request)
                    preloadCalls[url] = call

                    call.execute().use { response ->
                        if (
                            generation == preloadGeneration &&
                            response.isSuccessful
                        ) {
                            val bytes = response.body.bytes()
                            slot.image = CachedImage(
                                bytes = bytes,
                                contentType = response.header("Content-Type"),
                            )
                        } else {
                        }
                    }
                } catch (e: Exception) {
                    // Fall back to Mihon's normal image request.
                } finally {
                    call?.let { preloadCalls.remove(url, it) }
                    slot.ready.countDown()

                    if (slot.image == null) {
                        preloadSlots.remove(url, slot)
                        scheduleNextPreload()
                    }
                }
            }

            return
        }
    }

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

        val pageCount = document.selectFirst("#md-preview-cap-hint")
            ?.text()
            ?.let { hint ->
                Regex("""(\d+)\D*$""")
                    .find(hint)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }

        val updatedChapters = if (readerUrl.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(
                SChapter.create().apply {
                    name = "Leer"
                    setUrlWithoutDomain(readerUrl)
                    scanlator = pageCount?.let { count ->
                        if (count == 1) "1 página" else "$count páginas"
                    }
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

        val pages = document.select("#reader-wrap .reader-img-wrap img")
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.attr("abs:src")
                    .ifBlank { element.attr("abs:data-src") }
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data-src") }

                if (imageUrl.isEmpty()) {
                    return@mapIndexedNotNull null
                }

                Page(
                    index = index,
                    imageUrl = imageUrl,
                )
            }

        preparePreload(pages.mapNotNull { it.imageUrl })

        return pages
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
        private const val PRELOAD_WINDOW = 10
        private const val PRELOAD_WAIT_SECONDS = 15L
        private const val PRELOAD_HEADER = "X-RBK-Preload"

        private const val CONTENT_PREF = "showNSFWContent"
        private const val CONTENT_PREF_TITLE = "Ocultar contenido +18"
        private const val CONTENT_PREF_SUMMARY =
            "Ocultar el contenido erótico en mangas populares y filtros, no funciona en los mangas recientes ni búsquedas textuales."
        private const val CONTENT_PREF_DEFAULT_VALUE = false
    }
}
