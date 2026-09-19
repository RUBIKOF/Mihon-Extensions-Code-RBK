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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Source
abstract class HentaiEnvy : KeiSource() {

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

    private val preloadExecutor by lazy {
        Executors.newFixedThreadPool(PRELOAD_WINDOW)
    }

    private val preloadSlots by lazy {
        ConcurrentHashMap<String, PreloadSlot>()
    }

    private val preloadCalls by lazy {
        ConcurrentHashMap<String, okhttp3.Call>()
    }

    private val preloadLock by lazy {
        Any()
    }

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
                    // Fallback: Mihon hará la descarga normal.
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
        val cards = select(
            "article.hnv-gallery-card, " +
                ".overview_thumbs .thumb, " +
                ".box_thumbs .thumb",
        )

        val mangas = cards
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }

        val hasNextPage =
            selectFirst("a[rel=next][href]") != null ||
                select("ul.pagination a[href], a.page-link[href]")
                    .any {
                        it.text()
                            .trim()
                            .startsWith("Next", ignoreCase = true)
                    }

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val titleLink = element.selectFirst(
            ".hnv-gallery-card__title a[href*=/gallery/], " +
                "a[href*=/gallery/][title], " +
                "a[href*=/gallery/]",
        ) ?: return null

        val title = element
            .selectFirst(".hnv-gallery-card__title")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                titleLink.attr("title")
                    .trim()
                    .removePrefix("Open ")
                    .trim()
            }
            .ifBlank {
                titleLink.attr("aria-label")
                    .trim()
                    .removePrefix("Open ")
                    .trim()
            }
            .ifBlank {
                titleLink.selectFirst(".title")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }

        if (title.isBlank()) return null

        val href = titleLink
            .attr("abs:href")
            .ifBlank { titleLink.attr("href") }

        if (href.isBlank()) return null

        val image = element.selectFirst(
            ".hnv-gallery-card__cover img, " +
                ".hnv-gallery-card__media img, " +
                "img",
        )

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = image?.imageUrl()
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
        title = selectFirst("#gallery-title, h1")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                selectFirst("head > title")
                    ?.text()
                    ?.replace(
                        Regex("""\s*-\s*HentaiEnvy\s*$""", RegexOption.IGNORE_CASE),
                        "",
                    )
                    ?.trim()
                    .orEmpty()
            }

        thumbnail_url = selectFirst(
            ".hnv-gallery-cover img, " +
                "img[data-src*=cover], " +
                "img[src*=cover]",
        )?.imageUrl()

        val authors = entityValues("Artists")
            .map(::capitalizeName)
            .distinct()

        val groups = entityValues("Groups")
            .map(::capitalizeName)
            .distinct()

        when {
            authors.isNotEmpty() -> {
                author = authors.joinToString(", ")
                artist = groups
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
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

        genre = entityValues("Tags")
            .distinctBy { it.lowercase() }
            .joinToString(", ")
            .takeIf(String::isNotBlank)

        val language = entityValues("Languages")
            .firstOrNull()
            ?.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase() else c.toString()
            }

        val category = entityValues("Category")
            .firstOrNull()
            ?.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase() else c.toString()
            }

        description = buildList {
            language?.let { add("$languageLabel: $it") }
            category?.let { add("$categoryLabel: $it") }
        }
            .joinToString("\n")
            .takeIf(String::isNotBlank)

        status = SManga.COMPLETED
        initialized = true
    }

    private fun Document.entityValues(label: String): List<String> {
        val group = select(".hnv-gallery-entity-group")
            .firstOrNull { element ->
                element
                    .selectFirst(".hnv-gallery-entity-label")
                    ?.text()
                    ?.trim()
                    ?.removeSuffix(":")
                    ?.equals(label, ignoreCase = true) == true
            }
            ?: return emptyList()

        val named = group
            .select(".hnv-gallery-tag__name")
            .map { it.text().trim() }
            .filter(String::isNotBlank)

        if (named.isNotEmpty()) {
            return named
        }

        return group
            .select(".hnv-gallery-entity-items a")
            .map { element ->
                element.attr("title")
                    .trim()
                    .ifBlank { element.text().trim() }
            }
            .filter(String::isNotBlank)
    }

    private fun Document.entityText(label: String): String? {
        val group = select(".hnv-gallery-entity-group")
            .firstOrNull { element ->
                element
                    .selectFirst(".hnv-gallery-entity-label")
                    ?.text()
                    ?.trim()
                    ?.removeSuffix(":")
                    ?.equals(label, ignoreCase = true) == true
            }
            ?: return null

        return group
            .selectFirst(".hnv-gallery-entity-items")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun Document.parseChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        url = mangaUrl
        name = chapterLabel
        chapter_number = 1f
        scanlator = pageCount().takeIf { it > 0 }?.let(::localizedPages)
        date_upload = 0L
    }

    private fun Document.pageCount(): Int {
        val pagesGroup = select(".hnv-gallery-entity-group")
            .firstOrNull { element ->
                element
                    .selectFirst(".hnv-gallery-entity-label")
                    ?.text()
                    ?.trim()
                    ?.removeSuffix(":")
                    ?.equals("Pages", ignoreCase = true) == true
            }

        pagesGroup
            ?.text()
            ?.let { text ->
                Regex("""\d+""")
                    .find(text)
                    ?.value
                    ?.toIntOrNull()
            }
            ?.let { return it }

        selectFirst(".hnv-gallery-metadata")
            ?.text()
            ?.let { metadata ->
                Regex(
                    """(?i)\bPages\s*:\s*(\d+)""",
                )
                    .find(metadata)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            ?.let { return it }

        return selectFirst("#load_pages")
            ?.attr("value")
            ?.toIntOrNull()
            ?: 0
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryUrl = getChapterUrl(chapter)
        val galleryId = GALLERY_ID_REGEX
            .find(galleryUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val readerUrl = "$baseUrl/g/$galleryId/1/"
        val response = client.get(readerUrl)
        val body = response.use { it.body.string() }
        val document = Jsoup.parse(body, readerUrl)

        val imageBase = document
            .selectFirst("#readerApp[data-reader-image-base]")
            ?.attr("data-reader-image-base")
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()

        if (imageBase.isBlank()) {
            return emptyList()
        }

        val formats = READER_PAGE_REGEX
            .findAll(body)
            .associate { match ->
                match.groupValues[1].toInt() to match.groupValues[2].lowercase()
            }

        if (formats.isEmpty()) {
            return emptyList()
        }

        val pages = formats
            .toSortedMap()
            .map { (pageNumber, extension) ->
                Page(
                    index = pageNumber - 1,
                    imageUrl = "$imageBase/$pageNumber.$extension",
                )
            }

        preparePreload(pages.mapNotNull { it.imageUrl })

        return pages
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
        const val PRELOAD_WINDOW = 10
        const val PRELOAD_WAIT_SECONDS = 15L
        const val PRELOAD_HEADER = "X-RBK-Preload"

        val GALLERY_ID_REGEX = Regex(
            """/gallery/(\d+)/?""",
            RegexOption.IGNORE_CASE,
        )

        val READER_PAGE_REGEX = Regex(
            """"page"\s*:\s*(\d+)\s*,\s*"ext"\s*:\s*"([A-Za-z0-9]+)"""",
            RegexOption.IGNORE_CASE,
        )
    }
}
