package eu.kanade.tachiyomi.extension.all.hitomirbk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object Filters {

    enum class SortMode {
        ADDED,
        PUBLISHED,
        POPULAR_TODAY,
        POPULAR_WEEK,
        POPULAR_MONTH,
        POPULAR_YEAR,
        RANDOM,
    }

    data class ContentFilter(
        val category: String,
        val value: String,
    )

    data class SearchConfig(
        val sort: SortMode,
        val language: String?,
        val contentFilters: List<ContentFilter>,
    )

    data class TypeOption(
        val label: String,
        val value: String,
    )

    data class LanguageOption(
        val label: String,
        val value: String,
    )

    class SortFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            "Orden",
            "Sort",
            "Ordenar",
            "Tri",
            "Sortierung",
            "Ordine",
            "並び順",
            "정렬",
            "排序",
            "Сортировка",
        ),
        arrayOf(
            text(
                lang,
                "Fecha agregada",
                "Date added",
                "Data adicionada",
                "Date d'ajout",
                "Hinzugefügt",
                "Data aggiunta",
                "追加日",
                "추가 날짜",
                "添加日期",
                "Дата добавления",
            ),
            text(
                lang,
                "Fecha publicada",
                "Date published",
                "Data publicada",
                "Date de publication",
                "Veröffentlicht",
                "Data pubblicata",
                "公開日",
                "게시 날짜",
                "发布日期",
                "Дата публикации",
            ),
            text(
                lang,
                "Popular: Hoy",
                "Popular: Today",
                "Popular: Hoje",
                "Populaire : Aujourd'hui",
                "Beliebt: Heute",
                "Popolare: Oggi",
                "人気: 今日",
                "인기: 오늘",
                "热门：今日",
                "Популярное: Сегодня",
            ),
            text(
                lang,
                "Popular: Semana",
                "Popular: Week",
                "Popular: Semana",
                "Populaire : Semaine",
                "Beliebt: Woche",
                "Popolare: Settimana",
                "人気: 週間",
                "인기: 주간",
                "热门：本周",
                "Популярное: Неделя",
            ),
            text(
                lang,
                "Popular: Mes",
                "Popular: Month",
                "Popular: Mês",
                "Populaire : Mois",
                "Beliebt: Monat",
                "Popolare: Mese",
                "人気: 月間",
                "인기: 월간",
                "热门：本月",
                "Популярное: Месяц",
            ),
            text(
                lang,
                "Popular: Año",
                "Popular: Year",
                "Popular: Ano",
                "Populaire : Année",
                "Beliebt: Jahr",
                "Popolare: Anno",
                "人気: 年間",
                "인기: 연간",
                "热门：本年",
                "Популярное: Год",
            ),
            text(
                lang,
                "Aleatorio",
                "Random",
                "Aleatório",
                "Aléatoire",
                "Zufällig",
                "Casuale",
                "ランダム",
                "무작위",
                "随机",
                "Случайно",
            ),
        ),
    )

    class ArtistFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            "Artista",
            "Artist",
            "Artista",
            "Artiste",
            "Künstler",
            "Artista",
            "作者",
            "작가",
            "作者",
            "Автор",
        ),
    )

    class GroupFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            "Grupo",
            "Group",
            "Grupo",
            "Groupe",
            "Gruppe",
            "Gruppo",
            "グループ",
            "그룹",
            "组",
            "Группа",
        ),
    )

    class SeriesFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            "Serie",
            "Series",
            "Série",
            "Série",
            "Serie",
            "Serie",
            "シリーズ",
            "시리즈",
            "系列",
            "Серия",
        ),
    )

    class CharacterFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            "Personaje",
            "Character",
            "Personagem",
            "Personnage",
            "Charakter",
            "Personaggio",
            "キャラクター",
            "캐릭터",
            "角色",
            "Персонаж",
        ),
    )

    class TagFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            "Tag",
            "Tag",
            "Tag",
            "Tag",
            "Tag",
            "Tag",
            "タグ",
            "태그",
            "标签",
            "Тег",
        ),
    )

    class TagTypeFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            "Tipo de tag",
            "Tag type",
            "Tipo de tag",
            "Type de tag",
            "Tag-Typ",
            "Tipo di tag",
            "タグの種類",
            "태그 유형",
            "标签类型",
            "Тип тега",
        ),
        arrayOf(
            text(
                lang,
                "General",
                "General",
                "Geral",
                "Général",
                "Allgemein",
                "Generale",
                "一般",
                "일반",
                "常规",
                "Общий",
            ),
            text(
                lang,
                "Femenino",
                "Female",
                "Feminino",
                "Féminin",
                "Weiblich",
                "Femminile",
                "女性",
                "여성",
                "女性",
                "Женский",
            ),
            text(
                lang,
                "Masculino",
                "Male",
                "Masculino",
                "Masculin",
                "Männlich",
                "Maschile",
                "男性",
                "남성",
                "男性",
                "Мужской",
            ),
        ),
    )

    class TypeFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            "Tipo",
            "Type",
            "Tipo",
            "Type",
            "Typ",
            "Tipo",
            "タイプ",
            "유형",
            "类型",
            "Тип",
        ),
        typeLabels(lang),
    )

    class LanguageFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            "Idioma",
            "Language",
            "Idioma",
            "Langue",
            "Sprache",
            "Lingua",
            "言語",
            "언어",
            "语言",
            "Язык",
        ),
        languageLabels(lang),
    )

    fun getFilterList(lang: String): FilterList {
        val result = mutableListOf<Filter<*>>(
            SortFilter(lang),

            Filter.Separator(),

            ArtistFilter(lang),
            GroupFilter(lang),
            SeriesFilter(lang),
            CharacterFilter(lang),

            Filter.Separator(),

            TypeFilter(lang),

            Filter.Separator(),

            TagTypeFilter(lang),
            TagFilter(lang),
        )

        if (lang == "all") {
            result += Filter.Separator()
            result += LanguageFilter(lang)
        }

        return FilterList(result)
    }

    fun parse(
        filters: FilterList,
        sourceLang: String,
    ): SearchConfig {
        var sort = SortMode.ADDED
        var selectedLanguage: String? = null
        var tagType = 0

        val contentFilters = mutableListOf<ContentFilter>()

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    sort = when (filter.state) {
                        1 -> SortMode.PUBLISHED
                        2 -> SortMode.POPULAR_TODAY
                        3 -> SortMode.POPULAR_WEEK
                        4 -> SortMode.POPULAR_MONTH
                        5 -> SortMode.POPULAR_YEAR
                        6 -> SortMode.RANDOM
                        else -> SortMode.ADDED
                    }
                }

                is ArtistFilter -> {
                    filter.state
                        .normalized()
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            contentFilters += ContentFilter(
                                category = "artist",
                                value = it,
                            )
                        }
                }

                is GroupFilter -> {
                    filter.state
                        .normalized()
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            contentFilters += ContentFilter(
                                category = "group",
                                value = it,
                            )
                        }
                }

                is SeriesFilter -> {
                    filter.state
                        .normalized()
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            contentFilters += ContentFilter(
                                category = "series",
                                value = it,
                            )
                        }
                }

                is CharacterFilter -> {
                    filter.state
                        .normalized()
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            contentFilters += ContentFilter(
                                category = "character",
                                value = it,
                            )
                        }
                }

                is TagTypeFilter -> {
                    tagType = filter.state
                }

                is TypeFilter -> {
                    typeOptions
                        .getOrNull(filter.state - 1)
                        ?.let {
                            contentFilters += ContentFilter(
                                category = "type",
                                value = it.value,
                            )
                        }
                }

                is LanguageFilter -> {
                    if (sourceLang == "all") {
                        selectedLanguage = languageOptions
                            .getOrNull(filter.state - 1)
                            ?.value
                    }
                }

                else -> Unit
            }
        }

        filters
            .filterIsInstance<TagFilter>()
            .firstOrNull()
            ?.state
            ?.normalized()
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                contentFilters += when (tagType) {
                    1 -> ContentFilter(
                        category = "tag",
                        value = "female:$value",
                    )

                    2 -> ContentFilter(
                        category = "tag",
                        value = "male:$value",
                    )

                    else -> ContentFilter(
                        category = "tag",
                        value = value,
                    )
                }
            }

        return SearchConfig(
            sort = sort,
            language = if (sourceLang == "all") {
                selectedLanguage
            } else {
                languageFromSourceLang(sourceLang)
            },
            contentFilters = contentFilters,
        )
    }

    private fun String.normalized(): String = trim()
        .lowercase()
        .replace(
            Regex("\\s+"),
            "_",
        )

    private val typeOptions = listOf(
        TypeOption(
            label = "Anime",
            value = "anime",
        ),
        TypeOption(
            label = "Artist CG",
            value = "artistcg",
        ),
        TypeOption(
            label = "Doujinshi",
            value = "doujinshi",
        ),
        TypeOption(
            label = "Game CG",
            value = "gamecg",
        ),
        TypeOption(
            label = "Manga",
            value = "manga",
        ),
    )

    private fun typeLabels(lang: String): Array<String> = arrayOf(
        text(
            lang,
            "Cualquiera",
            "Any",
            "Qualquer",
            "Tous",
            "Beliebig",
            "Qualsiasi",
            "すべて",
            "전체",
            "任意",
            "Любой",
        ),
    ) + typeOptions
        .sortedBy { it.label.lowercase() }
        .map { it.label }
        .toTypedArray()

    private val languageOptions = listOf(
        LanguageOption("Albanian", "albanian"),
        LanguageOption("Arabic", "arabic"),
        LanguageOption("Bulgarian", "bulgarian"),
        LanguageOption("Burmese", "burmese"),
        LanguageOption("Catalan", "catalan"),
        LanguageOption("Cebuano", "cebuano"),
        LanguageOption("Chinese", "chinese"),
        LanguageOption("Czech", "czech"),
        LanguageOption("Danish", "danish"),
        LanguageOption("Dutch", "dutch"),
        LanguageOption("English", "english"),
        LanguageOption("Esperanto", "esperanto"),
        LanguageOption("Estonian", "estonian"),
        LanguageOption("Finnish", "finnish"),
        LanguageOption("French", "french"),
        LanguageOption("German", "german"),
        LanguageOption("Greek", "greek"),
        LanguageOption("Hebrew", "hebrew"),
        LanguageOption("Hindi", "hindi"),
        LanguageOption("Hungarian", "hungarian"),
        LanguageOption("Icelandic", "icelandic"),
        LanguageOption("Indonesian", "indonesian"),
        LanguageOption("Italian", "italian"),
        LanguageOption("Japanese", "japanese"),
        LanguageOption("Javanese", "javanese"),
        LanguageOption("Khmer", "khmer"),
        LanguageOption("Korean", "korean"),
        LanguageOption("Latin", "latin"),
        LanguageOption("Mongolian", "mongolian"),
        LanguageOption("Norwegian", "norwegian"),
        LanguageOption("Persian", "persian"),
        LanguageOption("Polish", "polish"),
        LanguageOption("Portuguese", "portuguese"),
        LanguageOption("Romanian", "romanian"),
        LanguageOption("Russian", "russian"),
        LanguageOption("Serbian", "serbian"),
        LanguageOption("Slovak", "slovak"),
        LanguageOption("Spanish", "spanish"),
        LanguageOption("Swedish", "swedish"),
        LanguageOption("Tagalog", "tagalog"),
        LanguageOption("Thai", "thai"),
        LanguageOption("Turkish", "turkish"),
        LanguageOption("Ukrainian", "ukrainian"),
        LanguageOption("Vietnamese", "vietnamese"),
    )

    private fun languageLabels(lang: String): Array<String> = arrayOf(
        text(
            lang,
            "Cualquiera",
            "Any",
            "Qualquer",
            "Tous",
            "Beliebig",
            "Qualsiasi",
            "すべて",
            "전체",
            "任意",
            "Любой",
        ),
    ) + languageOptions
        .sortedBy { it.label.lowercase() }
        .map { it.label }
        .toTypedArray()

    private fun languageFromSourceLang(lang: String): String? = when (lang) {
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

    private fun text(
        lang: String,
        es: String,
        en: String,
        pt: String,
        fr: String,
        de: String,
        it: String,
        ja: String,
        ko: String,
        zh: String,
        ru: String,
    ): String = when (lang) {
        "es" -> es
        "en" -> en
        "pt" -> pt
        "fr" -> fr
        "de" -> de
        "it" -> it
        "ja" -> ja
        "ko" -> ko
        "zh" -> zh
        "ru" -> ru
        else -> en
    }
}

