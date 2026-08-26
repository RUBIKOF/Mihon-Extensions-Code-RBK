import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiEnvy"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all",
        "en",
        "fr",
        "es",
        "ja",
        "de",
        "ru",
        "ko",
    ).forEach {
        source {
            name = "HentaiEnvy"
            lang = it
            baseUrl = "https://hentaienvy.com"
        }
    }

    deeplink {
        path("/gallery/..*")
    }
}
