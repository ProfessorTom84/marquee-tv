package app.marquee.tv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class Source { PLEX, STREAMING, YOUTUBE }

enum class AvailKind(val label: String) { PLEX("On your Plex"), INCLUDED("Included"), FREE("Free"), WITH_ADS("Free with ads") }

/** One way to watch a title that costs nothing extra. Rentals and purchases are never modelled. */
@Immutable
@Serializable
data class Availability(
    val kind: AvailKind,
    val name: String,
    val providerId: Int? = null,
    val quality: List<String> = emptyList(),
    val note: String = "",
    val score: Int = 0,
)

@Immutable
@Serializable
data class Title(
    val id: String,
    val source: Source,
    val title: String,
    val mediaType: String = "movie", // movie | tv | video
    val year: String = "",
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val rating: Double? = null,
    val ratingLabel: String? = null,
    val runtimeMin: Int? = null,
    val genres: List<String> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val availability: List<Availability> = emptyList(),
    val badge: String? = null,
    val reason: String? = null,
    val popularity: Double = 0.0,
    val trendRank: Int? = null,
    val plexKey: String? = null,
    val plexViewed: Boolean = false,
    val progress: Float? = null,
    val contentRating: String = "",
    val youtubeId: String? = null,
    val channel: String = "",
    val published: String = "",
    val duration: String = "",
    val elsewhere: List<String> = emptyList(), // "Trending everywhere": where it streams, info only
    val score: Double = 0.0,
    val cast: List<String> = emptyList(),
    val plexGuid: String? = null, // plex://movie/<id>, used to find official trailers
    val plexShowKey: String? = null, // for an episode: its show's rating key
    val addedAt: Long = 0, // Plex: when it arrived on your server (epoch seconds)
) {
    val isElsewhere get() = availability.isEmpty() && elsewhere.isNotEmpty()
    val bestAvailability get() = availability.maxByOrNull { it.score }
    val metaLine: String
        get() = listOfNotNull(ratingLabel, bestAvailability?.let { availabilityPhrase(it) }).joinToString(" · ")

    companion object {
        fun availabilityPhrase(a: Availability) = when (a.kind) {
            AvailKind.PLEX -> "On your Plex"
            AvailKind.INCLUDED -> "Included with ${a.name}"
            AvailKind.FREE -> "Free on ${a.name}"
            AvailKind.WITH_ADS -> "Free on ${a.name} (ads)"
        }
    }
}

@Immutable
@Serializable
data class Row(val key: String, val title: String, val subtitle: String = "", val items: List<Title>, val wide: Boolean = true)

/** Streaming services the launcher knows how to open. Ids are TMDB / JustWatch provider ids (US). */
data class Provider(val id: Int, val name: String, val packages: List<String>)

object Providers {
    val all = listOf(
        Provider(8, "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        Provider(9, "Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
        Provider(337, "Disney+", listOf("com.disney.disneyplus")),
        Provider(15, "Hulu", listOf("com.hulu.livingroomplus", "com.hulu.plus")),
        Provider(1899, "Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        Provider(350, "Apple TV+", listOf("com.apple.atve.androidtv.appletv")),
        Provider(531, "Paramount+", listOf("com.cbs.ott", "com.cbs.app")),
        Provider(386, "Peacock", listOf("com.peacocktv.peacockandroid")),
        Provider(43, "Starz", listOf("com.bydeluxe.d3.android.program.starz")),
        Provider(283, "Crunchyroll", listOf("com.crunchyroll.crunchyroid")),
        Provider(191, "Kanopy", listOf("com.kanopy")),
    )
    private val byId = all.associateBy { it.id }
    fun name(id: Int) = byId[id]?.name
    fun get(id: Int) = byId[id]
    // TMDB sometimes reports a service under several ids (ad tier, add-on channels). Fold them together.
    val aliases = mapOf(384 to 1899, 2303 to 531, 2616 to 531, 387 to 386, 1796 to 8, 2528 to 337, 10 to 9)
    fun canonical(id: Int) = aliases[id] ?: id

    const val PLEX_PACKAGE = "com.plexapp.android"
    const val PLEZY_PACKAGE = "com.edde746.plezy"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube.tv"
}
