import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LectorHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all",
        "es",
        "en",
        "pt",
        "fr",
        "ru",
    ).forEach { sourceLang ->
        source {
            name = "LectorHentai"
            lang = sourceLang
            baseUrl = "https://lectorhentai.com"
        }
    }

    deeplink {
        path("/manga/..*")
    }
}
