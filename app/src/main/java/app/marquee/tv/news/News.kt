package app.marquee.tv.news

import app.marquee.tv.data.Settings
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.net.Http
import app.marquee.tv.net.urlEncode
import app.marquee.tv.weather.WeatherRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A headline as it comes from a feed, before Marquee has checked that the story can be read on the TV. */
data class Headline(val title: String, val source: String, val link: String, val published: Instant?, val image: String? = null)

enum class NewsCategory(val label: String) {
    LOCAL("Local"), STATE("State"), NATIONAL("National"), POLITICS("Politics"), TECH("Tech"), BUSINESS("Business")
}

/** A story Marquee has already opened and read: it's only shown once that worked, so every one opens on the TV. */
@Serializable
data class Story(
    val category: NewsCategory, val title: String, val source: String, val link: String,
    val publishedMs: Long?, val image: String?, val summary: String,
) {
    val published get() = publishedMs?.let(Instant::ofEpochMilli)
}

/** One category's stories; [label] is the place for Local and State ("Brooklyn", "New York"). */
@Serializable
data class NewsSection(val category: NewsCategory, val label: String, val stories: List<Story>) {
    val title get() = if (category == NewsCategory.LOCAL || category == NewsCategory.STATE) "${category.label} · $label" else category.label
}

object NewsFeeds {
    /** Marquee always says what it is. A site that turns it away is simply never shown. */
    const val USER_AGENT = "Marquee TV launcher (news reader)"

    /** Feeds whose links go straight to the story (not to an aggregator's page), by category. */
    val national: Map<NewsCategory, List<Pair<String, String>>> = mapOf(
        NewsCategory.NATIONAL to listOf(
            "CBS News" to "https://www.cbsnews.com/latest/rss/us",
            "NBC News" to "https://feeds.nbcnews.com/nbcnews/public/us-news",
            "PBS NewsHour" to "https://www.pbs.org/newshour/feeds/rss/nation",
            "BBC News" to "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml",
        ),
        NewsCategory.POLITICS to listOf(
            "CBS News" to "https://www.cbsnews.com/latest/rss/politics",
            "NBC News" to "https://feeds.nbcnews.com/nbcnews/public/politics",
            "PBS NewsHour" to "https://www.pbs.org/newshour/feeds/rss/politics",
        ),
        NewsCategory.TECH to listOf(
            "Ars Technica" to "https://feeds.arstechnica.com/arstechnica/index",
            "TechCrunch" to "https://techcrunch.com/feed/",
            "BBC News" to "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "CBS News" to "https://www.cbsnews.com/latest/rss/technology",
            "NBC News" to "https://feeds.nbcnews.com/nbcnews/public/tech",
        ),
        NewsCategory.BUSINESS to listOf(
            "CBS News" to "https://www.cbsnews.com/latest/rss/moneywatch",
            "NBC News" to "https://feeds.nbcnews.com/nbcnews/public/business",
            "PBS NewsHour" to "https://www.pbs.org/newshour/feeds/rss/economy",
        ),
    )

    /** CBS's local newsrooms, each with its own local-news feed. */
    class Station(val slug: String, val name: String, val lat: Double, val lon: Double)
    val stations = listOf(
        Station("newyork", "CBS New York", 40.71, -74.01), Station("losangeles", "CBS Los Angeles", 34.05, -118.24),
        Station("chicago", "CBS Chicago", 41.88, -87.63), Station("philadelphia", "CBS Philadelphia", 39.95, -75.17),
        Station("texas", "CBS Texas", 32.78, -96.80), Station("sanfrancisco", "CBS Bay Area", 37.77, -122.42),
        Station("boston", "CBS Boston", 42.36, -71.06), Station("detroit", "CBS Detroit", 42.33, -83.05),
        Station("minnesota", "CBS Minnesota", 44.98, -93.27), Station("miami", "CBS Miami", 25.76, -80.19),
        Station("colorado", "CBS Colorado", 39.74, -104.99), Station("sacramento", "CBS Sacramento", 38.58, -121.49),
        Station("pittsburgh", "CBS Pittsburgh", 40.44, -80.00), Station("baltimore", "CBS Baltimore", 39.29, -76.61),
        Station("atlanta", "CBS Atlanta", 33.75, -84.39),
    )

    /** The local newsroom for where you are, if one is within about 150 km. */
    fun nearestStation(lat: Double, lon: Double, maxKm: Double = 150.0): Station? {
        if (lat.isNaN() || lon.isNaN()) return null
        fun km(s: Station): Double {
            val dLat = Math.toRadians(s.lat - lat); val dLon = Math.toRadians(s.lon - lon)
            val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(s.lat)) * Math.sin(dLon / 2).let { it * it }
            return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
        return stations.minByOrNull(::km)?.takeIf { km(it) <= maxKm }
    }

    /** A news search as a feed. Its links carry the story's own address (see [unwrap]). */
    fun search(query: String, region: String) =
        "https://www.bing.com/news/search?q=${query.urlEncode()}&format=rss&mkt=en-${region.uppercase().ifBlank { "US" }}"

    /** The feeds for a category, for this home. Local and State need your city (Settings → Weather). */
    fun feedsFor(cat: NewsCategory, s: Settings, state: String): List<Pair<String, String>> = when (cat) {
        NewsCategory.LOCAL -> if (s.cityName.isBlank()) emptyList() else listOfNotNull(
            nearestStation(s.latitude, s.longitude)?.let { it.name to "https://www.cbsnews.com/${it.slug}/latest/rss/local-news" },
            "Local news" to search(listOf(s.cityName, state).filter { it.isNotBlank() }.distinct().joinToString(" "), s.region),
        )
        NewsCategory.STATE -> if (state.isBlank()) emptyList() else listOf(
            "State news" to search("\"$state\" state news", s.region),
            "State news" to search("\"$state\" governor", s.region),
            "State news" to search("\"$state\" legislature", s.region),
        )
        else -> national[cat].orEmpty()
    }

    /** Pages that aren't a story to read: videos, rolling live updates, shopping guides. */
    private val notAStory = Regex("/(video|videos|watch|live|live-updates|live-news|live-blog|liveblog|select|shopping|gallery|photos|podcast|podcasts)/", RegexOption.IGNORE_CASE)
    fun isStoryLink(link: String) = link.startsWith("http") && !notAStory.containsMatchIn(link)

    /** Search-feed links wrap the story's address in a "url" parameter; everything else is returned as is. */
    fun unwrap(link: String): String {
        if (!link.contains("bing.com/news/apiclick")) return link
        val raw = Regex("[?&]url=([^&]+)").find(link)?.groupValues?.get(1) ?: return link
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(link)
    }

    /**
     * Reads an RSS feed's items: title, outlet, link, date and a picture if the feed gives one. Handles CDATA,
     * HTML entities, Google's " - Outlet" title suffix and search-feed links. Plain string matching, no XML
     * library, so it's tested on the JVM.
     */
    fun parse(xml: String, defaultSource: String): List<Headline> =
        Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            fun tag(name: String) = Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::clean)
            val source = (tag("News:Source") ?: tag("source"))?.takeIf { it.isNotBlank() } ?: defaultSource
            var title = tag("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (title.endsWith(" - $source")) title = title.removeSuffix(" - $source").trim()
            val link = unwrap(tag("link").orEmpty())
            Headline(title, source, link, tag("pubDate")?.let(::date), image(item))
        }.toList()

    /** A picture from the feed item: media:thumbnail / media:content, an image enclosure, or the search feed's image. */
    private fun image(item: String): String? {
        val media = Regex("<(?:media:thumbnail|media:content|enclosure)\\b([^>]*)>").findAll(item).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val url = Regex("\\burl=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)?.let(::decode) ?: return@mapNotNull null
            val type = Regex("\\b(?:type|medium)=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
            if (type.startsWith("video") || url.contains(".mp4") || url.contains(".m3u8")) null else url
        }.firstOrNull()
        return media ?: Regex("<News:Image>(.*?)</News:Image>").find(item)?.groupValues?.get(1)?.let(::clean)
    }

    /** Newest first, one story per subject: when several outlets cover the same thing, keep the first. */
    fun mix(lists: List<List<Headline>>, max: Int): List<Headline> {
        val seen = mutableListOf<Set<String>>()
        return lists.flatten().sortedByDescending { it.published ?: Instant.EPOCH }.filter { h ->
            val k = subject(h.title)
            val dup = seen.any { s -> sameSubject(k, s) }
            if (!dup) seen += k
            !dup
        }.take(max)
    }

    /** The distinctive words of a headline, to spot the same story told by two outlets. */
    fun subject(title: String) = title.lowercase().replace(Regex("[^a-z0-9 ]"), "").split(' ').filter { it.length > 3 }.take(6).toSet()
    fun sameSubject(a: Set<String>, b: Set<String>) = a.isNotEmpty() && (a intersect b).size >= 3

    private fun clean(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[").removeSuffix("]]>")
        t = t.replace(Regex("<[^>]+>"), "") // stray markup
        return decode(t).replace(Regex("\\s+"), " ").trim()
    }

    private fun decode(s: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }

    private fun date(s: String): Instant? = runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()

    /** "12 min ago", "3 h ago", "yesterday". */
    fun ago(t: Instant?, now: Instant = Instant.now()): String {
        t ?: return ""
        val min = java.time.Duration.between(t, now).toMinutes().coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}

class NewsRepo(private val store: SettingsStore) {
    suspend fun sections(): List<NewsSection> {
        val s = store.value
        return NewsBuilder.sections(s, stateFor(s))
    }

    /** Your state or region, from your city (looked up once and remembered). */
    private suspend fun stateFor(s: Settings): String {
        if (s.stateName.isNotBlank() || s.cityName.isBlank()) return s.stateName
        val found = runCatching {
            WeatherRepo.searchPlaces(s.cityName).minByOrNull { Math.abs(it.lat - s.latitude) + Math.abs(it.lon - s.longitude) }?.state
        }.getOrNull().orEmpty()
        if (found.isNotBlank()) store.update { it.copy(stateName = found) }
        return found
    }
}

/** Builds the news from the feeds (no Android needed, so it can be run and checked off the TV). */
object NewsBuilder {
    private val gate = Semaphore(4) // at most four stories being opened at once

    /**
     * The news, by category. Every story has already been opened and read in the background (and kept, so it
     * opens at once): anything a site won't serve, paywalls, video-only pages and aggregator pages never appear.
     */
    suspend fun sections(s: Settings, state: String, perCategory: Int = 8): List<NewsSection> = coroutineScope {
        val fresh = Instant.now().minusSeconds(4L * 24 * 3600)
        val candidates = NewsCategory.entries.map { cat ->
            async {
                val lists = NewsFeeds.feedsFor(cat, s, state).map { (label, url) ->
                    async { runCatching { NewsFeeds.parse(Http.getText(url, mapOf("User-Agent" to NewsFeeds.USER_AGENT), cacheMinutes = 20), label) }.getOrDefault(emptyList()) }
                }.awaitAll()
                cat to NewsFeeds.mix(lists, 40).filter { NewsFeeds.isStoryLink(it.link) && (it.published == null || it.published.isAfter(fresh)) }
            }
        }.awaitAll().toMap()

        // A story belongs to one row: the most specific category that has it keeps it.
        val claimed = mutableListOf<Set<String>>(); val links = mutableSetOf<String>()
        val assigned = listOf(NewsCategory.LOCAL, NewsCategory.STATE, NewsCategory.POLITICS, NewsCategory.TECH, NewsCategory.BUSINESS, NewsCategory.NATIONAL)
            .associateWith { cat ->
                candidates[cat].orEmpty().filter { h ->
                    val k = NewsFeeds.subject(h.title)
                    val dup = h.link in links || claimed.any { NewsFeeds.sameSubject(k, it) }
                    if (!dup) { links += h.link; claimed += k }
                    !dup
                }
            }

        NewsCategory.entries.map { cat ->
            async {
                val label = when (cat) { NewsCategory.LOCAL -> s.cityName; NewsCategory.STATE -> state; else -> cat.label }
                NewsSection(cat, label, readable(cat, assigned[cat].orEmpty(), perCategory))
            }
        }.awaitAll().filter { it.stories.isNotEmpty() }
    }

    /** Opens candidates, newest first, until [want] of them have read properly (trying at most twice that many). */
    private suspend fun readable(cat: NewsCategory, cands: List<Headline>, want: Int): List<Story> = coroutineScope {
        val out = mutableListOf<Story>()
        val limit = minOf(cands.size, want * 2)
        var i = 0
        while (out.size < want && i < limit) {
            val batch = cands.subList(i, minOf(i + (want - out.size), limit)); i += batch.size
            batch.map { h -> async { gate.withPermit { h to ArticleReader.read(h.link) } } }.awaitAll().forEach { (h, a) ->
                if (a != null) out += Story(
                    cat, h.title, h.source.takeIf { it != "Local news" && it != "State news" } ?: a.site, h.link, h.published?.toEpochMilli(),
                    a.heroImage ?: a.blocks.firstOrNull { it.kind == Article.Kind.IMAGE }?.url ?: h.image,
                    a.blocks.firstOrNull { it.kind == Article.Kind.PARA }?.text.orEmpty().take(260),
                )
            }
        }
        out
    }
}
