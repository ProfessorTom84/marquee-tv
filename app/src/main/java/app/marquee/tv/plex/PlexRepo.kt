package app.marquee.tv.plex

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.bool
import app.marquee.tv.net.dbl
import app.marquee.tv.net.get
import app.marquee.tv.net.int
import app.marquee.tv.net.long
import app.marquee.tv.net.str
import app.marquee.tv.net.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PlexPin(val id: Long, val code: String)
data class PlexServer(val name: String, val machineId: String, val token: String, val uris: List<String>)

/**
 * Plex's official APIs: plex.tv for sign-in (PIN linking) and server discovery,
 * then the user's own Plex Media Server for library data, artwork and trailers.
 */
class PlexRepo(private val store: SettingsStore) {
    private val s get() = store.value

    private fun baseHeaders(token: String? = null) = buildMap {
        put("X-Plex-Product", "Marquee")
        put("X-Plex-Version", "0.1.0")
        put("X-Plex-Client-Identifier", s.plexClientId)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device", "Google TV")
        put("X-Plex-Device-Name", "Marquee Launcher")
        if (!token.isNullOrBlank()) put("X-Plex-Token", token)
    }

    // ---------- Sign-in ----------
    suspend fun createPin(): PlexPin {
        val j = Http.postForm("https://plex.tv/api/v2/pins", mapOf("strong" to "false"), baseHeaders())
        return PlexPin(j["id"].long ?: error("Plex did not return a PIN"), j["code"].str ?: "")
    }

    /** Returns the account token once the user has entered the code at plex.tv/link, or null while waiting. */
    suspend fun checkPin(pin: PlexPin): String? {
        val j = Http.get("https://plex.tv/api/v2/pins/${pin.id}", baseHeaders())
        return j["authToken"].str?.takeIf { it.isNotBlank() }
    }

    suspend fun servers(accountToken: String): List<PlexServer> {
        val j = Http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", baseHeaders(accountToken))
        return j.arr.filter { (it["provides"].str ?: "").contains("server") }.map { d ->
            val conns = d["connections"].arr.sortedWith(
                compareBy({ if (it["relay"].bool == true) 2 else if (it["local"].bool == true) 0 else 1 })
            ).mapNotNull { it["uri"].str }
            PlexServer(d["name"].str ?: "Plex", d["clientIdentifier"].str ?: "", d["accessToken"].str ?: accountToken, conns)
        }
    }

    /** Finds the first connection that answers, preferring the local network. */
    suspend fun pickConnection(server: PlexServer): String? = withContext(Dispatchers.IO) {
        val quick = Http.client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        server.uris.firstOrNull { uri ->
            runCatching {
                quick.newCall(Request.Builder().url("$uri/identity").header("X-Plex-Token", server.token).build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    suspend fun connect(accountToken: String): PlexServer? {
        val list = servers(accountToken)
        for (srv in list) {
            val uri = pickConnection(srv) ?: continue
            store.update {
                it.copy(
                    plexToken = accountToken, plexServerName = srv.name, plexServerUri = uri,
                    plexServerToken = srv.token, plexMachineId = srv.machineId,
                )
            }
            return srv
        }
        store.update { it.copy(plexToken = accountToken) }
        return null
    }

    fun signOut() = store.update {
        it.copy(plexToken = "", plexServerName = "", plexServerUri = "", plexServerToken = "", plexMachineId = "")
    }

    // ---------- Library ----------
    private suspend fun pms(path: String, cacheMinutes: Int = 5): JsonElement {
        val sep = if (path.contains('?')) '&' else '?'
        return Http.get("${s.plexServerUri}$path${sep}includeGuids=1", baseHeaders(s.plexServerToken), cacheMinutes)["MediaContainer"]
            ?: error("Empty Plex response")
    }

    data class Section(val key: String, val type: String, val title: String)

    suspend fun sections(): List<Section> = pms("/library/sections", 60)["Directory"].arr.mapNotNull {
        val type = it["type"].str ?: return@mapNotNull null
        if (type != "movie" && type != "show") null else Section(it["key"].str ?: "", type, it["title"].str ?: "")
    }

    private fun size(n: Int) = "X-Plex-Container-Start=0&X-Plex-Container-Size=$n"

    suspend fun unwatchedTopRated(limit: Int = 60): List<Title> = sections().flatMap { sec ->
        val type = if (sec.type == "movie") 1 else 2
        val filter = if (sec.type == "movie") "unwatched=1" else "unwatchedLeaves=1"
        runCatching { pms("/library/sections/${sec.key}/all?type=$type&$filter&sort=audienceRating:desc&${size(limit)}")["Metadata"].arr.map { map(it) } }
            .getOrDefault(emptyList())
    }

    suspend fun recentlyWatched(limit: Int = 30): List<Title> = sections().flatMap { sec ->
        val type = if (sec.type == "movie") 1 else 2
        runCatching { pms("/library/sections/${sec.key}/all?type=$type&sort=lastViewedAt:desc&viewCount>>=1&${size(limit)}")["Metadata"].arr.map { map(it) } }
            .getOrDefault(emptyList())
    }.filter { it.plexViewed }

    suspend fun continueWatching(): List<Title> {
        val hub = runCatching { pms("/hubs/continueWatching/items?${size(20)}", 1)["Metadata"].arr }.getOrDefault(emptyList())
        val items = hub.ifEmpty { runCatching { pms("/library/onDeck?${size(20)}", 1)["Metadata"].arr }.getOrDefault(emptyList()) }
        return items.map { map(it) }
    }

    suspend fun recentlyAdded(): List<Title> =
        runCatching { pms("/library/recentlyAdded?${size(30)}", 10)["Metadata"].arr.map { map(it) } }.getOrDefault(emptyList())
            .distinctBy { it.title }

    suspend fun search(q: String): List<Title> =
        runCatching { pms("/hubs/search?query=${q.urlEncode()}&limit=20", 1)["Hub"].arr.flatMap { it["Metadata"].arr } }
            .getOrDefault(emptyList()).filter { (it["type"].str ?: "") in setOf("movie", "show", "episode") }.map { map(it) }

    /** Unwatched titles, newest arrivals first (what you've just downloaded and haven't seen). */
    suspend fun unwatchedNewest(limit: Int = 40): List<Title> = sections().flatMap { sec ->
        val type = if (sec.type == "movie") 1 else 2
        val filter = if (sec.type == "movie") "unwatched=1" else "unwatchedLeaves=1"
        runCatching { pms("/library/sections/${sec.key}/all?type=$type&$filter&sort=addedAt:desc&${size(limit)}", 10)["Metadata"].arr.map { map(it) } }
            .getOrDefault(emptyList())
    }.sortedByDescending { it.addedAt }

    data class RelatedHub(val title: String, val items: List<Title>)

    /** Plex's own "related" rows for a title: similar titles, more with an actor, more from the director… */
    suspend fun related(ratingKey: String): List<RelatedHub> = runCatching {
        pms("/library/metadata/$ratingKey/related?count=20", 60)["Hub"].arr.mapNotNull { h ->
            val items = h["Metadata"].arr.map { map(it) }.filter { it.plexKey != ratingKey }
            val title = h["title"].str ?: return@mapNotNull null
            if (items.size < 2) null else RelatedHub(title, items)
        }
    }.getOrDefault(emptyList())

    data class Season(val key: String, val index: Int, val title: String, val episodes: Int, val watched: Int)
    data class Episode(
        val key: String, val season: Int, val index: Int, val title: String, val summary: String,
        val thumb: String?, val minutes: Int?, val progress: Float?, val watched: Boolean,
    )

    suspend fun seasons(showKey: String): List<Season> = runCatching {
        pms("/library/metadata/$showKey/children", 5)["Metadata"].arr.mapNotNull {
            val idx = it["index"].int ?: return@mapNotNull null
            Season(it["ratingKey"].str ?: return@mapNotNull null, idx, it["title"].str ?: "Season $idx", it["leafCount"].int ?: 0, it["viewedLeafCount"].int ?: 0)
        }.filter { it.index > 0 || it.episodes > 0 }
    }.getOrDefault(emptyList())

    suspend fun episodes(seasonKey: String): List<Episode> = runCatching {
        pms("/library/metadata/$seasonKey/children", 5)["Metadata"].arr.mapNotNull {
            val dur = it["duration"].long ?: 0; val off = it["viewOffset"].long ?: 0
            Episode(
                it["ratingKey"].str ?: return@mapNotNull null, it["parentIndex"].int ?: 0, it["index"].int ?: 0,
                it["title"].str ?: "", it["summary"].str ?: "", image(it["thumb"].str, 480, 270),
                (dur / 60000).toInt().takeIf { m -> m > 0 },
                if (dur > 0 && off > 0) off.toFloat() / dur else null,
                (it["viewCount"].int ?: 0) > 0,
            )
        }
    }.getOrDefault(emptyList())

    /** Full metadata: streams (HDR, Atmos), cast. */
    suspend fun details(ratingKey: String): Title? =
        runCatching { pms("/library/metadata/$ratingKey", 10)["Metadata"][0]?.let { map(it, detailed = true) } }.getOrNull()

    /** A directly playable trailer URL from the server's extras (local trailers or Plex's licensed trailers). */
    suspend fun trailerUrl(ratingKey: String): String? = runCatching {
        val extras = pms("/library/metadata/$ratingKey/extras", 60)["Metadata"].arr
        val trailer = extras.firstOrNull { (it["subtype"].str == "trailer") || it["extraType"].int == 1 } ?: extras.firstOrNull()
        val part = trailer?.get("Media")?.get(0)?.get("Part")?.get(0)?.get("key")?.str ?: return@runCatching null
        if (part.startsWith("http")) part else "${s.plexServerUri}$part?X-Plex-Token=${s.plexServerToken}"
    }.getOrNull()

    // ---------- Official trailers from Plex's own catalogue (ad-free, 1080p), for any movie or show ----------
    /** Plex catalogue ids that could be this title, best match first. */
    private suspend fun discoverIds(t: Title): List<String> {
        val exact = t.plexGuid?.substringAfterLast('/')
        if (s.plexToken.isBlank()) return listOfNotNull(exact)
        val types = if (t.mediaType == "tv") "tv" else "movies"
        val j = runCatching {
            Http.get("https://discover.provider.plex.tv/library/search?query=${t.title.urlEncode()}&limit=10&searchTypes=$types&searchProviders=discover&includeMetadata=1",
                baseHeaders(s.plexToken), cacheMinutes = 1440)
        }.getOrNull()
        val wantType = if (t.mediaType == "tv") "show" else "movie"
        val year = t.year.toIntOrNull()
        val hits = j["MediaContainer"]["SearchResults"].arr.flatMap { it["SearchResult"].arr }.mapNotNull { it["Metadata"] }
            .filter { m -> m["type"].str == wantType && (m["title"].str ?: "").equals(t.title, true) }
            .sortedBy { m -> if (year == null) 0 else kotlin.math.abs((m["year"].int ?: year) - year) }
            .mapNotNull { it["ratingKey"].str }
        return (listOfNotNull(exact) + hits).distinct().take(4)
    }

    /** A real trailer for any title Plex's catalogue knows, served by Plex with your account. */
    suspend fun officialTrailer(t: Title): String? {
        if (s.plexToken.isBlank()) return null
        trailerCache[t.id]?.let { return it.ifBlank { null } }
        for (id in discoverIds(t)) {
            val url = runCatching {
                val extras = Http.get("https://metadata.provider.plex.tv/library/metadata/$id/extras", baseHeaders(s.plexToken), cacheMinutes = 1440)["MediaContainer"]["Metadata"].arr
                val trailers = extras.filter { it["subtype"].str == "trailer" || it["extraType"].int == 1 }
                val pick = trailers.firstOrNull { !(it["title"].str ?: "").contains("teaser", true) } ?: trailers.firstOrNull() ?: return@runCatching null
                val media = pick["Media"].arr
                val m = media.firstOrNull { it["videoResolution"].str == "1080" } ?: media.firstOrNull()
                val part = m?.get("Part")?.get(0)?.get("key")?.str ?: return@runCatching null
                if (part.startsWith("http")) part else "https://metadata.provider.plex.tv$part?X-Plex-Token=${s.plexToken}"
            }.getOrNull()
            if (url != null) { trailerCache[t.id] = url; return url }
        }
        trailerCache[t.id] = ""
        return null
    }

    private val trailerCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class Hub(val id: String, val title: String, val items: List<Title>, val promoted: Boolean)

    private val playable = setOf("movie", "show", "episode", "season")

    private fun hubs(container: JsonElement): List<Hub> = container["Hub"].arr.mapNotNull { h ->
        val id = h["hubIdentifier"].str ?: return@mapNotNull null
        val items = h["Metadata"].arr.filter { (it["type"].str ?: "") in playable }.map { map(it) }.distinctBy { it.title }
        if (items.size < 4) null else Hub(id, h["title"].str ?: "", items, h["promoted"].bool == true)
    }

    /**
     * Plex's own rotating ribbons: the home hubs (including collections you promote with
     * "Manage Recommendations") and each library's hubs (Top Rated, Because you watched, More in a genre,
     * By a director, Rediscover…). Plex rotates these itself, so they change over time.
     */
    suspend fun rotatingHubs(): List<Hub> {
        val skip = listOf("continueWatching", "onDeck", "recentlyAdded", "playlists", "inProgress", "recentlyViewed")
        val home = runCatching { hubs(pms("/hubs?count=16", 30)) }.getOrDefault(emptyList())
        val perSection = sections().flatMap { sec -> runCatching { hubs(pms("/hubs/sections/${sec.key}?count=16", 30)) }.getOrDefault(emptyList()) }
        val skipTitles = listOf("recently added", "continue watching", "on deck", "playlists")
        return (home + perSection).filter { h -> skip.none { h.id.contains(it, true) } && skipTitles.none { h.title.contains(it, true) } }.distinctBy { it.title }
    }

    data class Collection(val key: String, val title: String, val art: String?, val count: Int)

    suspend fun collections(): List<Collection> = sections().flatMap { sec ->
        runCatching { pms("/library/sections/${sec.key}/collections", 120)["Metadata"].arr }.getOrDefault(emptyList()).map {
            Collection(it["ratingKey"].str ?: "", it["title"].str ?: "", image(it["art"].str ?: it["thumb"].str, 1280, 720), it["childCount"].int ?: 0)
        }
    }.filter { it.count >= 4 }

    suspend fun collectionItems(key: String): List<Title> =
        runCatching { pms("/library/collections/$key/children", 120)["Metadata"].arr.map { map(it) } }.getOrDefault(emptyList())

    /** Every movie and show in the library, for matching against TMDB lists (seasonal picks). Cached for hours. */
    suspend fun libraryIndex(): List<Title> = sections().flatMap { sec ->
        val type = if (sec.type == "movie") 1 else 2
        runCatching { pms("/library/sections/${sec.key}/all?type=$type", 360)["Metadata"].arr.map { map(it) } }.getOrDefault(emptyList())
    }

    /** Library items that match a TMDB/IMDb id, used to rank "your Plex copy" first. */
    fun guidIndex(items: List<Title>): Map<String, Title> = buildMap {
        items.forEach { t ->
            t.tmdbId?.let { put("tmdb:${t.mediaType}:$it", t) }
            t.imdbId?.let { put("imdb:$it", t) }
        }
    }

    fun image(path: String?, w: Int, h: Int): String? {
        if (path.isNullOrBlank() || s.plexServerUri.isBlank()) return null
        return "${s.plexServerUri}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${path.urlEncode()}&X-Plex-Token=${s.plexServerToken}"
    }

    private fun ratingLabel(value: Double?, image: String?, audience: Boolean = false): String? {
        value ?: return null
        val img = image ?: ""
        return when {
            img.contains("imdb") -> "IMDb ${"%.1f".format(value)}"
            img.contains("rottentomatoes") -> "${if (audience) "AUD" else "RT"} ${(value * 10).toInt()}%"
            img.contains("themoviedb") -> "TMDB ${"%.1f".format(value)}"
            else -> "★ ${"%.1f".format(value)}"
        }
    }

    private fun map(m: JsonElement, detailed: Boolean = false): Title {
        val type = m["type"].str ?: "movie"
        val isEpisode = type == "episode"
        val key = (if (isEpisode) m["ratingKey"].str else m["ratingKey"].str) ?: ""
        val guids = m["Guid"].arr.mapNotNull { it["id"].str }
        val tmdb = guids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull()
        val imdb = guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")
        val media = m["Media"][0]
        val quality = mutableListOf<String>()
        when (media["videoResolution"].str) {
            "4k" -> quality += "4K"
            "1080" -> quality += "1080p"
            "720" -> quality += "720p"
        }
        if (detailed) {
            val streams = media["Part"][0]["Stream"].arr
            val video = streams.firstOrNull { it["streamType"].int == 1 }
            val audio = streams.firstOrNull { it["streamType"].int == 2 }
            val vTitle = (video["displayTitle"].str ?: "") + " " + (video["DOVIPresent"].bool?.let { if (it) "Dolby Vision" else "" } ?: "")
            when {
                vTitle.contains("Dolby Vision", true) || video["DOVIPresent"].bool == true -> quality += "Dolby Vision"
                vTitle.contains("HDR", true) || (video["colorTrc"].str ?: "").contains("smpte2084") -> quality += "HDR10"
            }
            val aTitle = (audio["displayTitle"].str ?: "") + " " + (audio["extendedDisplayTitle"].str ?: "")
            if (aTitle.contains("Atmos", true)) quality += "Dolby Atmos" else if ((audio["channels"].int ?: 2) >= 6) quality += "5.1"
        } else {
            if ((media["audioChannels"].int ?: 2) >= 6 && quality.none { it == "5.1" }) quality += "5.1"
        }
        val durationMs = m["duration"].long ?: 0
        val offset = m["viewOffset"].long ?: 0
        val rating = m["audienceRating"].dbl ?: m["rating"].dbl
        val label = ratingLabel(m["audienceRating"].dbl, m["audienceRatingImage"].str, audience = true) ?: ratingLabel(m["rating"].dbl, m["ratingImage"].str)
        val showTitle = m["grandparentTitle"].str
        val name = if (isEpisode && showTitle != null) showTitle else m["title"].str ?: ""
        val epLabel = if (isEpisode) "S${m["parentIndex"].int ?: 0} E${m["index"].int ?: 0} · ${m["title"].str ?: ""}" else ""
        val art = m["art"].str ?: m["grandparentArt"].str
        val thumb = if (isEpisode) m["grandparentThumb"].str ?: m["thumb"].str else m["thumb"].str
        return Title(
            id = "plex:$key",
            plexGuid = (if (isEpisode) m["grandparentGuid"].str else m["guid"].str)?.takeIf { it.startsWith("plex://") },
            source = Source.PLEX,
            title = name,
            mediaType = if (type == "movie") "movie" else "tv",
            year = m["year"].int?.toString() ?: "",
            overview = if (isEpisode) "$epLabel\n${m["summary"].str ?: ""}".trim() else m["summary"].str ?: "",
            posterUrl = image(thumb, 360, 540),
            backdropUrl = image(art, 1920, 1080),
            logoUrl = m["Image"].arr.firstOrNull { it["type"].str == "clearLogo" }?.get("url")?.str?.let { image(it, 800, 300) },
            tmdbId = tmdb,
            imdbId = imdb,
            rating = rating,
            ratingLabel = label,
            runtimeMin = (durationMs / 60000).toInt().takeIf { it > 0 },
            genres = m["Genre"].arr.mapNotNull { it["tag"].str },
            availability = listOf(
                Availability(AvailKind.PLEX, s.plexServerName.ifBlank { "Plex" }, null, quality, score = 100 + quality.size * 5)
            ),
            plexKey = key,
            plexViewed = (m["viewCount"].int ?: 0) > 0 || (m["lastViewedAt"].long ?: 0) > 0,
            progress = if (durationMs > 0 && offset > 0) (offset.toFloat() / durationMs) else null,
            contentRating = m["contentRating"].str ?: "",
            cast = m["Role"].arr.take(6).mapNotNull { it["tag"].str },
            badge = if (epLabel.isNotBlank()) epLabel.substringBefore(" · ") else null,
            plexShowKey = if (isEpisode) m["grandparentRatingKey"].str else if (type == "show") key else null,
            addedAt = m["addedAt"].long ?: 0,
        )
    }
}
