package eu.kanade.tachiyomi.extension.en.mangasee

import eu.kanade.tachiyomi.multisrc.nepnep.NepNep
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import rx.Observable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaSee : NepNep() {

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(1, 2.seconds)
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val id = when {
            query.startsWith("id:") -> query.substringAfter("id:")
            else -> query.toHttpUrlOrNull()
                ?.takeIf { it.host == "mangasee123.com" && it.pathSegments.firstOrNull() == "manga" }
                ?.pathSegments
                ?.getOrNull(1)
        }
        return if (id != null) {
            client.newCall(GET("$baseUrl/manga/$id"))
                .asObservableSuccess()
                .map { response ->
                    val manga = mangaDetailsParse(response)
                    manga.url = "/manga/$id"
                    MangasPage(listOf(manga), false)
                }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }
}
