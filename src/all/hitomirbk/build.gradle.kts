import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HitomiRBK"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all", "id", "jv", "ca", "ceb", "cs", "da", "de", "et", "en",
        "es", "eo", "fr", "hi", "is", "it", "km", "la", "hu", "nl",
        "no", "pl", "pt", "ro", "sq", "sk", "sr", "fi", "sv", "tl",
        "vi", "tr", "el", "bg", "mn", "ru", "uk", "he", "ar", "fa",
        "th", "my", "ko", "zh", "ja",
    ).forEach { sourceLang ->
        source {
            name = "HitomiRBK"
            lang = sourceLang
            baseUrl = "https://hitomi.la"
        }
    }

    deeplink {
        path("/..*")
    }
}
