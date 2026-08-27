import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiRun"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "en", "ja", "es", "fr", "ko", "de", "ru").forEach {
        source {
            name = "HentaiRun"
            lang = it
            baseUrl = "https://hentairun.com"
        }
    }

    deeplink {
        path("/gallery/..*")
        path("/view/..*")
    }
}
