import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Porn Comic"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all", "en", "zh", "ja", "ko", "es", "ru", "it", "fr",
        "id", "ar", "pl", "pt", "tl", "vi", "uk", "th",
    ).forEach {
        source {
            name = "Porn Comic"
            lang = it
            baseUrl = "https://www.porn-comic.com"
        }
    }

    deeplink {
        path("/h/..*")
    }
}
