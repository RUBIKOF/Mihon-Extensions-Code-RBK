package eu.kanade.tachiyomi.extension.es.tmohentairbk

import eu.kanade.tachiyomi.source.model.Filter

class SortBy :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf(
            "Más recientes",
            "Mejor valorados",
            "Alfabético",
        ),
    )

class ContentFilter :
    Filter.Select<String>(
        "Contenido",
        arrayOf(
            "Todo",
            "Yaoi",
            "Yuri",
            "Futanari",
            "Solo Fenemenino",
            "Solo Masculino",
            "Vanilla",
            "NTR/Netorare",
            "Uncensored",
        ),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "yaoi"
        2 -> "yuri"
        3 -> "futanari"
        4 -> "sole-female"
        5 -> "sole-male"
        6 -> "vanilla"
        7 -> "ntr"
        8 -> "uncensored"
        else -> ""
    }
}
