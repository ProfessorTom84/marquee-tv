package app.marquee.tv.news

import app.marquee.tv.data.SettingsStore
import app.marquee.tv.net.Http
import app.marquee.tv.net.urlEncode
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A news headline from a free RSS feed (no account or key). */
data class Headline(val title: String, val source: String, val link: String, val published: Instant?)

/** Where the Dashboard's headlines come from (Settings → Home style, or the phone setup page). */
enum class NewsSource(val label: String) {
    TOP("Top stories"),
    LOCAL("Near you"),
    BBC("BBC News"),
    NPR("NPR"),
    OFF("Off");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: TOP
    }
}

object NewsFeeds {
    /** Google News is regional (country code from settings); "Near you" searches it for your city. */
    fun url(source: NewsSource, region: String, city: String): String? {
        val r = region.uppercase().ifBlank { "US" }
        val google = "hl=en-$r&gl=$r&ceid=$r:en"
        return when (source) {
            NewsSource.TOP -> "https://news.google.com/rss?$google"
            NewsSource.LOCAL -> city.takeIf { it.isNotBlank() }?.let { "https://news.google.com/rss/search?q=${it.urlEncode()}&$google" }
            NewsSource.BBC -> "https://feeds.bbci.co.uk/news/rss.xml"
            NewsSource.NPR -> "https://feeds.npr.org/1001/rss.xml"
            NewsSource.OFF -> null
        }
    }

    /**
     * Reads an RSS feed's items. Handles Google News (title "Headline - Outlet" plus a <source>), BBC (CDATA) and
     * NPR (HTML entities). Plain string matching, no XML library, so it's tested on the JVM.
     */
    fun parse(xml: String, defaultSource: String): List<Headline> =
        Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            fun tag(name: String) = Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::clean)
            val source = tag("source")?.takeIf { it.isNotBlank() } ?: defaultSource
            var title = tag("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Google News appends " - Outlet" to every title; the outlet is shown separately.
            if (title.endsWith(" - $source")) title = title.removeSuffix(" - $source").trim()
            Headline(title, source, tag("link").orEmpty(), tag("pubDate")?.let(::date))
        }.toList()

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
    /** Up to [max] headlines, newest first; refreshed at most every 20 minutes (and kept across restarts). */
    suspend fun headlines(max: Int = 12): List<Headline> {
        val s = store.value
        val source = s.newsSource
        val url = NewsFeeds.url(source, s.region, s.cityName) ?: return emptyList()
        val xml = Http.getText(url, mapOf("User-Agent" to "Marquee TV launcher"), cacheMinutes = 20)
        return NewsFeeds.parse(xml, source.label).sortedByDescending { it.published ?: Instant.EPOCH }.take(max)
    }
}
