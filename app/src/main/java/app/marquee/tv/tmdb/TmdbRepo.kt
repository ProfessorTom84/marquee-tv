package app.marquee.tv.tmdb

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Providers
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.dbl
import app.marquee.tv.net.get
import app.marquee.tv.net.int
import app.marquee.tv.net.str
import app.marquee.tv.net.urlEncode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

data class WatchInfo(val included: List<Int>, val free: List<Int>)

data class Person(val id: Int, val name: String, val role: String, val photoUrl: String?)
data class ExtraInfo(
    val trailerYoutubeKey: String?, val logoUrl: String?, val runtime: Int?, val cast: List<String>, val imdbId: String?,
    val genres: List<String>, val certification: String,
    val people: List<Person> = emptyList(), val director: Person? = null, val seasons: Int? = null,
)

/**
 * TMDB: metadata, artwork, trending, and "where to watch" (data from JustWatch).
 * Only flatrate (subscription) and free availability is ever requested: rent and buy are ignored entirely.
 */
class TmdbRepo(private val store: SettingsStore) {
    private val key get() = store.value.tmdbKey.trim()
    private val region get() = store.value.region
    val ready get() = key.isNotBlank()

    private suspend fun api(path: String, cacheMinutes: Int = 60): JsonElement {
        val bearer = key.startsWith("eyJ")
        val sep = if (path.contains('?')) '&' else '?'
        val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "${sep}api_key=$key"
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        return Http.get(url, headers, cacheMinutes)
    }

    companion object {
        const val IMG = "https://image.tmdb.org/t/p/"
        val genreNames = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 53 to "Thriller", 10752 to "War", 37 to "Western",
            10759 to "Action & Adventure", 10762 to "Kids", 10765 to "Sci-Fi & Fantasy", 10768 to "War & Politics", 10764 to "Reality",
        )
        // Movie genre id -> closest TV genre id, so one filter can cover both.
        val tvGenreFor = mapOf(28 to 10759, 12 to 10759, 878 to 10765, 14 to 10765, 10752 to 10768)
    }

    fun map(j: JsonElement, forcedType: String? = null): Title? {
        val type = forcedType ?: j["media_type"].str ?: "movie"
        if (type != "movie" && type != "tv") return null
        val id = j["id"].int ?: return null
        val name = j["title"].str ?: j["name"].str ?: return null
        val vote = j["vote_average"].dbl
        val genreIds = j["genre_ids"].arr.mapNotNull { it.int }
        return Title(
            id = "tmdb:$type:$id",
            source = Source.STREAMING,
            title = name,
            mediaType = type,
            year = (j["release_date"].str ?: j["first_air_date"].str ?: "").take(4),
            overview = j["overview"].str ?: "",
            posterUrl = j["poster_path"].str?.let { IMG + "w500" + it },
            backdropUrl = j["backdrop_path"].str?.let { IMG + "w1280" + it },
            tmdbId = id,
            rating = vote,
            ratingLabel = vote?.takeIf { it > 0 }?.let { "TMDB ${"%.1f".format(it)}" },
            genreIds = genreIds,
            genres = genreIds.mapNotNull { genreNames[it] },
            popularity = j["popularity"].dbl ?: 0.0,
        )
    }

    suspend fun trending(pages: Int = 2): List<Title> = coroutineScope {
        (1..pages).map { p -> async { runCatching { api("/trending/all/week?page=$p", 60)["results"].arr.mapNotNull { map(it) } }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().mapIndexed { i, t -> t.copy(trendRank = i + 1) }
    }

    /** Titles included with the given services. */
    suspend fun discover(
        type: String,
        providers: Set<Int>,
        genres: List<Int> = emptyList(),
        maxRuntime: Int? = null,
        sort: String = "popularity.desc",
        minVotes: Int = 300,
        page: Int = 1,
    ): List<Title> {
        if (providers.isEmpty()) return emptyList()
        val g = if (genres.isEmpty()) "" else "&with_genres=" + genres.map { if (type == "tv") tvGenreFor[it] ?: it else it }.distinct().joinToString("|")
        val rt = maxRuntime?.let { "&with_runtime.lte=$it" } ?: ""
        val path = "/discover/$type?watch_region=$region&with_watch_providers=${providers.joinToString("|")}" +
            "&with_watch_monetization_types=flatrate|free&sort_by=$sort&vote_count.gte=$minVotes$g$rt&page=$page&include_adult=false"
        return runCatching { api(path, 60)["results"].arr.mapNotNull { map(it, type) } }.getOrDefault(emptyList())
    }

    /** Resolves a keyword name ("halloween") to TMDB keyword ids. Cached for a month. */
    suspend fun keywordIds(name: String): List<Int> = runCatching {
        api("/search/keyword?query=${name.urlEncode()}", 43_200)["results"].arr
            .filter { (it["name"].str ?: "").equals(name, true) }.mapNotNull { it["id"].int }
    }.getOrDefault(emptyList())

    /** Titles tagged with any of the keywords. providers = null means anywhere (used to match your Plex library). */
    suspend fun byKeywords(type: String, keywordIds: List<Int>, providers: Set<Int>?, genres: List<Int> = emptyList(), pages: Int = 1, minVotes: Int = 80): List<Title> {
        if (keywordIds.isEmpty()) return emptyList()
        if (providers != null && providers.isEmpty()) return emptyList()
        val prov = providers?.let { "&watch_region=$region&with_watch_providers=${it.joinToString("|")}&with_watch_monetization_types=flatrate|free" } ?: ""
        val g = if (genres.isEmpty()) "" else "&with_genres=" + genres.joinToString("|")
        return (1..pages).flatMap { p ->
            runCatching {
                api("/discover/$type?with_keywords=${keywordIds.joinToString("|")}$prov$g&sort_by=popularity.desc&vote_count.gte=$minVotes&include_adult=false&page=$p", 360)["results"].arr.mapNotNull { map(it, type) }
            }.getOrDefault(emptyList())
        }
    }

    /** Recently released titles that are already included with your services. */
    suspend fun newOnServices(type: String, providers: Set<Int>): List<Title> {
        if (providers.isEmpty()) return emptyList()
        val today = java.time.LocalDate.now()
        val (gte, lte) = if (type == "movie") "primary_release_date.gte" to "primary_release_date.lte" else "first_air_date.gte" to "first_air_date.lte"
        val path = "/discover/$type?watch_region=$region&with_watch_providers=${providers.joinToString("|")}&with_watch_monetization_types=flatrate" +
            "&$gte=${today.minusDays(120)}&$lte=$today&sort_by=popularity.desc&vote_count.gte=40&include_adult=false"
        return runCatching { api(path, 180)["results"].arr.mapNotNull { map(it, type) } }.getOrDefault(emptyList())
    }

    suspend fun watchInfo(type: String, id: Int): WatchInfo {
        val r = runCatching { api("/$type/$id/watch/providers", 360)["results"][region] }.getOrNull()
        fun ids(k: String) = r[k].arr.mapNotNull { it["provider_id"].int?.let(Providers::canonical) }.distinct()
        return WatchInfo(included = ids("flatrate"), free = ids("free"))
    }

    /** Converts provider info into availability the household can use, and "elsewhere" names otherwise. */
    fun availabilityFor(info: WatchInfo, services: Set<Int>): Pair<List<Availability>, List<String>> {
        val mine = buildList {
            info.included.filter { it in services }.forEach { add(Availability(AvailKind.INCLUDED, Providers.name(it) ?: "Service", it, score = 50)) }
            // "Free" on TMDB usually means a free tier with ads, so only count it for services you've chosen.
            info.free.filter { it in services && it !in info.included }.forEach { id -> Providers.name(id)?.let { add(Availability(AvailKind.INCLUDED, it, id, score = 45)) } }
        }
        val elsewhere = info.included.filter { it !in services }.mapNotNull { Providers.name(it) }
        return mine to elsewhere
    }

    suspend fun extras(type: String, id: Int): ExtraInfo {
        val j = api("/$type/$id?append_to_response=videos,images,credits,external_ids,release_dates,content_ratings&include_image_language=en,null", 720)
        val vids = j["videos"]["results"].arr.filter { it["site"].str == "YouTube" }
        val trailer = (vids.firstOrNull { it["type"].str == "Trailer" && it["official"].str == "true" }
            ?: vids.firstOrNull { it["type"].str == "Trailer" } ?: vids.firstOrNull())?.get("key")?.str
        val logo = j["images"]["logos"].arr.firstOrNull { it["iso_639_1"].str == "en" }?.get("file_path")?.str
            ?: j["images"]["logos"].arr.firstOrNull()?.get("file_path")?.str
        val runtime = j["runtime"].int ?: j["episode_run_time"][0].int
        val cert = if (type == "movie") {
            j["release_dates"]["results"].arr.firstOrNull { it["iso_3166_1"].str == region }?.get("release_dates")?.arr
                ?.firstNotNullOfOrNull { it["certification"].str?.takeIf { c -> c.isNotBlank() } } ?: ""
        } else {
            j["content_ratings"]["results"].arr.firstOrNull { it["iso_3166_1"].str == region }?.get("rating")?.str ?: ""
        }
        return ExtraInfo(
            trailerYoutubeKey = trailer,
            logoUrl = logo?.let { IMG + "w500" + it.replace(".svg", ".png") },
            runtime = runtime,
            cast = j["credits"]["cast"].arr.take(6).mapNotNull { it["name"].str },
            imdbId = j["external_ids"]["imdb_id"].str ?: j["imdb_id"].str,
            genres = j["genres"].arr.mapNotNull { it["name"].str },
            certification = cert,
            people = j["credits"]["cast"].arr.take(12).mapNotNull { c ->
                Person(c["id"].int ?: return@mapNotNull null, c["name"].str ?: return@mapNotNull null, c["character"].str ?: "",
                    c["profile_path"].str?.let { IMG + "w185" + it })
            },
            director = (j["credits"]["crew"].arr.firstOrNull { it["job"].str == "Director" } ?: j["created_by"][0])?.let { c ->
                c["id"].int?.let { id -> Person(id, c["name"].str ?: "", if (type == "tv") "Creator" else "Director", c["profile_path"].str?.let { IMG + "w185" + it }) }
            },
            seasons = j["number_of_seasons"].int,
        )
    }

    /** Extra stills for card previews when there's no ad-free trailer. */
    suspend fun backdrops(type: String, id: Int): List<String> = runCatching {
        api("/$type/$id/images?include_image_language=null", 1440)["backdrops"].arr
            .sortedByDescending { it["vote_average"].dbl ?: 0.0 }.take(6).mapNotNull { it["file_path"].str?.let { p -> IMG + "w1280" + p } }
    }.getOrDefault(emptyList())

    suspend fun recommendations(type: String, id: Int): List<Title> =
        runCatching { api("/$type/$id/recommendations", 720)["results"].arr.mapNotNull { map(it, type) } }.getOrDefault(emptyList())

    suspend fun similar(type: String, id: Int): List<Title> =
        runCatching { api("/$type/$id/similar", 720)["results"].arr.mapNotNull { map(it, type) } }.getOrDefault(emptyList())

    /** Someone's best-known films and shows, most popular first. */
    suspend fun personCredits(personId: Int, crew: Boolean = false): List<Title> = runCatching {
        val j = api("/person/$personId/combined_credits", 1440)
        (if (crew) j["crew"].arr.filter { it["job"].str == "Director" || it["job"].str == "Creator" } else j["cast"].arr)
            .filter { (it["vote_count"].int ?: 0) >= 150 }
            .sortedByDescending { it["popularity"].dbl ?: 0.0 }
            .mapNotNull { map(it) }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    suspend fun findByImdb(imdb: String): Title? = runCatching {
        val j = api("/find/$imdb?external_source=imdb_id", 1440)
        j["movie_results"][0]?.let { map(it, "movie") } ?: j["tv_results"][0]?.let { map(it, "tv") }
    }.getOrNull()

    suspend fun search(q: String): List<Title> =
        runCatching { api("/search/multi?query=${q.urlEncode()}&include_adult=false", 5)["results"].arr.mapNotNull { map(it) } }.getOrDefault(emptyList())

    // ---- Optional IMDb/RT ratings via OMDb ----
    data class Ratings(val imdb: String?, val rt: String?)

    suspend fun ratings(imdbId: String?): Ratings? {
        val k = store.value.omdbKey.trim()
        if (k.isBlank() || imdbId.isNullOrBlank()) return null
        return runCatching {
            val j = Http.get("https://www.omdbapi.com/?i=$imdbId&apikey=$k", cacheMinutes = 1440)
            val imdb = j["imdbRating"].str?.takeIf { it != "N/A" }
            val rt = j["Ratings"].arr.firstOrNull { it["Source"].str == "Rotten Tomatoes" }?.get("Value")?.str
            Ratings(imdb, rt)
        }.getOrNull()
    }
}
