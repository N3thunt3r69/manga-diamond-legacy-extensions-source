import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaSee"
    versionCode = 24
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    theme = "nepnep"

    source {
        lang = "en"
        baseUrl = "https://mangasee123.com"
        id = 9
    }

    deeplink {
        host("mangasee123.com")
        path("/manga/..*")
    }
}
