package app.marquee.tv.recs

/**
 * Everyday mood and genre rows. Unlike the seasonal ribbons these lead with what's on your services,
 * mixed across all of them; your Plex only fills gaps (see [FeedLayout.servicesFirst]).
 */
data class Mood(
    val key: String,
    val title: String,
    val subtitle: String,
    val movieGenres: List<Int> = emptyList(), // TMDB movie genre ids (any of)
    val tvGenres: List<Int> = emptyList(),    // TMDB TV genre ids (any of)
    val types: List<String> = listOf("movie", "tv"),
    val maxRuntime: Int? = null,
    val sort: String = "popularity.desc",
    val minVotes: Int = 300,
    val plexGenres: List<String> = emptyList(),
    val kidFriendly: Boolean = false,
)

object Moods {
    val all = listOf(
        Mood("comedy", "Easy comedies", "Light, funny, nothing heavy", listOf(35), listOf(35), plexGenres = listOf("Comedy"), kidFriendly = true),
        Mood("thriller", "Gripping thrillers", "Edge-of-your-seat picks", listOf(53), listOf(80, 9648), plexGenres = listOf("Thriller", "Suspense")),
        Mood("short", "Under 90 minutes", "Films that fit in an evening", types = listOf("movie"), maxRuntime = 90, minVotes = 500, kidFriendly = true),
        Mood("feelgood", "Feel-good favourites", "Warm, funny and family friendly", listOf(10751, 10749), listOf(10751), plexGenres = listOf("Family", "Romance"), kidFriendly = true),
        Mood("scifi", "Mind-bending sci-fi", "Big ideas and other worlds", listOf(878), listOf(10765), plexGenres = listOf("Science Fiction", "Sci-Fi")),
        Mood("crime", "Crime and mystery", "Whodunnits, heists and detectives", listOf(80, 9648), listOf(80, 9648), plexGenres = listOf("Crime", "Mystery")),
        Mood("action", "Action-packed", "Big, loud and fun", listOf(28), listOf(10759), plexGenres = listOf("Action")),
        Mood("true", "True stories", "Documentaries and docuseries", listOf(99), listOf(99), minVotes = 100, plexGenres = listOf("Documentary")),
        Mood("animated", "Animated for everyone", "Good for all ages", listOf(16), listOf(16), plexGenres = listOf("Animation"), kidFriendly = true),
        Mood("acclaimed", "Acclaimed series", "The best-rated shows on your services", types = listOf("tv"), sort = "vote_average.desc", minVotes = 1500),
        Mood("drama", "Award-worthy dramas", "Critically loved films", listOf(18), types = listOf("movie"), sort = "vote_average.desc", minVotes = 2500, plexGenres = listOf("Drama")),
    )

    /** Today's moods: a daily shuffle, so the set changes from day to day (and the layout rotates between visits). */
    fun forToday(seed: Long, kidSafe: Boolean, count: Int = 5): List<Mood> =
        all.filter { !kidSafe || it.kidFriendly }.shuffled(kotlin.random.Random(seed * 17 + 3)).take(count)
}
