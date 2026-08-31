package eu.kanade.tachiyomi.multisrc.vermangaspornorbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class VerMangasPorno : KeiSource() {

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

            val slot = preloadSlots[url] ?: return chain.proceed(original)

            val ready = runCatching {
                slot.ready.await(PRELOAD_WAIT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)

            val cached = if (ready) slot.image else null

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
                            slot.image = CachedImage(
                                bytes = response.body.bytes(),
                                contentType = response.header("Content-Type"),
                            )
                        }
                    }
                } catch (_: Exception) {
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

    protected abstract val listingPath: String

    override val supportsLatest = false

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(listingUrl(page)).asJsoup()
        return mangaPageParse(document)
    }

    // =============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(listingUrl(page)).asJsoup()
        return mangaPageParse(document)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = listingUrl(page)
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("s", query.trim())
            .build()

        val document = client.get(url).asJsoup()
        return mangaPageParse(document)
    }

    // ============================== Browse ===============================

    protected open fun mangaPageParse(document: Document): MangasPage {
        val firstGrid = document.select("div.pp-grid").firstOrNull()
            ?: return MangasPage(emptyList(), false)

        val mangas = firstGrid
            .select("article.pp-card:not(.pp-card-pinned)")
            .mapNotNull { element ->
                mangaFromElement(element)
            }

        val hasNextPage = document
            .select("a.next.page-numbers, .pagination a.next, .navigation a.next")
            .isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    protected open fun mangaFromElement(element: Element): SManga? {
        val link = element.select("a.pp-card-link").firstOrNull()
            ?: element.select("a[href]").firstOrNull()
            ?: return null

        val rawTitle = element
            .select(".pp-card-title")
            .firstOrNull()
            ?.text()
            ?.trim()
            .orEmpty()

        if (rawTitle.isBlank()) return null

        val parsed = parseTitleAndAuthor(
            rawTitle = rawTitle,
            siteArtist = null,
        )

        val img = element
            .select(".pp-card-img img")
            .firstOrNull()
            ?: element.select("img.pp-card-img").firstOrNull()
            ?: element.select("img").firstOrNull()

        return SManga.create().apply {
            setUrlWithoutDomain(link.attr("abs:href"))
            title = parsed.title
            author = parsed.author

            thumbnail_url = img
                ?.let(::imageUrl)
        }
    }

    // ============================== Details ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = if (fetchDetails) {
            mangaDetailsParse(document)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(chapterFromDocument(document, manga.url))
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    protected open fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val rawTitle = document
            .select("h1")
            .firstOrNull()
            ?.text()
            ?.trim()
            .orEmpty()

        val siteArtist = artistTags(document)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val parsed = parseTitleAndAuthor(
            rawTitle = rawTitle,
            siteArtist = siteArtist,
        )

        title = parsed.title
        author = parsed.author
        artist = null

        genre = document
            .select(".pp-tag")
            .map { it.text().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .filterNot { ARTIST_TAG_REGEX.matches(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val authorNames = parsed.author
            ?.split(",")
            ?.map { it.normalizeForComparison() }
            .orEmpty()

        val categories = categoryLinks(document)
            .filterNot { category ->
                category.normalizeForComparison() in authorNames
            }

        description = categories
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = "Categoría: ",
                separator = ", ",
            )

        val ogImage = document
            .select("meta[property=og:image]")
            .firstOrNull()
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val firstReaderImage = document
            .select(".pp-comic-content img")
            .firstOrNull()
            ?.let(::imageUrl)

        thumbnail_url = ogImage ?: firstReaderImage

        status = SManga.COMPLETED
    }

    private fun artistTags(document: Document): List<String> = document
        .select(".pp-tag")
        .mapNotNull { element ->
            val text = element
                .text()
                .removePrefix("#")
                .trim()

            ARTIST_TAG_REGEX
                .matchEntire(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.capitalizeName()
        }
        .distinctBy { it.lowercase(Locale.ROOT) }

    private fun categoryLinks(document: Document): List<String> = document
        .select(
            "a[href*='/category/'], " +
                "a[rel~=category], " +
                "a[rel='category tag'], " +
                "a[class*=category]",
        )
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }

    // ============================= Chapters ==============================

    protected open fun chapterFromDocument(
        document: Document,
        mangaUrl: String,
    ): SChapter = SChapter.create().apply {
        url = mangaUrl
        name = chapterLabel
        chapter_number = 1f

        val pages = pageCount(document)
        scanlator = if (pages > 0) {
            "$pages $pagesLabel"
        } else {
            null
        }

        date_upload = publicationDate(document)
    }

    private fun pageCount(document: Document): Int {
        val text = document
            .select(".pp-pagecount")
            .firstOrNull()
            ?.text()
            .orEmpty()

        return PAGE_COUNT_REGEX
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: document.select(".pp-comic-content img").size
    }

    private fun publicationDate(document: Document): Long {
        val datetime = document
            .select("time[datetime]")
            .firstOrNull()
            ?.attr("datetime")
            ?.takeIf { it.isNotBlank() }
            ?: return 0L

        return try {
            OffsetDateTime
                .parse(datetime)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val pages = pageListParse(document)

        preparePreload(pages.mapNotNull { it.imageUrl })

        return pages
    }

    protected open fun pageListParse(document: Document): List<Page> = document
        .select(".pp-comic-content img")
        .mapNotNull { imageUrl(it) }
        .distinct()
        .mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }

    // ========================== Title / Author ===========================

    private fun parseTitleAndAuthor(
        rawTitle: String,
        siteArtist: String?,
    ): ParsedTitle {
        val original = rawTitle.trim()

        // Solo un [] inicial puede aportar el fallback de autor.
        val initialBracketAuthor = BRACKET_PREFIX_REGEX
            .find(original)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.capitalizeName()

        // Pero TODOS los bloques [...] se eliminan del título mostrado.
        val titleWithoutBrackets = original
            .replace(ANY_BRACKET_BLOCK_REGEX, " ")
            .replace(MULTISPACE_REGEX, " ")
            .trim()

        // 1) Artist real manda siempre.
        if (!siteArtist.isNullOrBlank()) {
            return ParsedTitle(
                title = titleWithoutBrackets.ifBlank { original },
                author = siteArtist,
            )
        }

        // 2) Sin Artist, [] inicial puede ser el autor.
        if (!initialBracketAuthor.isNullOrBlank()) {
            return ParsedTitle(
                title = titleWithoutBrackets.ifBlank { original },
                author = initialBracketAuthor,
            )
        }

        // 3) Sin Artist ni [] inicial, probar autor – título / autor - título.
        val dashMatch = DASH_PREFIX_REGEX
            .mapNotNull { regex -> regex.matchEntire(titleWithoutBrackets) }
            .firstOrNull()

        val dashAuthor = dashMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.capitalizeName()

        val titleWithoutDash = dashMatch
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            .orEmpty()

        if (!dashAuthor.isNullOrBlank() && titleWithoutDash.isNotBlank()) {
            return ParsedTitle(
                title = titleWithoutDash,
                author = dashAuthor,
            )
        }

        // 4) Nada fiable: no inventar autor.
        return ParsedTitle(
            title = titleWithoutBrackets.ifBlank { original },
            author = null,
        )
    }

    private fun String.capitalizeName(): String = trim()
        .split(Regex("""\s+"""))
        .joinToString(" ") { word ->
            word.replaceFirstChar { first ->
                if (first.isLowerCase()) {
                    first.titlecase(Locale.getDefault())
                } else {
                    first.toString()
                }
            }
        }

    private fun String.normalizeForComparison(): String = trim()
        .replace(MULTISPACE_REGEX, " ")
        .lowercase(Locale.ROOT)

    // ============================== Utils ================================

    protected fun listingUrl(page: Int): String {
        val cleanPath = listingPath.trim('/')

        return if (page > 1) {
            "$baseUrl/$cleanPath/page/$page/"
        } else {
            "$baseUrl/$cleanPath/"
        }
    }

    private fun imageUrl(element: Element): String? {
        val raw = element
            .attr("data-src")
            .ifBlank { element.attr("data-lazy-src") }
            .ifBlank { element.attr("src") }
            .takeIf { it.isNotBlank() }
            ?: return null

        return element
            .attr("src", raw)
            .attr("abs:src")
            .takeIf { it.isNotBlank() }
            ?: raw
    }

    // ============================ Localization ===========================

    protected open val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "pt" -> "Capítulo"
            "fr" -> "Chapitre"
            "it" -> "Capitolo"
            "de" -> "Kapitel"
            "ru" -> "Глава"
            "uk" -> "Розділ"
            "pl" -> "Rozdział"
            "tr" -> "Bölüm"
            "ja" -> "チャプター"
            "ko" -> "챕터"
            "zh" -> "章节"
            else -> "Chapter"
        }

    protected open val pagesLabel: String
        get() = when (lang) {
            "es", "pt" -> "páginas"
            "fr" -> "pages"
            "it" -> "pagine"
            "de" -> "Seiten"
            "ru" -> "страниц"
            "uk" -> "сторінок"
            "pl" -> "stron"
            "tr" -> "sayfa"
            "ja" -> "ページ"
            "ko" -> "페이지"
            "zh" -> "页"
            "id" -> "halaman"
            "tl" -> "pahina"
            "vi" -> "trang"
            "th" -> "หน้า"
            else -> "pages"
        }

    private data class ParsedTitle(
        val title: String,
        val author: String?,
    )

    companion object {
        private const val PRELOAD_WINDOW = 10
        private const val PRELOAD_WAIT_SECONDS = 15L
        private const val PRELOAD_HEADER = "X-RBK-Preload"

        private val ARTIST_TAG_REGEX =
            Regex("""(?i)^artist\s*:\s*(.+)$""")

        private val BRACKET_PREFIX_REGEX =
            Regex("""^\s*\[([^\[\]]+)]""")

        private val ANY_BRACKET_BLOCK_REGEX =
            Regex("""\[[^\[\]]*]""")

        private val DASH_PREFIX_REGEX = listOf(
            Regex("""^(.+?)\s+[–—]\s+(.+)$""", RegexOption.DOT_MATCHES_ALL),
            Regex("""^(.+?)\s+-\s+(.+)$""", RegexOption.DOT_MATCHES_ALL),
        )

        private val PAGE_COUNT_REGEX =
            Regex("""(\d+)""")

        private val MULTISPACE_REGEX =
            Regex("""\s+""")
    }
}
