package eu.kanade.tachiyomi.extension.all.naishorbk

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Source
abstract class Naisho : KeiSource() {

    override val supportsLatest = false

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
                return chain.proceed(original)
            }

            preloadSlots.remove(url, slot)
            scheduleNextPreload()

            val mediaType = cached.contentType
                ?.takeIf { it.isNotBlank() }
                ?.toMediaType()

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

            if (preloadSlots.putIfAbsent(url, slot) != null) continue

            preloadExecutor.execute {
                if (generation != preloadGeneration) {
                    slot.ready.countDown()
                    return@execute
                }

                val parsedUrl = runCatching { url.toHttpUrl() }.getOrNull()

                if (parsedUrl == null) {
                    slot.ready.countDown()
                    return@execute
                }

                try {
                    val request = Request.Builder()
                        .url(parsedUrl)
                        .header("User-Agent", headers["User-Agent"].orEmpty())
                        .header(PRELOAD_HEADER, "1")
                        .get()
                        .build()

                    val call = client.newCall(request)
                    preloadCalls[url] = call
                    call.execute().use { response ->
                        if (generation != preloadGeneration) return@use

                        if (!response.isSuccessful) {
                            return@use
                        }

                        val bytes = response.body.bytes()
                        if (generation == preloadGeneration) {
                            slot.image = CachedImage(
                                bytes = bytes,
                                contentType = response.header("Content-Type"),
                            )
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    preloadCalls.remove(url)
                    slot.ready.countDown()
                }
            }
            return
        }
    }

    private val siteLang: String?
        get() = when (lang) {
            "all" -> null
            "ja" -> "jp"
            "en" -> "gb"
            "ko" -> "kr"
            "ru" -> "ru"
            "zh" -> "cn"
            "fr" -> "fr"
            "it" -> "it"
            "es" -> "es"
            "pt" -> "pt"
            "de" -> "de"
            "th" -> "th"
            "ar" -> "sa"
            "tr" -> "tr"
            "he" -> "il"
            "tl" -> "ph"
            "uk" -> "ua"
            "bg" -> "bg"
            "nl" -> "nl"
            "mn" -> "mn"
            "vi" -> "vn"
            "mk" -> "mk"
            "pl" -> "pl"
            "hu" -> "hu"
            "no" -> "no"
            "id" -> "id"
            "lt" -> "lt"
            "sr" -> "rs"
            "fa" -> "ir"
            "hr" -> "hr"
            "cs" -> "cz"
            "sk" -> "sk"
            "ro" -> "ro"
            "fi" -> "fi"
            "el" -> "gr"
            "sv" -> "se"
            "la" -> "va"
            "sq" -> "al"
            "my" -> "mm"
            "ca" -> "es-ct"
            "da" -> "dk"
            "et" -> "ee"
            "hi" -> "in"
            "is" -> "is"
            "jv" -> "jv"
            "km" -> "kh"
            "sl" -> "si"
            else -> null
        }

    override suspend fun getPopularManga(page: Int): MangasPage = getGridPage(
        page = page,
        query = "",
        config = Filters.Config(
            language = siteLang,
            categories = emptyList(),
            includedTags = emptyList(),
            excludedTags = emptyList(),
        ),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val config = Filters.parse(
            filters = filters,
            sourceLang = lang,
            fixedSiteLang = siteLang,
        )

        return getGridPage(
            page = page,
            query = query.trim(),
            config = config,
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList(lang)

    private suspend fun getGridPage(
        page: Int,
        query: String,
        config: Filters.Config,
    ): MangasPage {
        val shellUrl = "$baseUrl/g".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }

            config.language?.let {
                addQueryParameter("lang", it)
            }

            config.categories.forEachIndexed { index, category ->
                addQueryParameter("cats[$index]", category.toString())
            }

            var tagIndex = 0

            config.includedTags.forEach { tag ->
                addQueryParameter("tags[$tagIndex][name]", tag)
                addQueryParameter("tags[$tagIndex][excluded]", "0")
                tagIndex++
            }

            config.excludedTags.forEach { tag ->
                addQueryParameter("tags[$tagIndex][name]", tag)
                addQueryParameter("tags[$tagIndex][excluded]", "1")
                tagIndex++
            }
        }.build()

        val shellResponse = client.get(shellUrl)
        val shellHtml = shellResponse.body.string()
        val shellDocument = Jsoup.parse(shellHtml, shellUrl.toString())

        val livewireConfig = shellDocument
            .selectFirst("script[data-update-uri][data-csrf]")
            ?: error("Livewire config not found")

        val csrf = livewireConfig
            .attr("data-csrf")
            .takeIf { it.isNotBlank() }
            ?: error("Livewire CSRF token not found")

        val livewireUpdateUrl = livewireConfig
            .attr("data-update-uri")
            .takeIf { it.isNotBlank() }
            ?.let { uri ->
                if (uri.startsWith("http")) {
                    uri.toHttpUrl()
                } else {
                    "$baseUrl${if (uri.startsWith("/")) uri else "/$uri"}".toHttpUrl()
                }
            }
            ?: error("Livewire update URI not found")

        val component = shellDocument
            .getAllElements()
            .firstOrNull {
                it.attr("wire:name") == "post-grid"
            }
            ?: error("post-grid component not found")

        var snapshot = component
            .attr("wire:snapshot")
            .takeIf { it.isNotBlank() }
            ?: error("post-grid snapshot not found")

        val lazyToken = Regex(
            """\${'$'}wire\.__lazyLoad\(['"]([^'"]+)['"]\)""",
        ).find(component.attr("x-init"))
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex(
                """\${'$'}wire\.__lazyLoad\(&#0*39;([^&]+)&#0*39;\)""",
            ).find(component.outerHtml())
                ?.groupValues
                ?.getOrNull(1)
            ?: error("post-grid lazy token not found")

        var result = callLivewire(
            url = livewireUpdateUrl,
            referer = shellUrl.toString(),
            csrf = csrf,
            snapshot = snapshot,
            method = "__lazyLoad",
            params = JSONArray().put(lazyToken),
        )

        var currentPage = 1

        while (currentPage < page && result.hasMore) {
            snapshot = result.snapshot

            result = callLivewire(
                url = livewireUpdateUrl,
                referer = shellUrl.toString(),
                csrf = csrf,
                snapshot = snapshot,
                method = "loadMore",
                params = JSONArray(),
            )

            currentPage++
        }

        if (currentPage != page) {
            return MangasPage(emptyList(), false)
        }

        return MangasPage(
            mangas = result.items.mapNotNull(::mangaFromPost),
            hasNextPage = result.hasMore,
        )
    }

    private suspend fun callLivewire(
        url: HttpUrl,
        referer: String,
        csrf: String,
        snapshot: String,
        method: String,
        params: JSONArray,
    ): LivewirePage {
        val call = JSONObject()
            .put("path", "")
            .put("method", method)
            .put("params", params)

        val component = JSONObject()
            .put("snapshot", snapshot)
            .put("updates", JSONObject())
            .put("calls", JSONArray().put(call))

        val payload = JSONObject()
            .put("_token", csrf)
            .put("components", JSONArray().put(component))

        val body = payload
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Livewire", "1")
            .header("Origin", baseUrl)
            .header("Referer", referer)
            .build()

        val responseText = client
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    error("Livewire HTTP ${response.code}")
                }
                response.body.string()
            }

        val root = JSONObject(responseText)
        val responseComponent = root
            .getJSONArray("components")
            .getJSONObject(0)

        val newSnapshot = responseComponent.getString("snapshot")
        val snapshotData = JSONObject(newSnapshot).getJSONObject("data")
        val hasMore = snapshotData.optBoolean("hasMore", false)

        val effects = responseComponent.optJSONObject("effects")
        val dispatches = effects?.optJSONArray("dispatches")

        val items = mutableListOf<JSONObject>()

        if (dispatches != null) {
            for (i in 0 until dispatches.length()) {
                val dispatch = dispatches.optJSONObject(i) ?: continue
                if (dispatch.optString("name") != "posts-loaded") continue

                val params = dispatch.optJSONObject("params") ?: continue
                val array = params.optJSONArray("items") ?: continue

                for (j in 0 until array.length()) {
                    array.optJSONObject(j)?.let(items::add)
                }
            }
        }

        return LivewirePage(
            snapshot = newSnapshot,
            hasMore = hasMore,
            items = items,
        )
    }

    private fun mangaFromPost(post: JSONObject): SManga? {
        val slug = post.optString("slug").trim()
        if (slug.isBlank()) return null

        val title = cleanTitle(
            post.optString("name")
                .ifBlank { post.optString("title") },
        )

        if (title.isBlank()) return null

        val postUrl = post.optString("url")
            .takeIf { it.isNotBlank() }
            ?: "$baseUrl/g/$slug"

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(postUrl)
            thumbnail_url = post.optString("cover")
                .takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "g") return null
        if (segments.getOrNull(2)?.toIntOrNull() != null) return null

        val document = client.get(url).asJsoup()

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
        val details = parseMangaDetails(document)
        val pages = detailPages(document)
        val chapterDate = detailDate(document)

        val chapter = SChapter.create().apply {
            name = chapterLabel
            scanlator = if (pages > 0) "$pages $pagesLabel" else null
            date_upload = chapterDate
            setUrlWithoutDomain(
                document.location().trimEnd('/') + "/1",
            )
        }

        return SMangaUpdate(
            manga = details,
            chapters = listOf(chapter),
        )
    }

    private fun parseMangaDetails(document: Document): SManga {
        val rawTitle = document.selectFirst("h1")?.text().orEmpty()
        val siteArtist = detailField(document, "Artist")
            ?.cleanMetadataName()

        val siteGroup = detailField(document, "Group")
            ?.cleanMetadataName()

        val finalAuthor: String?
        val finalArtist: String?

        when {
            !siteArtist.isNullOrBlank() && !siteGroup.isNullOrBlank() -> {
                finalAuthor = siteArtist
                finalArtist = siteGroup.takeUnless {
                    it.equals(siteArtist, ignoreCase = true)
                }
            }

            !siteArtist.isNullOrBlank() -> {
                finalAuthor = siteArtist
                finalArtist = null
            }

            !siteGroup.isNullOrBlank() -> {
                finalAuthor = siteGroup
                finalArtist = null
            }

            else -> {
                finalAuthor = null
                finalArtist = null
            }
        }

        val category = detailField(document, "Category")
            ?.cleanMetadataName()

        val detailLanguage = if (lang == "all") {
            detailLanguage(document)
        } else {
            null
        }

        val tags = detailTags(document)

        val siteDescription = document
            .selectFirst(
                "article p:not(:has(a)), div.prose p, " +
                    "meta[name=description]",
            )
            ?.let { element ->
                if (element.tagName() == "meta") {
                    element.attr("content")
                } else {
                    element.text()
                }
            }
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("Access thousands", ignoreCase = true)
            }

        val description = buildString {
            category?.takeIf { it.isNotBlank() }?.let {
                append(categoryLabel)
                append(": ")
                append(it)
            }

            detailLanguage?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n")
                append("Language: ")
                append(it)
            }

            siteDescription?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.takeIf { it.isNotBlank() }

        return SManga.create().apply {
            setUrlWithoutDomain(document.location())
            title = cleanTitle(rawTitle)
            author = finalAuthor
            artist = finalArtist
            genre = tags.joinToString(", ").takeIf { it.isNotBlank() }
            this.description = description
            thumbnail_url = document
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document
                    .selectFirst("article img[src], main img[src]")
                    ?.attr("abs:src")
                    ?.takeIf { it.isNotBlank() }
            status = SManga.COMPLETED
        }
    }

    private fun detailField(
        document: Document,
        label: String,
    ): String? {
        val labelElement = document
            .getAllElements()
            .firstOrNull {
                it.ownText().trim().equals(label, ignoreCase = true)
            }
            ?: return null

        labelElement.nextElementSibling()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val parent = labelElement.parent() ?: return null
        val siblings = parent.children()
        val index = siblings.indexOf(labelElement)

        if (index >= 0) {
            siblings
                .drop(index + 1)
                .firstOrNull { it.text().isNotBlank() }
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return parent
            .ownText()
            .removePrefix(label)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun detailLanguage(document: Document): String? {
        val detail = document
            .getAllElements()
            .firstOrNull { it.attr("wire:name") == "pages::post-detail" }
            ?: return null

        return detail
            .select("button")
            .firstNotNullOfOrNull { button ->
                val click = button.attr("@click")
                    .ifBlank { button.attr("x-on:click") }

                if (
                    click.contains("trigger-filter") &&
                    click.contains("lang:") &&
                    !click.contains("tags:")
                ) {
                    button.text()
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.capitalizeName()
                } else {
                    null
                }
            }
    }

    private fun detailTags(document: Document): List<String> {
        val detail = document
            .getAllElements()
            .firstOrNull { it.attr("wire:name") == "pages::post-detail" }
            ?: return emptyList()

        return detail
            .getAllElements()
            .mapNotNull(::triggerFilterValue)
            .filter { (namespace, _) ->
                namespace !in NON_GENRE_NAMESPACES
            }
            .map { (_, value) ->
                value
                    .removePrefix("#")
                    .trim()
                    .capitalizeName()
            }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    private fun triggerFilterValue(element: Element): Pair<String, String>? {
        val click = element.attr("@click")
            .ifBlank { element.attr("x-on:click") }

        if (!click.contains("trigger-filter")) return null

        val raw = TRIGGER_FILTER_NAME
            .find(click)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?: return null

        val separator = raw.indexOf(':')
        if (separator <= 0 || separator >= raw.lastIndex) return null

        val namespace = raw
            .substring(0, separator)
            .trim()
            .lowercase()

        val value = raw
            .substring(separator + 1)
            .trim()

        if (namespace.isBlank() || value.isBlank()) return null

        return namespace to value
    }

    private fun detailDate(document: Document): Long {
        val rawDate = document
            .selectFirst("time[datetime]")
            ?.attr("datetime")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return 0L

        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .parse(rawDate)
                ?.time
                ?: 0L
        }.getOrDefault(0L)
    }

    private fun detailPages(document: Document): Int {
        val direct = detailField(document, "Pages")
            ?.filter(Char::isDigit)
            ?.toIntOrNull()

        if (direct != null) return direct

        return Regex("""\btotal:\s*(\d+)""")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("User-Agent", headers["User-Agent"].orEmpty())
        .get()
        .build()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client
            .get(getChapterUrl(chapter))
            .body
            .string()

        val encoded = Regex(
            """pages:\s*JSON\.parse\('((?:\\.|[^'])*)'\)""",
        ).find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val decodedJson = decodeJavascriptString(encoded)
        val pages = JSONArray(decodedJson)

        val pageList = buildList {
            for (i in 0 until pages.length()) {
                val item = pages.optJSONObject(i) ?: continue
                val imageUrl = item.optString("src")
                if (imageUrl.isBlank()) continue

                add(
                    Page(
                        index = i,
                        imageUrl = imageUrl,
                    ),
                )
            }
        }

        preparePreload(pageList.mapNotNull { it.imageUrl })

        return pageList
    }

    private fun decodeJavascriptString(value: String): String {
        var result = value
            .replace("\\/", "/")
            .replace("\\'", "'")
            .replace("\\\"", "\"")

        result = Regex("""\\u([0-9a-fA-F]{4})""")
            .replace(result) { match ->
                match.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }

        result = result
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

        return result
    }

    private fun cleanTitle(raw: String): String {
        var title = raw

        title = BRACKET_BLOCK.replace(title, " ")

        title = PAREN_BLOCK.replace(title) { match ->
            val inside = match.groupValues[1]
            if (inside.contains("translated", ignoreCase = true) ||
                inside.contains("translate", ignoreCase = true) ||
                inside.contains("translation", ignoreCase = true) ||
                inside.contains("traslate", ignoreCase = true)
            ) {
                " "
            } else {
                match.value
            }
        }

        title = title
            .replace(
                Regex(
                    """(?i)\b(?:translated|translation|translate|traslate)\b""",
                ),
                " ",
            )
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '|')

        return title
    }

    private fun String.cleanMetadataName(): String = removePrefix("#")
        .trim()
        .capitalizeName()

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

    private val chapterLabel: String
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
            "ar" -> "الفصل"
            "he" -> "פרק"
            "ja" -> "チャプター"
            "ko" -> "챕터"
            "zh" -> "章节"
            else -> "Chapter"
        }

    private val pagesLabel: String
        get() = when (lang) {
            "es", "pt" -> "páginas"
            "fr" -> "pages"
            "it" -> "pagine"
            "de" -> "Seiten"
            "ru" -> "страниц"
            "uk" -> "сторінок"
            "pl" -> "stron"
            "tr" -> "sayfa"
            "ar" -> "صفحات"
            "he" -> "עמודים"
            "ja" -> "ページ"
            "ko" -> "페이지"
            "zh" -> "页"
            "id" -> "halaman"
            "tl" -> "pahina"
            "vi" -> "trang"
            "th" -> "หน้า"
            else -> "pages"
        }

    private val categoryLabel: String
        get() = when (lang) {
            "es" -> "Categoría"
            "pt" -> "Categoria"
            "fr" -> "Catégorie"
            "it" -> "Categoria"
            "de" -> "Kategorie"
            "ru" -> "Категория"
            "uk" -> "Категорія"
            "pl" -> "Kategoria"
            "tr" -> "Kategori"
            "ar" -> "الفئة"
            "he" -> "קטגוריה"
            "ja" -> "カテゴリー"
            "ko" -> "카테고리"
            "zh" -> "分类"
            else -> "Category"
        }

    private class LivewirePage(
        val snapshot: String,
        val hasMore: Boolean,
        val items: List<JSONObject>,
    )

    companion object {
        private const val PRELOAD_WINDOW = 10
        private const val PRELOAD_WAIT_SECONDS = 15L
        private const val PRELOAD_HEADER = "X-RBK-Preload"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BRACKET_BLOCK = Regex("""\[[^\]]*]""")
        private val PAREN_BLOCK = Regex("""\(([^()]*)\)""")
        private val TRIGGER_FILTER_NAME = Regex(
            """name\s*:\s*(['"])(.*?)\1""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_GENRE_NAMESPACES = setOf(
            "language",
            "group",
            "artist",
        )
    }
}
