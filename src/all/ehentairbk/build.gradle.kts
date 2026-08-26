import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all",
        "en",
        "es",
        "ja",
        "zh",
        "fr",
        "de",
        "ko",
        "ru",
        "it",
        "pt",
    ).forEach { sourceLang ->
        source {
            name = "E-Hentai"
            lang = sourceLang
            baseUrl = "https://e-hentai.org"
        }
    }

    deeplink {
        path("/..*")
    }
}
