package eu.kanade.tachiyomi.extension.all.hitomirbk

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class Hitomi : KeiSource() {

    private val dataBaseUrl = "https://ltn.gold-usergeneratedcontent.net"

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(popularPath, page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(latestPath, page)

    private suspend fun getListing(
        path: String,
        page: Int,
    ): MangasPage {
        val nozomiPath = if (path == "/") {
            "/index-all.nozomi"
        } else {
            path.removeSuffix(".html") + ".nozomi"
        }

        val nozomiUrl = "$dataBaseUrl$nozomiPath"

        val idsPerPage = 25
        val bytesPerId = 4

        val start = (page - 1L) * idsPerPage * bytesPerId
        val end = start + ((idsPerPage + 1L) * bytesPerId) - 1

        val response = client.get(
            nozomiUrl,
            headers = headers.newBuilder()
                .set("Range", "bytes=$start-$end")
                .build(),
        )

        var bytes = response.body.bytes()

        if (
            response.code == 200 &&
            start <= Int.MAX_VALUE &&
            start < bytes.size
        ) {
            val from = start.toInt()

            val to = minOf(
                end.toInt() + 1,
                bytes.size,
            )

            bytes = bytes.copyOfRange(from, to)
        }

        if (bytes.size < 4) {
            return MangasPage(
                mangas = emptyList(),
                hasNextPage = false,
            )
        }

        val galleryIds = buildList {
            var position = 0

            while (position + 4 <= bytes.size) {
                add(readInt32BE(bytes, position))
                position += 4
            }
        }

        val hasNextPage = galleryIds.size > idsPerPage

        val pageIds = galleryIds.take(idsPerPage)

        val mangas = coroutineScope {
            pageIds.map { galleryId ->
                async {
                    val blockHtml = client
                        .get("$dataBaseUrl/galleryblock/$galleryId.html")
                        .body
                        .string()

                    parseSearchBlock(
                        html = blockHtml,
                        documentBase = baseUrl,
                    )
                }
            }.awaitAll()
                .filterNotNull()
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val totalStart = System.currentTimeMillis()

        val cleanQuery = query
            .trim()
            .lowercase()

        val config = Filters.parse(
            filters = filters,
            sourceLang = lang,
        )

        val languageSuffix = config.language ?: "all"

        val snapshotKey = buildString {
            append(cleanQuery)
            append('|')
            append(languageSuffix)
            append('|')
            append(config.sort.name)

            config.contentFilters.forEach {
                append('|')
                append(it.category)
                append(':')
                append(it.value)
            }
        }

        val mustRefresh =
            page == 1 ||
                searchSnapshotKey != snapshotKey ||
                searchSnapshotIds == null ||
                searchSnapshotDataBase == null

        val results: List<Int>
        val dataBase: String

        if (mustRefresh) {
            val shellExpression = buildString {
                config.language?.let {
                    append("language:")
                    append(it)
                    append(' ')
                }

                if (cleanQuery.isNotBlank()) {
                    append(cleanQuery)
                }
            }.trim()

            val encodedShellExpression = URLEncoder
                .encode(
                    shellExpression,
                    Charsets.UTF_8.name(),
                )
                .replace("+", "%20")

            val searchShellUrl =
                "$baseUrl/search.html?$encodedShellExpression"

            val searchDocument = client
                .get(searchShellUrl)
                .asJsoup()

            val searchScriptUrl = searchDocument
                .selectFirst("script[src$='searchlib.js']")
                ?.attr("abs:src")
                ?.toHttpUrlOrNull()

            dataBase = searchScriptUrl
                ?.let {
                    "${it.scheme}://${it.host}"
                }
                ?: baseUrl

            val filterIdSets = config.contentFilters.map { contentFilter ->
                getNozomiIdsForContentFilter(
                    dataBase = dataBase,
                    contentFilter = contentFilter,
                    languageSuffix = languageSuffix,
                ).toHashSet()
            }

            val queryTerms = cleanQuery
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            val queryIdSets = if (queryTerms.isNotEmpty()) {
                val galleriesVersion = getCachedGalleriesVersion(
                    dataBase = dataBase,
                )

                if (galleriesVersion.isBlank()) {
                    return MangasPage(
                        emptyList(),
                        false,
                    )
                }

                coroutineScope {
                    queryTerms.map { term ->
                        async {
                            getGalleryIdsForPlainQuery(
                                dataBase = dataBase,
                                version = galleriesVersion,
                                term = term,
                            ).toHashSet()
                        }
                    }.awaitAll()
                }
            } else {
                emptyList()
            }

            val allConstraintSets =
                filterIdSets + queryIdSets

            val orderedBaseIds = config.contentFilters
                .firstOrNull()
                ?.let { primaryFilter ->
                    getNozomiIdsForContentFilter(
                        dataBase = dataBase,
                        contentFilter = primaryFilter,
                        languageSuffix = languageSuffix,
                        sort = config.sort,
                    )
                }
                ?: getNozomiIdsForGlobalSort(
                    dataBase = dataBase,
                    languageSuffix = languageSuffix,
                    sort = config.sort,
                )

            val setsToApply =
                if (config.contentFilters.isNotEmpty()) {
                    allConstraintSets.drop(1)
                } else {
                    allConstraintSets
                }

            var freshResults = orderedBaseIds.filter { galleryId ->
                setsToApply.all { set ->
                    galleryId in set
                }
            }

            if (config.sort == Filters.SortMode.RANDOM) {
                freshResults = freshResults.shuffled()
            }

            searchSnapshotKey = snapshotKey
            searchSnapshotIds = freshResults
            searchSnapshotDataBase = dataBase

            results = freshResults
        } else {
            results = searchSnapshotIds.orEmpty()
            dataBase = searchSnapshotDataBase ?: baseUrl
        }

        val idsElapsed = System.currentTimeMillis() - totalStart
        println("RBK_SEARCH_IDS_MS=$idsElapsed")

        val fromIndex =
            (page - 1) * 25

        if (fromIndex >= results.size) {
            return MangasPage(
                emptyList(),
                false,
            )
        }

        val toIndex = minOf(
            fromIndex + 25,
            results.size,
        )

        val pageIds = results.subList(
            fromIndex,
            toIndex,
        )

        val blocksStart = System.currentTimeMillis()

        val mangas = coroutineScope {
            pageIds.map { galleryId ->
                async {
                    val blockHtml = client
                        .get(
                            "$dataBase/galleryblock/$galleryId.html",
                        )
                        .body
                        .string()

                    parseSearchBlock(
                        html = blockHtml,
                        documentBase = dataBase,
                    )
                }
            }.awaitAll()
                .filterNotNull()
        }

        val blocksElapsed = System.currentTimeMillis() - blocksStart
        val totalElapsed = System.currentTimeMillis() - totalStart

        println("RBK_SEARCH_BLOCKS_MS=$blocksElapsed")
        println("RBK_SEARCH_TOTAL_MS=$totalElapsed")
        println("RBK_SEARCH_PAGE_ITEMS=${pageIds.size}")

        return MangasPage(
            mangas = mangas,
            hasNextPage = toIndex < results.size,
        )
    }

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        val galleryId = extractGalleryId(
            url.toString(),
        ) ?: return null

        val blockHtml = client
            .get("$dataBaseUrl/galleryblock/$galleryId.html")
            .body
            .string()

        return parseSearchBlock(
            html = blockHtml,
            documentBase = baseUrl,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val galleryId = extractGalleryId(manga.url)
            ?: return SMangaUpdate(
                manga = manga,
                chapters = if (fetchChapters) emptyList() else chapters,
            )

        val galleryInfo = if (fetchDetails || fetchChapters) {
            val script = client
                .get("$dataBaseUrl/galleries/$galleryId.js")
                .body
                .string()

            parseGalleryInfo(script)
        } else {
            null
        }

        val updatedManga = if (fetchDetails && galleryInfo != null) {
            manga.apply {
                galleryInfo.optString("title")
                    .takeIf { it.isNotBlank() }
                    ?.let { title = it }

                val artists = jsonNames(
                    galleryInfo = galleryInfo,
                    arrayName = "artists",
                    valueName = "artist",
                )

                val groups = jsonNames(
                    galleryInfo = galleryInfo,
                    arrayName = "groups",
                    valueName = "group",
                )

                val type = formatDisplayText(
                    galleryInfo
                        .optString("type")
                        .trim(),
                )

                val language = formatDisplayText(
                    galleryInfo
                        .optString("language_localname")
                        .ifBlank {
                            galleryInfo.optString("language")
                        }
                        .trim(),
                )

                val series = buildList {
                    addAll(
                        jsonNames(
                            galleryInfo = galleryInfo,
                            arrayName = "parodys",
                            valueName = "parody",
                        ),
                    )

                    addAll(
                        jsonNames(
                            galleryInfo = galleryInfo,
                            arrayName = "series",
                            valueName = "series",
                        ),
                    )
                }.distinct()

                val characters = jsonNames(
                    galleryInfo = galleryInfo,
                    arrayName = "characters",
                    valueName = "character",
                )

                val tags = jsonTags(galleryInfo)

                val pages = galleryInfo
                    .optJSONArray("files")
                    ?.length()
                    ?: 0

                author = artists.joinToString(", ")
                artist = groups.joinToString(", ")

                genre = tags
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(", ")

                description = buildList {
                    if (type.isNotBlank()) {
                        add("Type: $type")
                    }

                    if (language.isNotBlank()) {
                        add("Language: $language")
                    }

                    if (series.isNotEmpty()) {
                        add("Series: ${series.joinToString(", ")}")
                    }

                    if (characters.isNotEmpty()) {
                        add("Characters: ${characters.joinToString(", ")}")
                    }

                    if (pages > 0) {
                        add("Pages: $pages")
                    }
                }.joinToString("\n")

                status = SManga.COMPLETED
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val type = galleryInfo
                ?.optString("type")
                ?.trim()
                .orEmpty()

            listOf(
                SChapter.create().apply {
                    url = "/reader/$galleryId.html"
                    name = chapterLabel
                    chapter_number = 1f

                    date_upload = parseGalleryDate(
                        galleryInfo
                            ?.optString("date")
                            .orEmpty(),
                    )

                    scanlator = formatDisplayText(type)
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        val galleryId = galleryIdRegex
            .find(chapterUrl)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val galleryScript = client
            .get("$dataBaseUrl/galleries/$galleryId.js")
            .body
            .string()

        val galleryInfo = parseGalleryInfo(galleryScript)
            ?: return emptyList()

        val files = galleryInfo
            .optJSONArray("files")
            ?: return emptyList()

        return buildList {
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index)
                    ?: continue

                val hash = file
                    .optString("hash")
                    .trim()

                if (hash.isBlank()) {
                    continue
                }

                val isGif = file
                    .optString("name")
                    .endsWith(
                        suffix = ".gif",
                        ignoreCase = true,
                    )

                add(
                    Page(
                        index = index,
                        url = buildString {
                            append(chapterUrl)
                            append("#hash=")
                            append(hash)
                            append("&gif=")
                            append(isGif)
                        },
                    ),
                )
            }
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val metadata = page.url
            .substringAfter(
                delimiter = '#',
                missingDelimiterValue = "",
            )

        val hash = readerHashRegex
            .find(metadata)
            ?.groupValues
            ?.get(1)
            ?: return ""

        val isGif = readerGifRegex
            .find(metadata)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()
            ?: false

        refreshImageScript()

        val imageId = hashSegment(hash)

        val subdomainOffset = imageSubdomainOffsetMap[imageId]
            ?: imageSubdomainOffsetDefault

        val type = if (isGif) {
            "webp"
        } else {
            "avif"
        }

        val subdomain = if (isGif) {
            "w${subdomainOffset + 1}"
        } else {
            "a${subdomainOffset + 1}"
        }

        val imageDomain = dataBaseUrl
            .toHttpUrl()
            .host
            .substringAfter('.')

        return "https://$subdomain.$imageDomain/" +
            "$imageCommonId$imageId/$hash.$type"
    }

    override fun getFilterList(data: JsonElement?): FilterList = Filters.getFilterList(lang)

    override fun imageRequest(page: Page): Request = super.imageRequest(page)
        .newBuilder()
        .header(
            "Accept",
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        )
        .header(
            "Referer",
            page.url.substringBefore('#'),
        )
        .build()

    private var cachedGalleriesVersion: String? = null
    private var cachedGalleriesVersionAt = 0L
    private var cachedGalleriesVersionDataBase: String? = null

    private val imageScriptMutex = Mutex()
    private var imageScriptLastRetrieval = 0L
    private var imageSubdomainOffsetDefault = 1
    private val imageSubdomainOffsetMap = mutableMapOf<Int, Int>()
    private var imageCommonId = ""

    private suspend fun refreshImageScript() = imageScriptMutex.withLock {
        val now = System.currentTimeMillis()

        if (
            imageCommonId.isNotBlank() &&
            now - imageScriptLastRetrieval < 60_000L
        ) {
            return@withLock
        }

        val ggUrl = "$dataBaseUrl/gg.js"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "_",
                now.toString(),
            )
            .build()

        val ggScript = client
            .get(
                ggUrl,
                headers = headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build(),
            )
            .body
            .string()

        val defaultOffset = ggDefaultOffsetRegex
            .find(ggScript)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return@withLock

        val caseOffset = ggCaseOffsetRegex
            .find(ggScript)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return@withLock

        val commonId = ggBaseRegex
            .find(ggScript)
            ?.groupValues
            ?.get(1)
            ?: return@withLock

        imageSubdomainOffsetDefault = defaultOffset

        imageSubdomainOffsetMap.clear()

        ggCaseRegex
            .findAll(ggScript)
            .forEach {
                val imageId = it
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@forEach

                imageSubdomainOffsetMap[imageId] =
                    caseOffset
            }

        imageCommonId = commonId
        imageScriptLastRetrieval = now
    }

    private suspend fun getCachedGalleriesVersion(
        dataBase: String,
    ): String {
        val now = System.currentTimeMillis()
        val maxAge = 5 * 60 * 1000L

        if (
            cachedGalleriesVersionDataBase == dataBase &&
            cachedGalleriesVersion != null &&
            now - cachedGalleriesVersionAt < maxAge
        ) {
            return cachedGalleriesVersion.orEmpty()
        }

        val version = client
            .get(
                "$dataBase/galleriesindex/version"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter(
                        "_",
                        now.toString(),
                    )
                    .build(),
            )
            .body
            .string()
            .trim()

        if (version.isNotBlank()) {
            cachedGalleriesVersion = version
            cachedGalleriesVersionAt = now
            cachedGalleriesVersionDataBase = dataBase
        }

        return version
    }

    private fun extractGalleryId(url: String): String? = Regex("""-(\d+)\.html(?:#\d+)?$""")
        .find(url)
        ?.groupValues
        ?.get(1)
        ?: Regex("""/reader/(\d+)\.html""")
            .find(url)
            ?.groupValues
            ?.get(1)

    private fun parseGalleryInfo(script: String): org.json.JSONObject? {
        val start = script.indexOf('{')
        val end = script.lastIndexOf('}')

        if (start == -1 || end == -1 || end <= start) {
            return null
        }

        return runCatching {
            org.json.JSONObject(
                script.substring(start, end + 1),
            )
        }.getOrNull()
    }

    private fun parseGalleryDate(date: String): Long {
        if (date.isBlank()) return 0L

        return runCatching {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ssX",
                java.util.Locale.US,
            ).parse(date)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun jsonNames(
        galleryInfo: org.json.JSONObject,
        arrayName: String,
        valueName: String,
    ): List<String> {
        val array = galleryInfo.optJSONArray(arrayName)
            ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: continue

                val value = formatDisplayText(
                    item
                        .optString(valueName)
                        .trim(),
                )

                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }

    private fun jsonTags(
        galleryInfo: org.json.JSONObject,
    ): List<String> {
        val array = galleryInfo.optJSONArray("tags")
            ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: continue

                val tag = formatDisplayText(
                    item
                        .optString("tag")
                        .trim(),
                )

                if (tag.isBlank()) {
                    continue
                }

                val suffix = when {
                    item.optString("female").isNotBlank() -> " ♀"
                    item.optString("male").isNotBlank() -> " ♂"
                    else -> ""
                }

                add(tag + suffix)
            }
        }
    }

    private fun hashSegment(hash: String): Int {
        val lastThree = hash.takeLast(3)

        if (lastThree.length != 3) {
            return 0
        }

        val reordered = "${lastThree[2]}${lastThree.substring(0, 2)}"

        return reordered.toInt(16)
    }

    private var searchSnapshotKey: String? = null
    private var searchSnapshotIds: List<Int>? = null
    private var searchSnapshotDataBase: String? = null
    private val galleryIdRegex = Regex("""/reader/(\d+)\.html""")
    private val hashRegex = Regex(""""hash"\s*:\s*"([0-9a-f]+)"""")
    private val ggCaseRegex = Regex("""case\s+(\d+)\s*:""")
    private val ggBaseRegex = Regex("""\bb\s*:\s*['"]([^'"]+)['"]""")
    private val ggDefaultOffsetRegex = Regex("""var\s+o\s*=\s*(\d+)""")
    private val ggCaseOffsetRegex = Regex("""o\s*=\s*(\d+)\s*;\s*break\s*;""")
    private val readerHashRegex = Regex("""(?:^|&)hash=([0-9a-f]+)(?:&|$)""")
    private val readerGifRegex = Regex("""(?:^|&)gif=(true|false)(?:&|$)""")

    private val popularPath: String
        get() = when (lang) {
            "all" -> "/popular/year-all.html"
            "id" -> "/popular/year-indonesian.html"
            "jv" -> "/popular/year-javanese.html"
            "ca" -> "/popular/year-catalan.html"
            "ceb" -> "/popular/year-cebuano.html"
            "cs" -> "/popular/year-czech.html"
            "da" -> "/popular/year-danish.html"
            "de" -> "/popular/year-german.html"
            "et" -> "/popular/year-estonian.html"
            "en" -> "/popular/year-english.html"
            "es" -> "/popular/year-spanish.html"
            "eo" -> "/popular/year-esperanto.html"
            "fr" -> "/popular/year-french.html"
            "hi" -> "/popular/year-hindi.html"
            "is" -> "/popular/year-icelandic.html"
            "it" -> "/popular/year-italian.html"
            "km" -> "/popular/year-khmer.html"
            "la" -> "/popular/year-latin.html"
            "hu" -> "/popular/year-hungarian.html"
            "nl" -> "/popular/year-dutch.html"
            "no" -> "/popular/year-norwegian.html"
            "pl" -> "/popular/year-polish.html"
            "pt" -> "/popular/year-portuguese.html"
            "ro" -> "/popular/year-romanian.html"
            "sq" -> "/popular/year-albanian.html"
            "sk" -> "/popular/year-slovak.html"
            "sr" -> "/popular/year-serbian.html"
            "fi" -> "/popular/year-finnish.html"
            "sv" -> "/popular/year-swedish.html"
            "tl" -> "/popular/year-tagalog.html"
            "vi" -> "/popular/year-vietnamese.html"
            "tr" -> "/popular/year-turkish.html"
            "el" -> "/popular/year-greek.html"
            "bg" -> "/popular/year-bulgarian.html"
            "mn" -> "/popular/year-mongolian.html"
            "ru" -> "/popular/year-russian.html"
            "uk" -> "/popular/year-ukrainian.html"
            "he" -> "/popular/year-hebrew.html"
            "ar" -> "/popular/year-arabic.html"
            "fa" -> "/popular/year-persian.html"
            "th" -> "/popular/year-thai.html"
            "my" -> "/popular/year-burmese.html"
            "ko" -> "/popular/year-korean.html"
            "zh" -> "/popular/year-chinese.html"
            "ja" -> "/popular/year-japanese.html"
            else -> "/popular/year-all.html"
        }

    private val latestPath: String
        get() = when (lang) {
            "all" -> "/"
            "id" -> "/index-indonesian.html"
            "jv" -> "/index-javanese.html"
            "ca" -> "/index-catalan.html"
            "ceb" -> "/index-cebuano.html"
            "cs" -> "/index-czech.html"
            "da" -> "/index-danish.html"
            "de" -> "/index-german.html"
            "et" -> "/index-estonian.html"
            "en" -> "/index-english.html"
            "es" -> "/index-spanish.html"
            "eo" -> "/index-esperanto.html"
            "fr" -> "/index-french.html"
            "hi" -> "/index-hindi.html"
            "is" -> "/index-icelandic.html"
            "it" -> "/index-italian.html"
            "km" -> "/index-khmer.html"
            "la" -> "/index-latin.html"
            "hu" -> "/index-hungarian.html"
            "nl" -> "/index-dutch.html"
            "no" -> "/index-norwegian.html"
            "pl" -> "/index-polish.html"
            "pt" -> "/index-portuguese.html"
            "ro" -> "/index-romanian.html"
            "sq" -> "/index-albanian.html"
            "sk" -> "/index-slovak.html"
            "sr" -> "/index-serbian.html"
            "fi" -> "/index-finnish.html"
            "sv" -> "/index-swedish.html"
            "tl" -> "/index-tagalog.html"
            "vi" -> "/index-vietnamese.html"
            "tr" -> "/index-turkish.html"
            "el" -> "/index-greek.html"
            "bg" -> "/index-bulgarian.html"
            "mn" -> "/index-mongolian.html"
            "ru" -> "/index-russian.html"
            "uk" -> "/index-ukrainian.html"
            "he" -> "/index-hebrew.html"
            "ar" -> "/index-arabic.html"
            "fa" -> "/index-persian.html"
            "th" -> "/index-thai.html"
            "my" -> "/index-burmese.html"
            "ko" -> "/index-korean.html"
            "zh" -> "/index-chinese.html"
            "ja" -> "/index-japanese.html"
            else -> "/"
        }

    private val chapterLabel: String
        get() = when (lang) {
            "es" -> "Capítulo"
            "en" -> "Chapter"
            "pt" -> "Capítulo"
            "fr" -> "Chapitre"
            "de" -> "Kapitel"
            "it" -> "Capitolo"
            "ja" -> "章"
            "ko" -> "챕터"
            "zh" -> "章节"
            "ru" -> "Глава"
            else -> "Chapter"
        }
    private data class SearchNode(
        val keys: List<ByteArray>,
        val datas: List<Pair<Long, Int>>,
        val subnodeAddresses: List<Long>,
    )

    private val searchLanguage: String?
        get() = when (lang) {
            "all" -> null
            "id" -> "indonesian"
            "jv" -> "javanese"
            "ca" -> "catalan"
            "ceb" -> "cebuano"
            "cs" -> "czech"
            "da" -> "danish"
            "de" -> "german"
            "et" -> "estonian"
            "en" -> "english"
            "es" -> "spanish"
            "eo" -> "esperanto"
            "fr" -> "french"
            "hi" -> "hindi"
            "is" -> "icelandic"
            "it" -> "italian"
            "km" -> "khmer"
            "la" -> "latin"
            "hu" -> "hungarian"
            "nl" -> "dutch"
            "no" -> "norwegian"
            "pl" -> "polish"
            "pt" -> "portuguese"
            "ro" -> "romanian"
            "sq" -> "albanian"
            "sk" -> "slovak"
            "sr" -> "serbian"
            "fi" -> "finnish"
            "sv" -> "swedish"
            "tl" -> "tagalog"
            "vi" -> "vietnamese"
            "tr" -> "turkish"
            "el" -> "greek"
            "bg" -> "bulgarian"
            "mn" -> "mongolian"
            "ru" -> "russian"
            "uk" -> "ukrainian"
            "he" -> "hebrew"
            "ar" -> "arabic"
            "fa" -> "persian"
            "th" -> "thai"
            "my" -> "burmese"
            "ko" -> "korean"
            "zh" -> "chinese"
            "ja" -> "japanese"
            else -> null
        }

    private fun formatDisplayText(text: String): String = text
        .trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase()
                } else {
                    char.toString()
                }
            }
        }

    private suspend fun getNozomiGalleryIds(
        dataBase: String,
        language: String,
    ): List<Int> {
        val bytes = client
            .get("$dataBase/n/index-$language.nozomi")
            .body
            .bytes()

        if (bytes.size < 4) {
            return emptyList()
        }

        return buildList {
            var pos = 0
            while (pos + 4 <= bytes.size) {
                add(readInt32BE(bytes, pos))
                pos += 4
            }
        }
    }

    private suspend fun getGalleryIdsForPlainQuery(
        dataBase: String,
        version: String,
        term: String,
    ): List<Int> {
        val totalStart = System.currentTimeMillis()

        val termCacheKey = "$version|$term"

        searchTermCache[termCacheKey]?.let { cached ->
            println("RBK_PLAIN_CACHE_HIT[$term]=${cached.size}")
            println(
                "RBK_PLAIN_TOTAL_MS[$term]=" +
                    (System.currentTimeMillis() - totalStart),
            )
            return cached
        }

        val hashStart = System.currentTimeMillis()

        val key = MessageDigest
            .getInstance("SHA-256")
            .digest(term.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 4)

        println(
            "RBK_PLAIN_HASH_MS[$term]=" +
                (System.currentTimeMillis() - hashStart),
        )

        val indexUrl =
            "$dataBase/galleriesindex/galleries.$version.index"

        val dataUrl =
            "$dataBase/galleriesindex/galleries.$version.data"

        var nodeAddress = 0L
        var nodeReads = 0
        var nodeNetworkMs = 0L
        var nodeDecodeMs = 0L

        while (true) {
            nodeReads++

            val nodeRangeStart = System.currentTimeMillis()

            val nodeBytes = getCachedSearchNodeBytes(
                indexUrl = indexUrl,
                version = version,
                nodeAddress = nodeAddress,
            )

            val nodeRangeElapsed =
                System.currentTimeMillis() - nodeRangeStart

            nodeNetworkMs += nodeRangeElapsed

            println(
                "RBK_PLAIN_NODE_RANGE_MS[$term][$nodeReads]=" +
                    nodeRangeElapsed,
            )

            val decodeStart = System.currentTimeMillis()

            val node = decodeSearchNode(nodeBytes)
                ?: return emptyList()

            val decodeElapsed =
                System.currentTimeMillis() - decodeStart

            nodeDecodeMs += decodeElapsed

            println(
                "RBK_PLAIN_NODE_DECODE_MS[$term][$nodeReads]=" +
                    decodeElapsed,
            )

            if (node.keys.isEmpty()) {
                return emptyList()
            }

            val compareStart = System.currentTimeMillis()

            var where = node.keys.size
            var found = false

            for (i in node.keys.indices) {
                val comparison =
                    compareSearchKeys(key, node.keys[i])

                if (comparison <= 0) {
                    where = i
                    found = comparison == 0
                    break
                }
            }

            println(
                "RBK_PLAIN_COMPARE_MS[$term][$nodeReads]=" +
                    (System.currentTimeMillis() - compareStart),
            )

            if (found) {
                val (offset, length) = node.datas[where]

                if (
                    length <= 0 ||
                    length > 100_000_000
                ) {
                    return emptyList()
                }

                val dataRangeStart =
                    System.currentTimeMillis()

                val data = rangeGet(
                    url = dataUrl,
                    start = offset,
                    end = offset + length - 1,
                )

                val dataRangeElapsed =
                    System.currentTimeMillis() - dataRangeStart

                println(
                    "RBK_PLAIN_DATA_RANGE_MS[$term]=" +
                        dataRangeElapsed,
                )

                val galleryDecodeStart =
                    System.currentTimeMillis()

                val galleryIds =
                    decodeGalleryIds(data)

                println(
                    "RBK_PLAIN_GALLERY_DECODE_MS[$term]=" +
                        (
                            System.currentTimeMillis() -
                                galleryDecodeStart
                            ),
                )

                /*
                 * Guardamos solo listas pequeñas/medianas.
                 *
                 * Las búsquedas de palabras muy comunes pueden devolver cientos
                 * de miles o más de un millón de IDs y no queremos retener esos
                 * List<Int> en memoria.
                 */
                if (galleryIds.size <= 10_000) {
                    if (searchTermCache.size >= 256) {
                        searchTermCache.clear()
                    }

                    searchTermCache[termCacheKey] =
                        galleryIds
                }

                println(
                    "RBK_PLAIN_NODE_READS[$term]=$nodeReads",
                )
                println(
                    "RBK_PLAIN_NODE_NETWORK_MS[$term]=" +
                        nodeNetworkMs,
                )
                println(
                    "RBK_PLAIN_NODE_DECODE_TOTAL_MS[$term]=" +
                        nodeDecodeMs,
                )
                println(
                    "RBK_PLAIN_RESULT_COUNT[$term]=" +
                        galleryIds.size,
                )
                println(
                    "RBK_PLAIN_TOTAL_MS[$term]=" +
                        (
                            System.currentTimeMillis() -
                                totalStart
                            ),
                )

                return galleryIds
            }

            val isLeaf =
                node.subnodeAddresses.all {
                    it == 0L
                }

            if (
                isLeaf ||
                where >= node.subnodeAddresses.size
            ) {
                return emptyList()
            }

            val nextAddress =
                node.subnodeAddresses[where]

            if (nextAddress == 0L) {
                return emptyList()
            }

            nodeAddress = nextAddress
        }
    }

    private val searchNodeCache = ConcurrentHashMap<String, ByteArray>()
    private val searchNodeLocks = ConcurrentHashMap<String, Mutex>()

    private val searchTermCache = ConcurrentHashMap<String, List<Int>>()

    private suspend fun getCachedSearchNodeBytes(
        indexUrl: String,
        version: String,
        nodeAddress: Long,
    ): ByteArray {
        val cacheKey = "$version|$nodeAddress"

        searchNodeCache[cacheKey]?.let {
            return it
        }

        val mutex = searchNodeLocks.computeIfAbsent(cacheKey) {
            Mutex()
        }

        return mutex.withLock {
            searchNodeCache[cacheKey]?.let {
                return@withLock it
            }

            val bytes = rangeGet(
                url = indexUrl,
                start = nodeAddress,
                end = nodeAddress + 463,
            )

            /*
             * Los nodos son pequeños, por lo que podemos conservar bastantes.
             * Este límite evita crecimiento ilimitado si aparecen muchas
             * versiones distintas del índice durante la vida del proceso.
             */
            if (bytes.isNotEmpty()) {
                if (searchNodeCache.size >= 2048) {
                    searchNodeCache.clear()
                }

                searchNodeCache[cacheKey] = bytes
            }

            searchNodeLocks.remove(cacheKey, mutex)

            bytes
        }
    }

    private suspend fun rangeGet(
        url: String,
        start: Long,
        end: Long,
    ): ByteArray {
        val response = client.get(
            url,
            headers = headers.newBuilder()
                .set("Range", "bytes=$start-$end")
                .build(),
        )

        val bytes = response.body.bytes()
        val expectedSize = (end - start + 1).toInt()

        /*
         * El sitio normalmente responde 206. Si algún CDN ignora Range
         * y devuelve el archivo completo con 200, recortamos localmente.
         */
        if (
            response.code == 200 &&
            bytes.size > expectedSize &&
            start <= Int.MAX_VALUE &&
            end < bytes.size
        ) {
            return bytes.copyOfRange(
                start.toInt(),
                end.toInt() + 1,
            )
        }

        return bytes
    }

    private fun decodeSearchNode(data: ByteArray): SearchNode? {
        if (data.size < 4) {
            return null
        }

        var pos = 0

        val numberOfKeys = readInt32BE(data, pos)
        pos += 4

        if (numberOfKeys < 0 || numberOfKeys > 16) {
            return null
        }

        val keys = mutableListOf<ByteArray>()

        repeat(numberOfKeys) {
            if (pos + 4 > data.size) {
                return null
            }

            val keySize = readInt32BE(data, pos)
            pos += 4

            if (keySize <= 0 || keySize > 32 || pos + keySize > data.size) {
                return null
            }

            keys += data.copyOfRange(pos, pos + keySize)
            pos += keySize
        }

        if (pos + 4 > data.size) {
            return null
        }

        val numberOfDatas = readInt32BE(data, pos)
        pos += 4

        if (numberOfDatas < 0 || numberOfDatas > 16) {
            return null
        }

        val datas = mutableListOf<Pair<Long, Int>>()

        repeat(numberOfDatas) {
            if (pos + 12 > data.size) {
                return null
            }

            val offset = readInt64BE(data, pos)
            pos += 8

            val length = readInt32BE(data, pos)
            pos += 4

            datas += offset to length
        }

        val subnodes = mutableListOf<Long>()

        repeat(17) {
            if (pos + 8 > data.size) {
                return null
            }

            subnodes += readInt64BE(data, pos)
            pos += 8
        }

        return SearchNode(
            keys = keys,
            datas = datas,
            subnodeAddresses = subnodes,
        )
    }

    private fun decodeGalleryIds(data: ByteArray): List<Int> {
        if (data.size < 4) {
            return emptyList()
        }

        val count = readInt32BE(data, 0)

        if (count <= 0 || count > 10_000_000) {
            return emptyList()
        }

        val expectedSize = count * 4 + 4

        if (data.size != expectedSize) {
            return emptyList()
        }

        return buildList {
            var pos = 4

            repeat(count) {
                add(readInt32BE(data, pos))
                pos += 4
            }
        }
    }

    private fun compareSearchKeys(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val top = minOf(left.size, right.size)

        for (i in 0 until top) {
            val a = left[i].toInt() and 0xFF
            val b = right[i].toInt() and 0xFF

            if (a < b) return -1
            if (a > b) return 1
        }

        return 0
    }

    private fun readInt32BE(
        data: ByteArray,
        offset: Int,
    ): Int = (
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        )

    private fun readInt64BE(
        data: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L

        for (i in 0 until 8) {
            result = (result shl 8) or
                (data[offset + i].toLong() and 0xFF)
        }

        return result
    }

    private fun parseSearchBlock(
        html: String,
        documentBase: String,
    ): SManga? {
        val document = Jsoup.parse(
            html,
            documentBase,
        )

        val titleElement = document.selectFirst("h1.lillie a[href]")
            ?: return null

        val image = document.selectFirst(".dj-img1 img")
            ?: return null

        val title = titleElement
            .text()
            .trim()

        val href = titleElement
            .attr("href")
            .trim()

        val rawThumbnail = image
            .attr("data-src")
            .ifBlank { image.attr("src") }
            .trim()

        if (title.isBlank() || href.isBlank()) {
            return null
        }

        return SManga.create().apply {
            setUrlWithoutDomain(href)

            this.title = title

            thumbnail_url = normalizeThumbnailUrl(
                rawThumbnail,
            )
        }
    }

    private fun normalizeThumbnailUrl(
        url: String,
    ): String {
        if (url.isBlank()) {
            return ""
        }

        return when {
            url.startsWith("//tn.gold-usergeneratedcontent.net") ->
                "https:$url"

            url.startsWith("https://tn.gold-usergeneratedcontent.net") ->
                url

            url.startsWith("//tn.hitomi.la") ->
                url.replace(
                    "//tn.hitomi.la",
                    "https://tn.gold-usergeneratedcontent.net",
                )

            url.startsWith("https://tn.hitomi.la") ->
                url.replace(
                    "https://tn.hitomi.la",
                    "https://tn.gold-usergeneratedcontent.net",
                )

            url.startsWith("http://tn.hitomi.la") ->
                url.replace(
                    "http://tn.hitomi.la",
                    "https://tn.gold-usergeneratedcontent.net",
                )

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                "$baseUrl$url"

            else ->
                url
        }
    }

    private suspend fun getNozomiIdsForContentFilter(
        dataBase: String,
        contentFilter: Filters.ContentFilter,
        languageSuffix: String,
        sort: Filters.SortMode = Filters.SortMode.ADDED,
    ): List<Int> {
        val encodedValue =
            encodeNozomiPathValue(contentFilter.value)

        val path = when (sort) {
            Filters.SortMode.ADDED,
            Filters.SortMode.RANDOM,
            -> "/${contentFilter.category}/$encodedValue-$languageSuffix.nozomi"

            Filters.SortMode.PUBLISHED ->
                "/${contentFilter.category}/date/published/$encodedValue-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_TODAY ->
                "/${contentFilter.category}/popular/today/$encodedValue-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_WEEK ->
                "/${contentFilter.category}/popular/week/$encodedValue-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_MONTH ->
                "/${contentFilter.category}/popular/month/$encodedValue-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_YEAR ->
                "/${contentFilter.category}/popular/year/$encodedValue-$languageSuffix.nozomi"
        }

        return getNozomiIdsFromPath(
            dataBase = dataBase,
            path = path,
        )
    }

    private suspend fun getNozomiIdsForGlobalSort(
        dataBase: String,
        languageSuffix: String,
        sort: Filters.SortMode,
    ): List<Int> {
        val path = when (sort) {
            Filters.SortMode.ADDED,
            Filters.SortMode.RANDOM,
            -> "/index-$languageSuffix.nozomi"

            Filters.SortMode.PUBLISHED ->
                "/date/published-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_TODAY ->
                "/popular/today-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_WEEK ->
                "/popular/week-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_MONTH ->
                "/popular/month-$languageSuffix.nozomi"

            Filters.SortMode.POPULAR_YEAR ->
                "/popular/year-$languageSuffix.nozomi"
        }

        return getNozomiIdsFromPath(
            dataBase = dataBase,
            path = path,
        )
    }

    private suspend fun getNozomiIdsFromPath(
        dataBase: String,
        path: String,
    ): List<Int> {
        val response = client.get(
            "$dataBase$path",
        )

        if (!response.isSuccessful) {
            return emptyList()
        }

        val bytes = response
            .body
            .bytes()

        if (bytes.size < 4) {
            return emptyList()
        }

        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)

        return buildList {
            while (buffer.remaining() >= 4) {
                add(buffer.int)
            }
        }
    }

    private fun encodeNozomiPathValue(
        value: String,
    ): String = URLEncoder
        .encode(
            value,
            Charsets.UTF_8.name(),
        )
        .replace("+", "%20")
}
