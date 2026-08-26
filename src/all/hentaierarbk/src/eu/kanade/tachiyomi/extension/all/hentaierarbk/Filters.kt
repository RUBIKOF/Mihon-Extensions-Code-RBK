package eu.kanade.tachiyomi.extension.all.hentaierarbk

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Sort",
        SORT_VALUES.map { it.first }.toTypedArray(),
    ) {

    val selectedParam: String
        get() = SORT_VALUES[state].second

    companion object {
        private val SORT_VALUES = arrayOf(
            "Latest" to "lt",
            "Popular" to "pp",
            "Downloaded" to "dl",
            "Top Rated" to "tr",
        )
    }
}

class TypeCheckBox(
    name: String,
    val param: String,
) : Filter.CheckBox(name)

class TypeFilter :
    Filter.Group<TypeCheckBox>(
        "Type",
        listOf(
            TypeCheckBox("Manga", "mg"),
            TypeCheckBox("Doujinshi", "dj"),
            TypeCheckBox("Western", "ws"),
            TypeCheckBox("Image Set", "is"),
            TypeCheckBox("Artist CG", "ac"),
            TypeCheckBox("Game CG", "gc"),
        ),
    ) {

    val selectedParams: List<String>
        get() = state
            .filter { it.state }
            .map { it.param }
}

class LanguageCheckBox(
    name: String,
    val param: String,
) : Filter.CheckBox(name)

class LanguageFilter :
    Filter.Group<LanguageCheckBox>(
        "Language",
        listOf(
            LanguageCheckBox("English", "en"),
            LanguageCheckBox("Japanese", "jp"),
            LanguageCheckBox("Spanish", "es"),
            LanguageCheckBox("French", "fr"),
            LanguageCheckBox("Korean", "kr"),
            LanguageCheckBox("German", "de"),
            LanguageCheckBox("Russian", "ru"),
        ),
    ) {

    val selectedParams: List<String>
        get() = state
            .filter { it.state }
            .map { it.param }
}
