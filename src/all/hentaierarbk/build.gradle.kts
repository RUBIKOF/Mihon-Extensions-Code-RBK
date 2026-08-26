import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiEra"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all",
        "en",
        "ja",
        "es",
        "fr",
        "ko",
        "de",
        "ru",
    ).forEach { sourceLang ->
        source {
            name = "HentaiEra"
            lang = sourceLang
            baseUrl = "https://hentaiera.com"
        }
    }

    deeplink {
        path("/gallery/..*")
    }
}
