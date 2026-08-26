package eu.kanade.tachiyomi.extension.es.hmangarbk

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class HManga : KeiSource() {

    private val pageSize = 25

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * pageSize

        val url = "$baseUrl/wp-json/wordpress-popular-posts/v1/popular-posts"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("post_type", "post")
            .addQueryParameter("order_by", "views")
            // .addQueryParameter("range", "last24hours")
            .addQueryParameter("range", "last7days")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", offset.toString())
            .build()

        val response = client.get(url)
        val json = JSONArray(response.body.string())

        if (json.length() == 0) {
            return MangasPage(emptyList(), false)
        }

        val popularItems = buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)

                add(
                    PopularItem(
                        id = item.getInt("id"),
                        title = decodeHtml(item.getJSONObject("title").getString("rendered")),
                        url = item.getString("link"),
                    ),
                )
            }
        }

        val thumbnails = getThumbnails(popularItems.map { it.id })

        val mangas = popularItems.map { item ->
            SManga.create().apply {
                title = item.title
                setUrlWithoutDomain(item.url)
                thumbnail_url = thumbnails[item.id]
            }
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = mangas.size == pageSize,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("per_page", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orderby", "date")
            .addQueryParameter("order", "desc")
            .addQueryParameter("status", "publish")
            .addQueryParameter("_embed", "1")
            .build()

        val response = client.get(url)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
        val json = JSONArray(response.body.string())

        val mangas = buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)

                add(
                    SManga.create().apply {
                        title = decodeHtml(item.getJSONObject("title").getString("rendered"))
                        setUrlWithoutDomain(item.getString("link"))
                        thumbnail_url = extractThumbnail(item)
                    },
                )
            }
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = page < totalPages,
        )
    }

    private suspend fun getThumbnails(ids: List<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()

        val url = "$baseUrl/wp-json/wp/v2/posts"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("include", ids.joinToString(","))
            .addQueryParameter("per_page", ids.size.toString())
            .addQueryParameter("_embed", "1")
            .build()

        val response = client.get(url)
        val json = JSONArray(response.body.string())

        return buildMap {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val thumbnail = extractThumbnail(item)

                if (!thumbnail.isNullOrBlank()) {
                    put(item.getInt("id"), thumbnail)
                }
            }
        }
    }

    private fun extractThumbnail(item: JSONObject): String? {
        val embedded = item.optJSONObject("_embedded") ?: return null
        val mediaArray = embedded.optJSONArray("wp:featuredmedia") ?: return null

        if (mediaArray.length() == 0) return null

        val media = mediaArray.optJSONObject(0) ?: return null
        val sizes = media
            .optJSONObject("media_details")
            ?.optJSONObject("sizes")

        listOf("medium", "medium_large", "thumbnail", "full").forEach { sizeName ->
            val sourceUrl = sizes
                ?.optJSONObject(sizeName)
                ?.optString("source_url")
                .orEmpty()

            if (sourceUrl.isNotBlank()) {
                return sourceUrl
            }
        }

        return media
            .optString("source_url")
            .takeIf { it.isNotBlank() }
    }

    private fun decodeHtml(text: String): String = Jsoup.parse(text).text()

    private data class PopularItem(
        val id: Int,
        val title: String,
        val url: String,
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("per_page", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orderby", "relevance")
            .addQueryParameter("status", "publish")
            .addQueryParameter("_embed", "1")
            .build()

        val response = client.get(url)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
        val json = JSONArray(response.body.string())

        val mangas = buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)

                add(
                    SManga.create().apply {
                        title = decodeHtml(
                            item.getJSONObject("title").getString("rendered"),
                        )
                        setUrlWithoutDomain(item.getString("link"))
                        thumbnail_url = extractThumbnail(item)
                    },
                )
            }
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = page < totalPages,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val response = client.get(url)
        val document = Jsoup.parse(
            response.body.string(),
            url.toString(),
        )

        if (document.selectFirst("h1.entry-title") == null) {
            return null
        }

        return parseMangaDetails(document)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(document.location())

        title = document
            .selectFirst("h1.entry-title")
            ?.text()
            .orEmpty()

        author = document
            .select("a[rel=tag][href*=/autor/]")
            .eachText()
            .distinct()
            .joinToString(", ")

        genre = document
            .select("a[rel=tag][href*=/tags/]")
            .eachText()
            .distinct()
            .joinToString(", ")

        thumbnail_url = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        status = SManga.COMPLETED
        initialized = true
    }

    private fun parseChapter(
        document: Document,
    ): SChapter = SChapter.create().apply {
        name = "Capítulo"
        chapter_number = 1f
        date_upload = 0L
        setUrlWithoutDomain(document.location())
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
            listOf(
                parseChapter(document),
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
        val response = client.get(getChapterUrl(chapter))
        val html = response.body.string()

        val pagesBlock = pagesRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        return pageUrlRegex
            .findAll(pagesBlock)
            .mapIndexed { index, match ->
                Page(
                    index = index,
                    imageUrl = match.groupValues[1],
                )
            }
            .toList()
    }

    private companion object {
        val pagesRegex = Regex(
            """pages\s*=\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val pageUrlRegex = Regex(
            """["'](https?://[^"']+)["']""",
        )
    }
}
