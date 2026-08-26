package eu.kanade.tachiyomi.extension.all.ehentairbk

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class EHentai : KeiSource() {

    private val cursorPages = linkedMapOf<String, MutableMap<Int, HttpUrl>>()
    private val readerStates = linkedMapOf<Long, ReaderState>()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildListingUrl(
            search = sourceLanguageSearch(),
            rating = 5,
        )

        return getListing(
            page = page,
            firstUrl = url,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = buildListingUrl(
            search = sourceLanguageSearch(),
        )

        return getListing(
            page = page,
            firstUrl = url,
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val config = Filters.parse(
            filters = filters,
            sourceLang = lang,
        )

        val search = buildList {
            if (query.isNotBlank()) {
                add(query.trim())
            }

            val language = if (lang == "all") {
                config.language
            } else {
                languageName(lang)
            }

            language?.let {
                add("language:$it$")
            }
        }.joinToString(" ")

        val url = buildListingUrl(
            search = search,
            disabledCategories = config.disabledCategories,
            rating = config.rating,
            minPages = config.minPages,
            maxPages = config.maxPages,
            showExpunged = config.showExpunged,
        )

        return getListing(
            page = page,
            firstUrl = url,
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList(lang)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val sourceHost = baseUrl.toHttpUrl().host

        if (
            url.host != sourceHost ||
            !GALLERY_PATH_REGEX.matches(url.encodedPath)
        ) {
            return null
        }

        val document = client.get(url).asJsoup()

        return document.parseMangaDetails().apply {
            this.url = url.encodedPath
        }
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

        val document = client
            .get(getMangaUrl(manga))
            .asJsoup()

        val updatedManga = if (fetchDetails) {
            document.parseMangaDetails().apply {
                url = manga.url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(document.parseChapter())
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val detailUrl = getChapterUrl(chapter).toHttpUrl()
        val firstDetail = client
            .get(detailUrl)
            .asJsoup()

        val pageCount = chapter.scanlator
            ?.let { PAGE_NUMBER_REGEX.find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return emptyList()

        val detailPageCount = (pageCount + 19) / 20

        val extraDetails = if (detailPageCount > 1) {
            coroutineScope {
                (1 until detailPageCount)
                    .map { detailPage ->
                        async {
                            val pageUrl = detailUrl
                                .newBuilder()
                                .setQueryParameter(
                                    "p",
                                    detailPage.toString(),
                                )
                                .build()

                            client
                                .get(pageUrl)
                                .asJsoup()
                        }
                    }
                    .awaitAll()
            }
        } else {
            emptyList()
        }

        val readerLinks = buildMap<Int, String> {
            (listOf(firstDetail) + extraDetails)
                .forEach { document ->
                    document
                        .select("#gdt a[href*='/s/']")
                        .forEach { link ->
                            val absolute = link.attr("abs:href")
                            val match = READER_LINK_REGEX.find(absolute)
                                ?: return@forEach

                            val key = match.groupValues[1]
                            val gid = match.groupValues[2].toLongOrNull()
                                ?: return@forEach
                            val pageNumber = match.groupValues[3].toIntOrNull()
                                ?: return@forEach

                            if (
                                pageNumber in 1..pageCount &&
                                key.isNotBlank()
                            ) {
                                put(pageNumber, absolute)
                            }
                        }
                }
        }

        val firstReaderUrl = readerLinks[1]
            ?: return emptyList()

        val firstReader = client
            .get(firstReaderUrl)
            .asJsoup()

        val scripts = firstReader
            .select("script:not([src])")
            .joinToString("\n") { it.data() }

        val gid = findJsVar(scripts, "gid")
            ?.toLongOrNull()
            ?: return emptyList()

        val showKey = findJsVar(scripts, "showkey")
            ?: return emptyList()

        val apiUrl = findJsVar(scripts, "api_url")
            ?.toHttpUrlOrNull()
            ?: return emptyList()

        val keys = readerLinks
            .mapValues { (_, url) ->
                READER_LINK_REGEX
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
            }
            .filterValues(String::isNotBlank)

        if (keys.size != pageCount) {
            error(
                "Reader keys incomplete: ${keys.size}/$pageCount",
            )
        }

        val state = ReaderState(
            gid = gid,
            apiUrl = apiUrl,
            showKey = showKey,
            referer = firstReaderUrl,
            keys = keys,
        ).apply {
            firstReader
                .selectFirst("#img")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
                ?.let { images[1] = it }
        }

        synchronized(readerStates) {
            if (readerStates.size >= 8 && gid !in readerStates) {
                readerStates.remove(readerStates.keys.first())
            }
            readerStates[gid] = state
        }

        return (1..pageCount).map { pageNumber ->
            Page(
                index = pageNumber - 1,
                url = "$firstReaderUrl#gid=$gid&page=$pageNumber",
            )
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val marker = page.url.substringAfter('#', "")

        val gid = marker
            .substringAfter("gid=", "")
            .substringBefore('&')
            .toLongOrNull()
            ?: error("Reader gid not found")

        val targetPage = marker
            .substringAfter("page=", "")
            .substringBefore('&')
            .toIntOrNull()
            ?: error("Reader page not found")

        val state = synchronized(readerStates) {
            readerStates[gid]
        } ?: error("Reader state expired")

        state.images[targetPage]?.let {
            return it
        }

        val targetKey = state.keys[targetPage]
            ?: error("Reader key not found for page $targetPage")

        val origin = state.referer.toHttpUrl().let {
            "${it.scheme}://${it.host}"
        }

        val apiHeaders = headers.newBuilder()
            .set("Referer", state.referer)
            .set("Origin", origin)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client
            .post(
                state.apiUrl,
                apiHeaders,
                ShowPageRequest(
                    method = "showpage",
                    gid = state.gid,
                    page = targetPage,
                    imgkey = targetKey,
                    showkey = state.showKey,
                ).toJsonRequestBody(),
            )
            .parseAs<ShowPageResponse>()

        response.error?.let {
            error("Reader API: $it")
        }

        val imageHtml = response.i3
            ?: error("Reader API image missing")

        val imageUrl = Jsoup
            .parseBodyFragment(imageHtml)
            .selectFirst("#img, img")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?: error("Reader image URL missing for page $targetPage")

        synchronized(state.images) {
            state.images[targetPage] = imageUrl
        }

        return imageUrl
    }

    private suspend fun getListing(
        page: Int,
        firstUrl: HttpUrl,
    ): MangasPage {
        val key = firstUrl.toString()
        val pageUrl = resolvePageUrl(
            page = page,
            firstUrl = firstUrl,
            key = key,
        ) ?: return MangasPage(
            mangas = emptyList(),
            hasNextPage = false,
        )

        val document = client
            .get(pageUrl)
            .asJsoup()

        val mangas = document
            .select("table.itg tr")
            .mapNotNull(::mangaFromElement)

        val nextUrl = document
            .selectFirst("a#unext[href], a#dnext[href]")
            ?.attr("abs:href")
            ?.toHttpUrlOrNull()

        rememberCursor(
            key = key,
            page = page + 1,
            url = nextUrl,
        )

        return MangasPage(
            mangas = mangas,
            hasNextPage = nextUrl != null,
        )
    }

    private suspend fun resolvePageUrl(
        page: Int,
        firstUrl: HttpUrl,
        key: String,
    ): HttpUrl? {
        if (page <= 1) {
            rememberCursor(
                key = key,
                page = 1,
                url = firstUrl,
            )
            return firstUrl
        }

        cursorPages[key]?.get(page)?.let {
            return it
        }

        var currentPage = cursorPages[key]
            ?.keys
            ?.filter { it < page }
            ?.maxOrNull()
            ?: 1

        var currentUrl = cursorPages[key]
            ?.get(currentPage)
            ?: firstUrl

        while (currentPage < page) {
            val document = client
                .get(currentUrl)
                .asJsoup()

            val next = document
                .selectFirst("a#unext[href], a#dnext[href]")
                ?.attr("abs:href")
                ?.toHttpUrlOrNull()
                ?: return null

            currentPage += 1
            currentUrl = next

            rememberCursor(
                key = key,
                page = currentPage,
                url = currentUrl,
            )
        }

        return currentUrl
    }

    private fun rememberCursor(
        key: String,
        page: Int,
        url: HttpUrl?,
    ) {
        if (cursorPages.size >= 16 && key !in cursorPages) {
            cursorPages.remove(cursorPages.keys.first())
        }

        val pages = cursorPages.getOrPut(key) {
            mutableMapOf()
        }

        if (url == null) {
            pages.remove(page)
        } else {
            pages[page] = url
        }
    }

    private fun buildListingUrl(
        search: String = "",
        disabledCategories: Int? = null,
        rating: Int? = null,
        minPages: Int? = null,
        maxPages: Int? = null,
        showExpunged: Boolean = false,
    ): HttpUrl = baseUrl
        .toHttpUrl()
        .newBuilder()
        .apply {
            if (search.isNotBlank()) {
                addQueryParameter("f_search", search)
            }

            disabledCategories
                ?.takeIf { it != 0 }
                ?.let {
                    addQueryParameter(
                        "f_cats",
                        it.toString(),
                    )
                }

            rating
                ?.takeIf { it > 0 }
                ?.let {
                    addQueryParameter(
                        "f_srdd",
                        it.toString(),
                    )
                }

            minPages
                ?.takeIf { it > 0 }
                ?.let {
                    addQueryParameter(
                        "f_spf",
                        it.toString(),
                    )
                }

            maxPages
                ?.takeIf { it > 0 }
                ?.let {
                    addQueryParameter(
                        "f_spt",
                        it.toString(),
                    )
                }

            if (showExpunged) {
                addQueryParameter("f_sh", "on")
            }
        }
        .build()

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst(
            ".gl3c.glname > a[href], .glname > a[href]",
        ) ?: return null

        val titleNode = element.selectFirst(".glink")
            ?: return null

        val rawTitle = titleNode.text().trim()
        if (rawTitle.isBlank()) return null

        val metadata = listingMetadata(element)

        return SManga.create().apply {
            title = cleanTitle(
                rawTitle = rawTitle,
                tags = metadata.tags,
                uploaders = metadata.uploaders,
            )

            setUrlWithoutDomain(link.attr("abs:href"))

            thumbnail_url = element
                .selectFirst(".glthumb img, .gl2c img")
                ?.imageUrl()
        }
    }

    private fun Document.parseMangaDetails(): SManga {
        val rawTitle = selectFirst("#gn")
            ?.text()
            ?.trim()
            .orEmpty()

        val tags = detailTags()
        val uploaders = detailUploaders()

        val artists = tags
            .filter { it.namespace == "artist" }
            .map { it.value.capitalizeName() }
            .filter(String::isNotBlank)
            .distinct()

        val contentTags = tags
            .filter {
                it.namespace in CONTENT_TAG_NAMESPACES
            }
            .map { it.value.capitalizeTag() }
            .filter(String::isNotBlank)
            .distinct()

        return SManga.create().apply {
            title = cleanTitle(
                rawTitle = rawTitle,
                tags = tags,
                uploaders = uploaders,
            )

            thumbnail_url = coverUrl()

            author = artists
                .joinToString(", ")
                .takeIf(String::isNotBlank)

            artist = null

            genre = contentTags
                .joinToString(", ")
                .takeIf(String::isNotBlank)

            description = null
            status = SManga.COMPLETED
            initialized = true
        }
    }

    private fun Document.parseChapter(): SChapter {
        val pages = pageCount()
        val posted = metadataValue("Posted")

        return SChapter.create().apply {
            setUrlWithoutDomain(location())
            name = chapterName(lang)
            chapter_number = 1f
            scanlator = localizedPages(
                lang = lang,
                pages = pages,
            )
            date_upload = DATE_TIME_FORMAT
                .tryParseDateTime(posted)
        }
    }

    private fun listingMetadata(element: Element): TitleMetadata {
        val tags = element
            .select(".gt[title], .gtl[title]")
            .mapNotNull { node ->
                val raw = node.attr("title").trim()
                val separator = raw.indexOf(':')

                if (separator <= 0) {
                    return@mapNotNull null
                }

                TitleTag(
                    namespace = normalizeText(
                        raw.substring(
                            0,
                            separator,
                        ),
                    ),
                    value = raw.substring(separator + 1).trim(),
                )
            }

        val uploaders = element
            .select(".gl4c a[href*='/uploader/']")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .map { UploaderInfo(it) }

        return TitleMetadata(
            tags = tags,
            uploaders = uploaders,
        )
    }

    private fun Document.detailTags(): List<TitleTag> = select("#taglist a[href*='/tag/']")
        .mapNotNull { link ->
            val rawPath = runCatching {
                URLDecoder.decode(
                    link.attr("href"),
                    Charsets.UTF_8.name(),
                )
            }.getOrDefault(link.attr("href"))

            val tagPart = rawPath.substringAfter(
                "/tag/",
                "",
            )

            val separator = tagPart.indexOf(':')
            val namespace = if (separator > 0) {
                normalizeText(
                    tagPart.substring(
                        0,
                        separator,
                    ),
                )
            } else {
                link
                    .closest("tr")
                    ?.selectFirst("td.tc")
                    ?.text()
                    ?.removeSuffix(":")
                    ?.let(::normalizeText)
                    .orEmpty()
            }

            val value = link.text().trim()

            if (
                namespace.isBlank() ||
                value.isBlank()
            ) {
                null
            } else {
                TitleTag(
                    namespace = namespace,
                    value = value,
                )
            }
        }
        .distinctBy {
            it.namespace to normalizeText(it.value)
        }

    private fun Document.detailUploaders(): List<UploaderInfo> = select("a[href*='/uploader/']")
        .map { it.text().trim() }
        .filter(String::isNotBlank)
        .distinct()
        .map { UploaderInfo(it) }

    private fun Document.coverUrl(): String? {
        val image = selectFirst("#gd1 img")
            ?.imageUrl()

        if (!image.isNullOrBlank()) {
            return image
        }

        val style = selectFirst(
            "#gd1 div[style*=background]",
        )?.attr("style")
            ?: return null

        val raw = BACKGROUND_URL_REGEX
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null

        return resolveUrl(raw)
    }

    private fun Document.metadataValue(label: String): String = select("#gdd tr")
        .firstOrNull { row ->
            row.children()
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.removeSuffix(":")
                ?.equals(
                    label,
                    ignoreCase = true,
                ) == true
        }
        ?.children()
        ?.lastOrNull()
        ?.text()
        ?.trim()
        .orEmpty()

    private fun Document.pageCount(): Int = PAGE_COUNT_REGEX
        .find(metadataValue("Length"))
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src")
            .takeIf(String::isNotBlank)
            ?: attr("data-lazy-src")
                .takeIf(String::isNotBlank)
            ?: attr("src")
                .takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("data:")
                }
            ?: return null

        return resolveUrl(raw)
    }

    private fun resolveUrl(raw: String): String = when {
        raw.startsWith("http://") ||
            raw.startsWith("https://") -> raw

        raw.startsWith("//") -> "https:$raw"

        raw.startsWith("/") ->
            baseUrl.trimEnd('/') + raw

        else ->
            baseUrl.trimEnd('/') + "/" + raw
    }

    private fun sourceLanguageSearch(): String = if (lang == "all") {
        ""
    } else {
        languageName(lang)
            ?.let { "language:$it$" }
            .orEmpty()
    }

    private fun languageName(sourceLang: String): String? = LANGUAGE_NAMES[sourceLang]

    private fun cleanTitle(
        rawTitle: String,
        tags: List<TitleTag>,
        uploaders: List<UploaderInfo>,
    ): String {
        val cleaned = TITLE_BLOCK_REGEX.replace(
            rawTitle,
        ) { match ->
            val block = match.value
            val inner = block
                .drop(1)
                .dropLast(1)
                .trim()

            if (
                shouldRemoveTitleBlock(
                    inner = inner,
                    tags = tags,
                    uploaders = uploaders,
                )
            ) {
                " "
            } else {
                block
            }
        }

        return cleaned
            .replace(SPACE_REGEX, " ")
            .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
            .trim(' ', '-', '–', '—', '_', '|')
    }

    private fun shouldRemoveTitleBlock(
        inner: String,
        tags: List<TitleTag>,
        uploaders: List<UploaderInfo>,
    ): Boolean {
        val normalized = normalizeText(inner)
        if (normalized.isBlank()) return true

        if (normalized in KNOWN_LANGUAGES) {
            return true
        }

        if (normalized in KNOWN_METADATA) {
            return true
        }

        if (
            normalized
                .split(' ')
                .any {
                    it in TRANSLATION_MARKERS
                }
        ) {
            return true
        }

        if (
            tags.any {
                fuzzyMatches(
                    normalized,
                    normalizeText(it.value),
                )
            }
        ) {
            return true
        }

        return uploaders.any { uploader ->
            uploader.aliases.any { alias ->
                uploaderAliasMatches(
                    block = normalized,
                    alias = alias,
                )
            }
        }
    }

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[_+]"), " ")
        .replace(NON_WORD_REGEX, " ")
        .replace(SPACE_REGEX, " ")
        .trim()

    private fun fuzzyMatches(
        block: String,
        candidate: String,
    ): Boolean {
        if (
            block.isBlank() ||
            candidate.isBlank()
        ) {
            return false
        }

        if (block == candidate) return true

        if (
            candidate in block ||
            block in candidate
        ) {
            val shorter = minOf(
                block.length,
                candidate.length,
            )

            val longer = maxOf(
                block.length,
                candidate.length,
            )

            if (
                shorter >= 5 &&
                shorter.toDouble() / longer >= 0.60
            ) {
                return true
            }
        }

        val left = block
            .split(' ')
            .filter(String::isNotBlank)
            .toSet()

        val right = candidate
            .split(' ')
            .filter(String::isNotBlank)
            .toSet()

        if (
            left.isEmpty() ||
            right.isEmpty()
        ) {
            return false
        }

        val overlap = left.intersect(right).size
        val coverage = overlap.toDouble() /
            minOf(
                left.size,
                right.size,
            )

        return overlap >= 2 &&
            coverage >= 0.80
    }

    private fun uploaderAliasMatches(
        block: String,
        alias: String,
    ): Boolean {
        val blockCompact = block.replace(" ", "")
        val aliasNormalized = normalizeText(alias)
        val aliasCompact = aliasNormalized.replace(" ", "")

        if (
            aliasCompact.length >= 6 &&
            aliasCompact in blockCompact
        ) {
            return true
        }

        if (
            blockCompact.length >= 6 &&
            blockCompact in aliasCompact
        ) {
            return true
        }

        return fuzzyMatches(
            block,
            aliasNormalized,
        )
    }

    private fun uploaderAliases(value: String): Set<String> {
        val normalized = normalizeText(value)
        val tokens = normalized
            .split(' ')
            .filter(String::isNotBlank)

        val aliases = mutableSetOf<String>()
        if (normalized.isNotBlank()) {
            aliases += normalized
        }

        val core = tokens
            .dropWhile {
                it in GENERIC_AFFIXES
            }
            .dropLastWhile {
                it in GENERIC_AFFIXES
            }

        if (core.isNotEmpty()) {
            aliases += core.joinToString(" ")
            aliases += core.joinToString("")
        }

        if (tokens.isNotEmpty()) {
            aliases += tokens.joinToString("")
        }

        val compact = tokens.joinToString("")

        GENERIC_AFFIXES.forEach { affix ->
            if (
                compact.endsWith(affix) &&
                compact.length > affix.length + 3
            ) {
                aliases += compact.dropLast(
                    affix.length,
                )
            }

            if (
                compact.startsWith(affix) &&
                compact.length > affix.length + 3
            ) {
                aliases += compact.drop(
                    affix.length,
                )
            }
        }

        return aliases.filterTo(mutableSetOf()) {
            it.replace(" ", "").length >= 4
        }
    }

    private fun String.capitalizeName(): String = trim()
        .split(SPACE_REGEX)
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.ROOT)
                } else {
                    char.toString()
                }
            }
        }

    private fun String.capitalizeTag(): String = lowercase(Locale.ROOT)
        .replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(Locale.ROOT)
            } else {
                char.toString()
            }
        }

    private fun findJsVar(
        script: String,
        name: String,
    ): String? {
        val quoted = Regex(
            """\b${Regex.escape(name)}\s*=\s*["']([^"']+)["']""",
        ).find(script)
            ?.groupValues
            ?.getOrNull(1)

        if (quoted != null) {
            return quoted
        }

        return Regex(
            """\b${Regex.escape(name)}\s*=\s*(\d+)""",
        ).find(script)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractNextPage(
        navHtml: String,
        currentPage: Int,
    ): Pair<Int, String>? {
        val document = Jsoup.parseBodyFragment(
            navHtml,
        )

        val candidates = buildList {
            document
                .selectFirst("#next[onclick]")
                ?.let(::add)

            addAll(
                document.select(
                    "a[onclick*=load_image]",
                ),
            )
        }

        for (element in candidates) {
            val match = LOAD_IMAGE_REGEX
                .find(element.attr("onclick"))
                ?: continue

            val page = match
                .groupValues[1]
                .toIntOrNull()
                ?: continue

            val key = match.groupValues[2]

            if (page == currentPage + 1) {
                return page to key
            }
        }

        return null
    }

    private fun chapterName(sourceLang: String): String = when (sourceLang) {
        "es" -> "Capítulo"
        "pt" -> "Capítulo"
        "fr" -> "Chapitre"
        "de" -> "Kapitel"
        "it" -> "Capitolo"
        "ja" -> "章"
        "ko" -> "장"
        "zh" -> "章节"
        "ru" -> "Глава"
        else -> "Chapter"
    }

    private fun localizedPages(
        lang: String,
        pages: Int,
    ): String = when (lang) {
        "es" -> "$pages páginas"
        "pt" -> "$pages páginas"
        "fr" -> "$pages pages"
        "de" -> "$pages Seiten"
        "it" -> "$pages pagine"
        "ja" -> "$pages ページ"
        "ko" -> "$pages 페이지"
        "zh" -> "$pages 页"
        "ru" -> "$pages страниц"
        else -> "$pages pages"
    }

    private class TitleMetadata(
        val tags: List<TitleTag>,
        val uploaders: List<UploaderInfo>,
    )

    private class TitleTag(
        val namespace: String,
        val value: String,
    )

    private inner class UploaderInfo(
        val value: String,
    ) {
        val aliases = uploaderAliases(value)
    }

    private class ReaderState(
        val gid: Long,
        val apiUrl: HttpUrl,
        val showKey: String,
        val referer: String,
        val keys: Map<Int, String>,
    ) {
        val images = mutableMapOf<Int, String>()
    }

    @Serializable
    private class ShowPageRequest(
        val method: String,
        val gid: Long,
        val page: Int,
        val imgkey: String,
        val showkey: String,
    )

    @Serializable
    private class ShowPageResponse(
        val p: Int? = null,
        val k: String? = null,
        val i3: String? = null,
        val n: String? = null,
        val error: String? = null,
    )

    companion object {
        private val LANGUAGE_NAMES = mapOf(
            "en" to "english",
            "es" to "spanish",
            "ja" to "japanese",
            "zh" to "chinese",
            "fr" to "french",
            "de" to "german",
            "ko" to "korean",
            "ru" to "russian",
            "it" to "italian",
            "pt" to "portuguese",
        )

        private val CONTENT_TAG_NAMESPACES = setOf(
            "female",
            "male",
            "mixed",
            "other",
        )

        private val KNOWN_LANGUAGES = setOf(
            "english",
            "spanish",
            "japanese",
            "chinese",
            "korean",
            "french",
            "german",
            "italian",
            "portuguese",
            "russian",
            "thai",
            "vietnamese",
            "indonesian",
            "polish",
            "dutch",
            "arabic",
            "turkish",
            "czech",
            "hungarian",
        )

        private val KNOWN_METADATA = setOf(
            "digital",
            "digital version",
            "translated",
            "translation",
            "translations",
            "traduccion",
            "traducción",
            "scan",
            "scans",
            "scanlation",
            "scanlations",
            "uncensored",
            "censored",
            "web",
            "web version",
            "team",
            "group",
            "blog",
        )

        private val GENERIC_AFFIXES = setOf(
            "blog",
            "team",
            "group",
            "translation",
            "translations",
            "traduccion",
            "traducción",
            "scan",
            "scans",
            "scanlation",
            "scanlations",
            "project",
            "projects",
            "fansub",
            "fansubs",
            "sub",
            "subs",
            "site",
            "web",
        )

        private val TRANSLATION_MARKERS = setOf(
            "translate",
            "translated",
            "translation",
            "translations",
            "translator",
            "translators",
            "traslate",
            "traslated",
            "traslation",
            "traduccion",
            "traducción",
            "traducido",
            "traducida",
            "traducidos",
            "traducidas",
            "traductor",
            "traductora",
            "scanlation",
            "scanlations",
            "scanlator",
            "scanlators",
            "fansub",
            "fansubs",
        )

        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm",
            Locale.ROOT,
        )

        private val GALLERY_PATH_REGEX = Regex(
            """/g/\d+/[a-zA-Z0-9]+/?""",
        )

        private val BACKGROUND_URL_REGEX = Regex(
            """url\((['"]?)(.*?)\1\)""",
        )

        private val PAGE_COUNT_REGEX = Regex(
            """(\d+)\s+pages?""",
            RegexOption.IGNORE_CASE,
        )

        private val READER_LINK_REGEX = Regex(
            """/s/([^/]+)/(\d+)-(\d+)""",
        )

        private val PAGE_NUMBER_REGEX = Regex("""(\d+)""")

        private val TITLE_BLOCK_REGEX = Regex(
            """\[[^\[\]]*]|\([^()]*\)""",
        )

        private val SPACE_REGEX = Regex("""\s+""")
        private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}\s]+""")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex(
            """\s+([,.;:!?])""",
        )

        private val LOAD_IMAGE_REGEX = Regex(
            """load_image\(\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*\)""",
        )

        private val TOTAL_PAGES_REGEX = Regex(
            """(\d+)\s*/\s*(\d+)""",
        )
    }
}
