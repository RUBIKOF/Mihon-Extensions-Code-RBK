import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TMOHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "TMOHentai"
        lang = "es"
        baseUrl = "https://tmohentai.app"
        id = 6842739150264187391L
    }

    deeplink {
        path("/..*")
    }
}
