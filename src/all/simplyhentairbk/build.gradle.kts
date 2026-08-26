import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Simply-Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "en",
        "fr",
        "it",
        "es",
        "pl",
        "ja",
        "de",
        "ru",
        "ko",
        "zh",
    ).forEach { sourceLang ->
        source {
            name = "Simply-Hentai"
            lang = sourceLang
            baseUrl = "https://www.simply-hentai.com"
        }
    }

    deeplink {
        path("/..*")
    }
}
