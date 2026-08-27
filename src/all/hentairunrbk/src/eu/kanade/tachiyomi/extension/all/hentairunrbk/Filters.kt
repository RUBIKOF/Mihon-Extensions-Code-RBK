package eu.kanade.tachiyomi.extension.all.hentairunrbk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object Filters {

    data class Option(
        val label: String,
        val value: String,
    )

    data class SearchConfig(
        val sort: String,
        val categories: List<String>,
        val languages: List<String>,
    )

    private val categoryOptions = listOf(
        Option("Manga", "manga"),
        Option("Doujinshi", "doujinshi"),
        Option("Western", "western"),
        Option("Image Set", "image-set"),
        Option("Artist CG", "artist-cg"),
        Option("Game CG", "game-cg"),
    )

    private val languageOptions = listOf(
        Option("English", "english"),
        Option("Japanese", "japanese"),
        Option("Spanish", "spanish"),
        Option("French", "french"),
        Option("Korean", "korean"),
        Option("German", "german"),
        Option("Russian", "russian"),
    )

    class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf(
                "Latest",
                "Popular",
                "Top Rated",
                "Downloaded",
            ),
        )

    class CategoryCheckBox(
        name: String,
        val value: String,
    ) : Filter.CheckBox(name)

    class CategoryFilter :
        Filter.Group<CategoryCheckBox>(
            "Categories",
            categoryOptions.map { CategoryCheckBox(it.label, it.value) },
        )

    class LanguageCheckBox(
        name: String,
        val value: String,
    ) : Filter.CheckBox(name)

    class LanguageFilter :
        Filter.Group<LanguageCheckBox>(
            "Languages",
            languageOptions.map { LanguageCheckBox(it.label, it.value) },
        )

    fun getFilterList(sourceLang: String): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            Filter.Separator(),
            CategoryFilter(),
        )

        if (sourceLang == "all") {
            filters += Filter.Separator()
            filters += LanguageFilter()
        }

        return FilterList(filters)
    }

    fun parse(
        filters: FilterList,
        sourceLang: String,
    ): SearchConfig {
        val sort = filters
            .filterIsInstance<SortFilter>()
            .firstOrNull()
            ?.state
            ?.let {
                when (it) {
                    1 -> "popular"
                    2 -> "top-rated"
                    3 -> "downloaded"
                    else -> "latest"
                }
            }
            ?: "latest"

        val categories = filters
            .filterIsInstance<CategoryFilter>()
            .firstOrNull()
            ?.state
            .orEmpty()
            .filter { it.state }
            .map { it.value }

        val selectedLanguages = if (sourceLang == "all") {
            filters
                .filterIsInstance<LanguageFilter>()
                .firstOrNull()
                ?.state
                .orEmpty()
                .filter { it.state }
                .map { it.value }
        } else {
            listOfNotNull(languageFromSourceLang(sourceLang))
        }

        return SearchConfig(
            sort = sort,
            categories = categories,
            languages = selectedLanguages,
        )
    }

    fun languageFromSourceLang(lang: String): String? = when (lang) {
        "en" -> "english"
        "ja" -> "japanese"
        "es" -> "spanish"
        "fr" -> "french"
        "ko" -> "korean"
        "de" -> "german"
        "ru" -> "russian"
        else -> null
    }
}
