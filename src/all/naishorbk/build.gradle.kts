import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Naisho"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    val languages = listOf(
        "all", "ja", "en", "ko", "ru", "zh", "fr", "it", "es", "pt",
        "de", "th", "ar", "tr", "he", "tl", "uk", "bg", "nl", "mn",
        "vi", "mk", "pl", "hu", "no", "id", "lt", "sr", "fa", "hr",
        "cs", "sk", "ro", "fi", "el", "sv", "la", "sq", "my", "ca",
        "da", "et", "hi", "is", "jv", "km", "sl",
    )

    languages.forEach {
        source {
            name = "Naisho"
            lang = it
            baseUrl = "https://naisho.moe"
        }
    }

    deeplink {
        path("/g/..*")
    }
}
