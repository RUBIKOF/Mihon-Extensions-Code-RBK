package eu.kanade.tachiyomi.extension.es.doujinhentairbk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object Filters {

    class TypeFilter :
        Filter.Select<String>(
            "Tipo",
            arrayOf(
                "Manga",
                "Comic",
                "Doujin",
            ),
        )

    class OrderFilter :
        Filter.Select<String>(
            "Orden",
            arrayOf(
                "Últimos agregados",
                "Más vistos",
            ),
        )

    class GenreModeFilter :
        Filter.CheckBox(
            "Filtrar por género",
            false,
        )

    class GenreFilter :
        Filter.Select<String>(
            "Género",
            genreOptions.map { it.first }.toTypedArray(),
        )

    data class Config(
        val typePath: String,
        val orderBy: String,
        val genreMode: Boolean,
        val genreSlug: String?,
    )

    fun getFilterList(): FilterList = FilterList(
        TypeFilter(),
        OrderFilter(),
        Filter.Separator(),
        Filter.Header(
            "Al activar el filtro por género, los filtros de Tipo y Orden dejarán de aplicarse.",
        ),
        GenreModeFilter(),
        GenreFilter(),
    )

    fun parse(filters: FilterList): Config {
        var typePath = "/lista-de-manga"
        var orderBy = "last"
        var genreMode = false
        var genreSlug: String? = null

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    typePath = when (filter.state) {
                        1 -> "/lista-de-comic"
                        2 -> "/lista-de-doujin"
                        else -> "/lista-de-manga"
                    }
                }

                is OrderFilter -> {
                    orderBy = when (filter.state) {
                        1 -> "views"
                        else -> "last"
                    }
                }

                is GenreModeFilter -> {
                    genreMode = filter.state
                }

                is GenreFilter -> {
                    genreSlug = genreOptions
                        .getOrNull(filter.state)
                        ?.second
                        ?.takeIf { it.isNotBlank() }
                }

                else -> Unit
            }
        }

        return Config(
            typePath = typePath,
            orderBy = orderBy,
            genreMode = genreMode,
            genreSlug = genreSlug,
        )
    }

    private val genreOptions = listOf(
        "Seleccionar género" to "",
        "Ahegao" to "ahegao",
        "Anal" to "anal",
        "Bikini" to "bikini",
        "Casadas" to "casadas",
        "Chica con Pene" to "chica-con-pene",
        "Cosplay" to "cosplay",
        "Doble Penetracion" to "doble-penetracion",
        "Doujinshi" to "doujinshi",
        "Ecchi" to "ecchi",
        "Embarazada" to "embarazada",
        "Enfermera" to "enfermera",
        "Escolares" to "escolares",
        "Full Color" to "full-colo",
        "Futanari" to "futanari",
        "Grandes pechos" to "grandes-pechos",
        "Harem" to "harem",
        "Incesto" to "incesto",
        "Interracial" to "interracial",
        "Juguetes Sexuales" to "juguetes-sexuales",
        "Lolicon" to "lolicon",
        "Maduras" to "maduras",
        "Mamadas" to "mamadas",
        "Manga" to "manga",
        "Masturbacion" to "masturbacion",
        "MILF" to "milf",
        "Orgias" to "orgias",
        "Profesores" to "profesores",
        "Romance" to "romance",
        "Shota" to "shota",
        "Sin Censura" to "sin-censura",
        "Sirvientas" to "sirvientas",
        "Tentaculos" to "tentaculos",
        "Tetonas" to "tetonas",
        "Virgenes" to "virgenes",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri",
    )
}
