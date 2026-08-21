import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    versionCode = 39
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://nhentai.net"
    }
    source {
        lang = "ja"
        baseUrl = "https://nhentai.net"
    }
    source {
        lang = "zh"
        baseUrl = "https://nhentai.net"
    }
    source {
        lang = "all"
        baseUrl = "https://nhentai.net"
        id = 7309872737163460316L
    }

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}
