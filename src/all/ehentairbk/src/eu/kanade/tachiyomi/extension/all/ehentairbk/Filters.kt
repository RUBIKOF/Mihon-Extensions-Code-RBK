package eu.kanade.tachiyomi.extension.all.ehentairbk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object Filters {

    private const val ALL_CATEGORY_MASK = 1023

    private val categories = arrayOf(
        Category("Misc", 1),
        Category("Doujinshi", 2),
        Category("Manga", 4),
        Category("Artist CG", 8),
        Category("Game CG", 16),
        Category("Image Set", 32),
        Category("Cosplay", 64),
        Category("Asian Porn", 128),
        Category("Non-H", 256),
        Category("Western", 512),
    )

    private val languages = arrayOf(
        Language("All", null),
        Language("English", "english"),
        Language("Español", "spanish"),
        Language("日本語", "japanese"),
        Language("中文", "chinese"),
        Language("Français", "french"),
        Language("Deutsch", "german"),
        Language("한국어", "korean"),
        Language("Русский", "russian"),
        Language("Italiano", "italian"),
        Language("Português", "portuguese"),
    )

    fun getFilterList(lang: String): FilterList {
        val result = mutableListOf<Filter<*>>(
            CategoryFilter(lang),
            Filter.Separator(),
            MinimumRatingFilter(lang),
            Filter.Separator(),
            Filter.Header(
                text(
                    lang,
                    es = "Número de páginas/imágenes",
                    en = "Number of pages/images",
                    pt = "Número de páginas/imagens",
                    fr = "Nombre de pages/images",
                    de = "Anzahl Seiten/Bilder",
                    it = "Numero di pagine/immagini",
                    ja = "ページ/画像数",
                    ko = "페이지/이미지 수",
                    zh = "页数/图片数",
                    ru = "Количество страниц/изображений",
                ),
            ),
            MinPagesFilter(lang),
            MaxPagesFilter(lang),
            Filter.Separator(),
            ShowExpungedFilter(lang),
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
    ): Config {
        val categoryFilter = filters
            .filterIsInstance<CategoryFilter>()
            .firstOrNull()

        val selectedMask = categoryFilter
            ?.state
            ?.filter { it.state }
            ?.fold(0) { acc, item ->
                acc or item.bit
            }
            ?: 0

        val disabledCategories = if (selectedMask == 0) {
            null
        } else {
            ALL_CATEGORY_MASK xor selectedMask
        }

        val rating = filters
            .filterIsInstance<MinimumRatingFilter>()
            .firstOrNull()
            ?.selectedValue

        val minPages = filters
            .filterIsInstance<MinPagesFilter>()
            .firstOrNull()
            ?.state
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        val maxPages = filters
            .filterIsInstance<MaxPagesFilter>()
            .firstOrNull()
            ?.state
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        val showExpunged = filters
            .filterIsInstance<ShowExpungedFilter>()
            .firstOrNull()
            ?.state
            ?: false

        val language = if (sourceLang == "all") {
            filters
                .filterIsInstance<LanguageFilter>()
                .firstOrNull()
                ?.selectedValue
        } else {
            null
        }

        return Config(
            disabledCategories = disabledCategories,
            rating = rating,
            minPages = minPages,
            maxPages = maxPages,
            showExpunged = showExpunged,
            language = language,
        )
    }

    class CategoryFilter(
        lang: String,
    ) : Filter.Group<CategoryCheckBox>(
        text(
            lang,
            es = "Categorías",
            en = "Categories",
            pt = "Categorias",
            fr = "Catégories",
            de = "Kategorien",
            it = "Categorie",
            ja = "カテゴリー",
            ko = "카테고리",
            zh = "分类",
            ru = "Категории",
        ),
        categories.map {
            CategoryCheckBox(
                name = it.name,
                bit = it.bit,
            )
        },
    )

    class CategoryCheckBox(
        name: String,
        val bit: Int,
    ) : Filter.CheckBox(
        name,
        false,
    )

    class MinimumRatingFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            es = "Rating mínimo",
            en = "Minimum rating",
            pt = "Avaliação mínima",
            fr = "Note minimale",
            de = "Mindestbewertung",
            it = "Valutazione minima",
            ja = "最低評価",
            ko = "최소 평점",
            zh = "最低评分",
            ru = "Минимальный рейтинг",
        ),
        arrayOf(
            text(
                lang,
                es = "Cualquiera",
                en = "Any",
                pt = "Qualquer",
                fr = "Tous",
                de = "Beliebig",
                it = "Qualsiasi",
                ja = "指定なし",
                ko = "전체",
                zh = "不限",
                ru = "Любой",
            ),
            "2 ★",
            "3 ★",
            "4 ★",
            "5 ★",
        ),
    ) {
        val selectedValue: Int?
            get() = when (state) {
                1 -> 2
                2 -> 3
                3 -> 4
                4 -> 5
                else -> null
            }
    }

    class MinPagesFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            es = "Mínimo",
            en = "Minimum",
            pt = "Mínimo",
            fr = "Minimum",
            de = "Minimum",
            it = "Minimo",
            ja = "最小",
            ko = "최소",
            zh = "最少",
            ru = "Минимум",
        ),
    )

    class MaxPagesFilter(
        lang: String,
    ) : Filter.Text(
        text(
            lang,
            es = "Máximo",
            en = "Maximum",
            pt = "Máximo",
            fr = "Maximum",
            de = "Maximum",
            it = "Massimo",
            ja = "最大",
            ko = "최대",
            zh = "最多",
            ru = "Максимум",
        ),
    )

    class ShowExpungedFilter(
        lang: String,
    ) : Filter.CheckBox(
        text(
            lang,
            es = "Mostrar galerías eliminadas",
            en = "Show expunged galleries",
            pt = "Mostrar galerias removidas",
            fr = "Afficher les galeries supprimées",
            de = "Gelöschte Galerien anzeigen",
            it = "Mostra gallerie eliminate",
            ja = "削除済みギャラリーを表示",
            ko = "삭제된 갤러리 표시",
            zh = "显示已删除画廊",
            ru = "Показывать удалённые галереи",
        ),
        false,
    )

    class LanguageFilter(
        lang: String,
    ) : Filter.Select<String>(
        text(
            lang,
            es = "Idioma",
            en = "Language",
            pt = "Idioma",
            fr = "Langue",
            de = "Sprache",
            it = "Lingua",
            ja = "言語",
            ko = "언어",
            zh = "语言",
            ru = "Язык",
        ),
        languages
            .map { it.label }
            .toTypedArray(),
    ) {
        val selectedValue: String?
            get() = languages[state].value
    }

    class Config(
        val disabledCategories: Int?,
        val rating: Int?,
        val minPages: Int?,
        val maxPages: Int?,
        val showExpunged: Boolean,
        val language: String?,
    )

    private class Category(
        val name: String,
        val bit: Int,
    )

    private class Language(
        val label: String,
        val value: String?,
    )

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
