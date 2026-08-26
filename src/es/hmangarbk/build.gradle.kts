import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HManga"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://hmanga.asia"
        lang = "es"
    }

    deeplink {
        path("/..*")
    }
}
