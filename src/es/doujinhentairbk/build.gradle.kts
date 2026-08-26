import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DoujinHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://doujinhentai.net"
        lang = "es"
    }

    deeplink {
        path("/..*")
    }
}
