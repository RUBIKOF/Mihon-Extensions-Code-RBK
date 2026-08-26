import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SexKomix2"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "es",
        "en",
        "pt",
        "de",
        "ru",
    ).forEach { sourceLang ->
        source {
            name = "SexKomix2"
            lang = sourceLang
            baseUrl = "https://sexkomix2.com"
        }
    }
}
