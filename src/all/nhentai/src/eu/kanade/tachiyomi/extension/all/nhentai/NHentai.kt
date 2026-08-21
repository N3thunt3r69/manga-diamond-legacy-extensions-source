package eu.kanade.tachiyomi.extension.all.nhentai

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getNumPages
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Source
abstract class NHentai :
    HttpSource(),
    ConfigurableSource {

    private val nhLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(4)
            .build()
    }

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)
    }

    override fun latestUpdatesRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangasPage(response)

    override fun popularMangaRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page" else "$baseUrl/language/$nhLang/popular?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangasPage(response)

    private fun parseMangasPage(response: Response): MangasPage = parseMangasPage(response.asJsoup())

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select("#content .container:not(.index-popular) .gallery").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("#content > section.pagination > a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a > div").text().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = element.select(".cover img").first()!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val id = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> query.toHttpUrlOrNull()
                ?.takeIf { it.host == "nhentai.net" && it.pathSegments.firstOrNull() == "g" }
                ?.pathSegments
                ?.getOrNull(1)
        }
        return if (id != null) {
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val fixedQuery = query.ifEmpty { "\"\"" }
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "+$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val isOkayToSort = filterList.findInstance<UploadedFilter>()?.state?.isBlank() ?: true
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$baseUrl/favorites".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", "$fixedQuery $advQuery")
                .addQueryParameter("page", offsetPage.toString())

            return GET(url.toString(), headers)
        } else {
            val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", "$fixedQuery $nhLangSearch$advQuery")
                .addQueryParameter("page", offsetPage.toString())

            if (isOkayToSort) {
                filterList.findInstance<SortFilter>()?.let { f ->
                    url.addQueryParameter("sort", f.toUriPart())
                }
            }

            return GET(url.toString(), headers)
        }
    }

    private fun combineQuery(filters: FilterList): String {
        val stringBuilder = StringBuilder()
        val advSearch = filters.filterIsInstance<AdvSearchEntryFilter>().flatMap { filter ->
            val splitState = filter.state.split(",").map(String::trim).filterNot(String::isBlank)
            splitState.map {
                AdvSearchEntry(filter.name, it.removePrefix("-"), it.startsWith("-"))
            }
        }

        advSearch.forEach { entry ->
            if (entry.exclude) stringBuilder.append("-")
            stringBuilder.append("${entry.name}:")
            stringBuilder.append(entry.text)
            stringBuilder.append(" ")
        }

        return stringBuilder.toString()
    }

    data class AdvSearchEntry(val name: String, val text: String, val exclude: Boolean)

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/login/") && document.select(".fa-sign-in").isNotEmpty()) {
            throw Exception("Log in via WebView to view favorites")
        }
        return parseMangasPage(document)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document): SManga {
        val fullTitle = document.select("#info > h1").text().replace("\"", "").trim()

        return SManga.create().apply {
            title = if (displayFullTitle) fullTitle else fullTitle.shortenTitle()
            thumbnail_url = document.select("#cover > a > img").attr("data-src")
            status = SManga.COMPLETED
            artist = getArtists(document)
            author = artist
            // Some people want these additional details in description
            description = "Full English and Japanese titles:\n"
                .plus("$fullTitle\n")
                .plus("${document.select("div#info h2").text()}\n\n")
                .plus("Pages: ${getNumPages(document)}\n")
                .plus("Favorited by: ${document.select("div#info i.fa-heart + span span").text().removeSurrounding("(", ")")}\n")
                .plus(getTagDescription(document))
            genre = getTags(document)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = getGroups(document)
                date_upload = getTime(document)
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    private fun pageListParse(document: Document): List<Page> {
        val script = document.select("script:containsData(media_server)").first()!!.data()
        val mediaServer = Regex("""media_server\s*:\s*(\d+)""").find(script)?.groupValues!![1]

        return document.select("div.thumbs a > img").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:data-src").replace("t.nh", "i.nh").replace("t\\d+.nh".toRegex(), "i$mediaServer.nh").replace("t.", "."))
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}
