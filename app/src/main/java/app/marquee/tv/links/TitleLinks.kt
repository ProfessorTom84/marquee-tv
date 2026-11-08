package app.marquee.tv.links

import app.marquee.tv.data.SettingsStore
import app.marquee.tv.data.Title
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.get
import app.marquee.tv.net.str

/**
 * Links straight to a title inside a streaming app ("Play on Netflix" opens that show, not just Netflix), from
 * Watchmode (api.watchmode.com, optional key on the phone setup page). Looked up only when you press Play, and
 * kept for a week, so a free plan's monthly credits go a long way. Rentals, purchases and cable-login sources are
 * ignored, like everywhere else in Marquee.
 */
class TitleLinks(private val store: SettingsStore) {
    val ready get() = store.value.watchmodeKey.isNotBlank()

    /** Best-first links for this title on this service: Android TV link, then Android link, then web link. */
    suspend fun linksFor(t: Title, providerId: Int): List<String> {
        val key = store.value.watchmodeKey.trim()
        val tmdb = t.tmdbId ?: return emptyList()
        if (key.isBlank()) return emptyList()
        val id = (if (t.mediaType == "tv") "tv-" else "movie-") + tmdb
        val region = store.value.region.ifBlank { "US" }
        val list = Http.get("https://api.watchmode.com/v1/title/$id/sources/?apiKey=$key&regions=$region", cacheMinutes = 7 * 24 * 60)
        return list.arr
            .filter { it["type"].str in setOf("sub", "free") && providerFor(it["name"].str ?: "") == providerId }
            .flatMap { s -> listOfNotNull(s["android_tv_url"].str, s["android_url"].str, s["web_url"].str) }
            .filter { it.startsWith("http") || it.contains("://") }
            .distinct()
    }

    companion object {
        /** Watchmode's service names → Marquee's provider ids (TMDB's). */
        fun providerFor(watchmodeName: String): Int? {
            val n = watchmodeName.lowercase().filter { it.isLetterOrDigit() }
            // "Paramount+ Amazon Channel", "Starz Roku Channel"…: subscriptions sold through another app.
            if (n.endsWith("channel")) return null
            return when {
                n.startsWith("netflix") -> 8
                n.startsWith("primevideo") || n.startsWith("amazonprime") || n == "amazon" -> 9
                n.startsWith("disney") -> 337
                n.startsWith("hulu") -> 15
                n == "max" || n.startsWith("hbomax") -> 1899 // not "Max Amazon Channel", which plays in Prime Video
                n.startsWith("appletv") -> 350
                n.startsWith("paramount") -> 531
                n.startsWith("peacock") -> 386
                n.startsWith("starz") -> 43
                n.startsWith("crunchyroll") -> 283
                n.startsWith("kanopy") -> 191
                else -> null
            }
        }

        /**
         * Netflix's TV app opens a title from its watch link (the same number as the title link) and starts it,
         * which is what "Play on Netflix" means. Other services' links are used as given.
         */
        fun forTv(url: String): String =
            Regex("""https?://(www\.)?netflix\.com/(?:[a-z-]+/)?title/(\d+)""").find(url)?.let { "https://www.netflix.com/watch/${it.groupValues[2]}" } ?: url
    }
}
