package eu.kanade.tachiyomi.extension.all.naishorbk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object Filters {

    class CategoryCheckBox(
        name: String,
        val id: Int,
    ) : Filter.CheckBox(name)

    class CategoryFilter :
        Filter.Group<CategoryCheckBox>(
            "Category",
            CATEGORY_OPTIONS.map { (id, name) -> CategoryCheckBox(name, id) },
        )

    class IncludeTagsFilter : Filter.Text("Include tags")

    class ExcludeTagsFilter : Filter.Text("Exclude tags")

    class LanguageFilter :
        Filter.Select<String>(
            "Language",
            LANGUAGE_OPTIONS.map { it.first }.toTypedArray(),
        ) {
        val siteCode: String?
            get() = LANGUAGE_OPTIONS[state].second
    }

    class Config(
        val language: String?,
        val categories: List<Int>,
        val includedTags: List<String>,
        val excludedTags: List<String>,
    )

    fun getFilterList(sourceLang: String): FilterList {
        val items = mutableListOf<Filter<*>>()

        if (sourceLang == "all") {
            items += LanguageFilter()
            items += Filter.Separator()
        }

        items += CategoryFilter()
        items += Filter.Separator()
        items += IncludeTagsFilter()
        items += ExcludeTagsFilter()
        items += Filter.Header(
            "Tags: use type:tag. Separate multiple tags with commas. " +
                "Example: female:collar, parody:love live",
        )

        return FilterList(*items.toTypedArray())
    }

    fun parse(
        filters: FilterList,
        sourceLang: String,
        fixedSiteLang: String?,
    ): Config {
        val language = if (sourceLang == "all") {
            filters
                .filterIsInstance<LanguageFilter>()
                .firstOrNull()
                ?.siteCode
        } else {
            fixedSiteLang
        }

        val categories = filters
            .filterIsInstance<CategoryFilter>()
            .firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.id }
            .orEmpty()

        val includedTags = filters
            .filterIsInstance<IncludeTagsFilter>()
            .firstOrNull()
            ?.state
            .orEmpty()
            .toTagList()

        val excludedTags = filters
            .filterIsInstance<ExcludeTagsFilter>()
            .firstOrNull()
            ?.state
            .orEmpty()
            .toTagList()

        return Config(
            language = language,
            categories = categories,
            includedTags = includedTags,
            excludedTags = excludedTags,
        )
    }

    private fun String.toTagList(): List<String> = split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val LANGUAGE_OPTIONS = listOf(
        "All" to null,
        "Japanese" to "jp",
        "English" to "gb",
        "Korean" to "kr",
        "Russian" to "ru",
        "Chinese" to "cn",
        "French" to "fr",
        "Italian" to "it",
        "Spanish" to "es",
        "Portuguese" to "pt",
        "German" to "de",
        "Thai" to "th",
        "Arabic" to "sa",
        "Turkish" to "tr",
        "Hebrew" to "il",
        "Tagalog" to "ph",
        "Ukrainian" to "ua",
        "Bulgarian" to "bg",
        "Dutch" to "nl",
        "Mongolian" to "mn",
        "Vietnamese" to "vn",
        "Macedonian" to "mk",
        "Polish" to "pl",
        "Hungarian" to "hu",
        "Norwegian" to "no",
        "Indonesian" to "id",
        "Lithuanian" to "lt",
        "Serbian" to "rs",
        "Persian" to "ir",
        "Croatian" to "hr",
        "Czech" to "cz",
        "Slovak" to "sk",
        "Romanian" to "ro",
        "Finnish" to "fi",
        "Greek" to "gr",
        "Swedish" to "se",
        "Latin" to "va",
        "Albanian" to "al",
        "Burmese" to "mm",
        "Catalan" to "es-ct",
        "Danish" to "dk",
        "Estonian" to "ee",
        "Hindi" to "in",
        "Icelandic" to "is",
        "Javanese" to "jv",
        "Khmer" to "kh",
        "Slovenian" to "si",
    )

    private val CATEGORY_OPTIONS = listOf(
        1 to "Doujinshi",
        2 to "Manga",
        3 to "Artist CG",
        4 to "Game CG",
        5 to "Western",
        6 to "Non-H",
        7 to "Image Set",
        8 to "Cosplay",
        9 to "Misc",
        10 to "Asian Porn",
    )
}
