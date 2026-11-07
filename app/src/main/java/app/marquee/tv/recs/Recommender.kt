package app.marquee.tv.recs

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.FeedBalance
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Providers
import app.marquee.tv.data.Row
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.plex.PlexRepo
import app.marquee.tv.tmdb.TmdbRepo
import app.marquee.tv.youtube.YouTubeRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Year

@kotlinx.serialization.Serializable
data class HomeData(
    val featured: Title?,
    val rows: List<Row>,
    val ambientArt: List<String>,
    val errors: List<String> = emptyList(),
)

data class DetailData(
    val title: Title,
    val ways: List<Availability>,
    val trailerUrl: String?,       // directly playable (Plex), auto-plays
    val trailerYoutube: String?,   // opens in the preferred YouTube app
    val moreLikeThis: List<Title>,
    val elsewhere: List<String>,
    val people: List<app.marquee.tv.tmdb.Person> = emptyList(),
    val director: app.marquee.tv.tmdb.Person? = null,
    val rows: List<Pair<String, List<Title>>> = emptyList(), // Plex's related rows, then "With <lead>" and "From <director>"
    val showKey: String? = null,   // Plex show, for the seasons & episodes picker
    val seasonCount: Int? = null,
)

/**
 * Deterministic recommendations: no AI. Every candidate must be watchable at no extra cost
 * (your Plex library, a subscription you have, or genuinely free). Rentals and purchases never appear.
 */
class Recommender(
    private val store: SettingsStore,
    private val plex: PlexRepo,
    private val tmdb: TmdbRepo,
    private val youtube: YouTubeRepo,
) {
    private val s get() = store.value
    private val gate = Semaphore(8)

    private val kidCerts = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")
    private val kidGenres = setOf("Family", "Animation", "Kids")

    suspend fun home(sky: app.marquee.tv.weather.Sky? = null): HomeData = coroutineScope {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun <T> safe(label: String, block: suspend () -> List<T>) = async {
            runCatching { block() }.getOrElse { errors += "$label: ${it.message}"; emptyList() }
        }
        val plexOn = s.plexReady
        val tmdbOn = tmdb.ready
        val services = s.services
        val balance = s.feedBalance
        val myServices = Providers.all.filter { it.id in services }

        val unwatched = safe("Plex") { if (plexOn) plex.unwatchedTopRated() else emptyList() }
        val watched = safe("Plex history") { if (plexOn) plex.recentlyWatched() else emptyList() }
        val cont = safe("Continue watching") { if (plexOn) plex.continueWatching() else emptyList() }
        val added = safe("Recently added") { if (plexOn) plex.recentlyAdded() else emptyList() }
        val trending = safe("Trending") { if (tmdbOn) tmdb.trending(3) else emptyList() }
        val popMovies = safe("Discover") { if (tmdbOn) tmdb.discover("movie", services) else emptyList() }
        val popTv = safe("Discover") { if (tmdbOn) tmdb.discover("tv", services) else emptyList() }
        val topMovies = safe("Top rated") { if (tmdbOn) tmdb.discover("movie", services, sort = "vote_average.desc", minVotes = 3000) else emptyList() }
        val topTv = safe("Top rated") { if (tmdbOn) tmdb.discover("tv", services, sort = "vote_average.desc", minVotes = 1500) else emptyList() }
        val yt = safe("YouTube") { youtube.newFromSubscriptions() }
        val occasions = Occasions.active(sky = sky).take(2)
        val library = safe("Plex library") { if (plexOn && occasions.isNotEmpty()) plex.libraryIndex() else emptyList() }
        val plexHubs = safe("Plex hubs") { if (plexOn) plex.rotatingHubs() else emptyList() }
        val plexCollections = safe("Plex collections") { if (plexOn) plex.collections() else emptyList() }
        // One "New on…" and one "Popular on…" list per service (Top 10 comes from trending, below).
        val perServiceNew = myServices.associate { p ->
            p.id to safe("New on ${p.name}") { if (tmdbOn) tmdb.newOnServices("movie", setOf(p.id)) + tmdb.newOnServices("tv", setOf(p.id)) else emptyList() }
        }
        val perServicePopular = myServices.associate { p ->
            p.id to safe("Popular on ${p.name}") {
                if (tmdbOn) interleave(tmdb.discover("movie", setOf(p.id), minVotes = 150), tmdb.discover("tv", setOf(p.id), minVotes = 150)) else emptyList()
            }
        }

        val plexItems = unwatched.await()
        val history = watched.await()
        val plexIndex = plex.guidIndex(plexItems + history + added.await())
        val seed = Occasions.dailySeed()

        // Taste: genres you actually finish on Plex.
        val taste = history.flatMap { it.genres }.groupingBy { it }.eachCount().let { counts ->
            val max = counts.values.maxOrNull()?.toDouble() ?: 1.0
            counts.mapValues { it.value / max }
        }

        // "Because you watched X": your latest Plex finish, plus two more that change daily.
        val seeds = history.filter { it.tmdbId != null }.distinctBy { "${it.mediaType}:${it.tmdbId}" }.take(8).let { h ->
            (h.take(1) + h.drop(1).shuffled(kotlin.random.Random(seed))).take(3)
        }
        val seedRecs = if (!tmdbOn) emptyList() else seeds.map { sd ->
            async {
                sd to runCatching {
                    (tmdb.recommendations(sd.mediaType, sd.tmdbId!!) + tmdb.similar(sd.mediaType, sd.tmdbId)).distinctBy { it.id }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        val because = mutableMapOf<String, String>()
        seedRecs.forEach { (sd, recs) -> recs.forEach { because.putIfAbsent(it.id, sd.title) } }

        // Attach availability to streaming candidates and fold in matching Plex copies.
        suspend fun withAvailability(list: List<Title>): List<Title> = list.distinctBy { it.id }.map { t ->
            async {
                gate.withPermit {
                    val inPlex = plexIndex["tmdb:${t.mediaType}:${t.tmdbId}"]
                    val info = runCatching { tmdb.watchInfo(t.mediaType, t.tmdbId!!) }.getOrNull()
                    val (mine, elsewhere) = info?.let { tmdb.availabilityFor(it, services) } ?: (emptyList<Availability>() to emptyList())
                    val ways = (inPlex?.availability.orEmpty() + mine).sortedByDescending { it.score }
                    t.copy(
                        availability = ways,
                        elsewhere = elsewhere,
                        plexKey = inPlex?.plexKey,
                        plexViewed = inPlex?.plexViewed ?: false,
                        backdropUrl = t.backdropUrl ?: inPlex?.backdropUrl,
                    )
                }
            }
        }.awaitAll()
        fun onMyServices(t: Title) = t.availability.any { it.kind != AvailKind.PLEX }

        val trend = withAvailability(trending.await())
        val streaming = withAvailability(popMovies.await() + popTv.await() + topMovies.await() + topTv.await())
            .filter { it.availability.isNotEmpty() }

        // Rank among trending titles per service: "#1 ON HULU".
        val perService = mutableMapOf<String, Int>()
        val trendBadges = mutableMapOf<String, String>()
        trend.sortedBy { it.trendRank ?: 999 }.forEach { t ->
            val a = t.bestAvailability ?: return@forEach
            val name = if (a.kind == AvailKind.PLEX) "YOUR PLEX" else a.name.uppercase()
            val n = (perService[name] ?: 0) + 1
            perService[name] = n
            if (n <= 10) trendBadges[t.id] = if (a.kind == AvailKind.PLEX) "TRENDING · ON YOUR PLEX" else "#$n ON $name"
        }
        val trendRankById = trend.associate { it.id to (it.trendRank ?: 99) }

        val profile = s.profile
        fun kidOk(t: Title) = !profile.kidSafe || t.genres.any { it in kidGenres } || t.contentRating in kidCerts

        var seasonalIds: Set<String> = emptySet()
        fun score(t: Title): Title {
            val rating = (t.rating ?: 6.0) / 10.0
            val tr = trendRankById[t.id]?.let { 1.0 - (it - 1) / 40.0 } ?: 0.0
            val tasteScore = if (taste.isEmpty()) 0.0 else t.genres.sumOf { taste[it] ?: 0.0 } / t.genres.size.coerceAtLeast(1)
            val reason = because[t.id]?.let { "Because you watched $it" }
            val fresh = if ((t.year.toIntOrNull() ?: 0) >= Year.now().value - 2) 0.05 else 0.0
            val seasonal = if (t.id in seasonalIds) 0.1 else 0.0
            // No Plex bonus here: the feed balance decides how much Plex "For you" gets.
            val total = 0.42 * rating + 0.22 * tr + 0.2 * tasteScore + (if (reason != null) 0.12 else 0.0) + fresh + seasonal
            return t.copy(
                score = total,
                reason = reason ?: t.reason ?: when {
                    tr > 0.7 -> "Trending this week"
                    t.source == Source.PLEX && (t.rating ?: 0.0) >= 8 -> "Top rated and unwatched in your library"
                    tasteScore > 0.6 -> "Matches what you watch"
                    else -> null
                },
                badge = trendBadges[t.id] ?: t.badge,
            )
        }

        // ---- Themed ribbons that change with the season, the weather and the day ----
        val collections = plexCollections.await()
        val libraryItems = library.await()
        val libIdx = plex.guidIndex(libraryItems)
        fun dedupeKey(t: Title) = if (t.tmdbId != null) "tmdb:${t.mediaType}:${t.tmdbId}" else t.id
        // Only an explicit "More Plex" lifts your library above better-rated titles on your services.
        val plexLift = if (balance == FeedBalance.MORE_PLEX) 0.5 else 0.0

        suspend fun occasionRow(o: Occasion): Row? {
            val types = if (o.type == "any") listOf("movie", "tv") else listOf(o.type)
            val kw = if (tmdbOn) o.keywords.flatMap { tmdb.keywordIds(it) }.distinct() else emptyList()
            val fromCollections = collections.filter { c -> o.collectionWords.any { c.title.contains(it, true) } }.take(2)
                .flatMap { plex.collectionItems(it.key) }
            val fromKeywords = if (kw.isEmpty() || libIdx.isEmpty()) emptyList()
                else types.flatMap { tmdb.byKeywords(it, kw, providers = null, pages = 4, minVotes = 30) }.mapNotNull { libIdx["tmdb:${it.mediaType}:${it.tmdbId}"] }
            val fromGenres = if (fromCollections.size + fromKeywords.size >= 8 || o.plexGenres.isEmpty()) emptyList()
                else (libraryItems.ifEmpty { plexItems }).filter { t ->
                    t.mediaType in types && t.genres.any { g -> o.plexGenres.any { g.equals(it, true) } } &&
                        (o.maxRuntime == null || t.mediaType == "tv" || (t.runtimeMin ?: 0) <= o.maxRuntime)
                }.sortedByDescending { it.rating ?: 0.0 }.take(24)
            // Holiday favourites are worth rewatching; everyday moods prefer things you haven't seen.
            val plexPart = (fromCollections + fromKeywords + fromGenres).filter { o.priority >= 80 || !it.plexViewed }
            val streamingRaw = when {
                !tmdbOn -> emptyList()
                kw.isNotEmpty() -> types.flatMap { tmdb.byKeywords(it, kw, services, minVotes = 100) }
                else -> types.flatMap { tmdb.discover(it, services, o.genres, o.maxRuntime, minVotes = 500) }
            }
            val streamingPart = withAvailability(streamingRaw.take(20)).filter { it.availability.isNotEmpty() }
            val all = (plexPart + streamingPart).distinctBy(::dedupeKey).filter(::kidOk)
            if (all.size < 4) return null
            // Best-rated first, but the top 30 rotate daily so the ribbon feels fresh.
            val ranked = all.sortedByDescending { (it.rating ?: 0.0) + (if (it.source == Source.PLEX) plexLift else 0.0) }
            val rotated = ranked.take(30).shuffled(kotlin.random.Random(seed + o.key.hashCode())) + ranked.drop(30)
            return Row("${FeedLayout.OCC}${o.key}", o.title, o.subtitle, rotated.take(20).map { it.copy(badge = it.badge ?: o.badge, reason = it.reason ?: "${o.title} pick") })
        }
        val occasionRows = occasions.map { o -> async { runCatching { occasionRow(o) }.getOrNull() } }

        // ---- Mood and genre rows: all your services mixed, Plex fills gaps ----
        fun plexGenreMatch(t: Title, names: List<String>) =
            names.isEmpty() || t.genres.any { g -> names.any { n -> g.contains(n, true) || n.contains(g, true) } }
        suspend fun moodRow(m: Mood): Row? {
            if (services.isEmpty() && plexItems.isEmpty()) return null
            val raw = if (!tmdbOn) emptyList() else m.types.map { ty ->
                async {
                    val g = if (ty == "movie") m.movieGenres else m.tvGenres
                    if (ty == "tv" && g.isEmpty() && m.movieGenres.isNotEmpty()) emptyList()
                    else tmdb.discover(ty, services, g, if (ty == "movie") m.maxRuntime else null, sort = m.sort, minVotes = m.minVotes)
                }
            }.awaitAll().let { lists -> lists.reduceOrNull { a, b -> interleave(a, b) }.orEmpty() }
            // The best 20 take turns leading from day to day.
            val pool = raw.take(20).shuffled(kotlin.random.Random(seed + m.key.hashCode())) + raw.drop(20)
            val svc = FeedLayout.spreadByService(withAvailability(pool.take(18)).filter { onMyServices(it) && kidOk(it) })
            val plexNames = m.plexGenres.ifEmpty { m.movieGenres.mapNotNull { TmdbRepo.genreNames[it] } }
            val fill = plexItems.filter { p ->
                !p.plexViewed && p.mediaType in m.types && kidOk(p) && plexGenreMatch(p, plexNames) &&
                    (m.maxRuntime == null || p.mediaType == "tv" || (p.runtimeMin ?: Int.MAX_VALUE) <= m.maxRuntime) &&
                    svc.none { it.tmdbId != null && it.tmdbId == p.tmdbId }
            }.sortedByDescending { it.rating ?: 0.0 }.take(12).shuffled(kotlin.random.Random(seed * 7 + m.key.hashCode()))
            val items = FeedLayout.servicesFirst(svc, fill, balance).distinctBy(::dedupeKey)
            if (items.size < 5) return null
            val where = FeedLayout.servicesPhrase(items)
            return Row("${FeedLayout.MOOD}${m.key}", m.title, listOf(m.subtitle, where).filter { it.isNotBlank() }.joinToString(" · "),
                items.map { it.copy(reason = it.reason ?: m.title) })
        }
        val moodRows = Moods.forToday(seed, profile.kidSafe).map { m -> async { runCatching { moodRow(m) }.getOrNull() } }

        // ---- "Because you watched…": your Plex history, discovered on your services ----
        val becauseRows = seedRecs.map { (sd, recs) ->
            async {
                runCatching {
                    val avail = withAvailability(recs.take(24)).filter(::kidOk)
                    // The point is finding it somewhere else: titles you already have on Plex only join under More Plex.
                    val elsewhere = avail.filter { onMyServices(it) && it.availability.none { a -> a.kind == AvailKind.PLEX } }
                    val owned = if (balance == FeedBalance.MORE_PLEX) avail.filter { it.availability.any { a -> a.kind == AvailKind.PLEX } && !it.plexViewed } else emptyList()
                    val items = (FeedLayout.spreadByService(elsewhere) + owned).take(16)
                    if (items.size < 4) null else Row(
                        "${FeedLayout.BECAUSE}${sd.mediaType}:${sd.tmdbId}", "Because you watched ${sd.title}",
                        FeedLayout.servicesPhrase(items).ifBlank { "From your Plex history" },
                        items.map { it.copy(reason = "Because you watched ${sd.title}") },
                    )
                }.getOrNull()
            }
        }

        // ---- One row per service: New on…, Popular on…, Top 10 on… ----
        val serviceRows = myServices.map { p ->
            async {
                runCatching {
                    val way = Availability(AvailKind.INCLUDED, p.name, p.id, score = 50)
                    // TMDB's lists are already filtered to this service, so no extra where-to-watch lookups are needed.
                    fun prep(list: List<Title>) = list.distinctBy { it.id }.filter(::kidOk).map { t ->
                        val inPlex = plexIndex["tmdb:${t.mediaType}:${t.tmdbId}"]
                        t.copy(availability = listOf(way), plexKey = inPlex?.plexKey, plexViewed = inPlex?.plexViewed ?: false)
                    }
                    val name = p.name.uppercase()
                    val fresh = prep(perServiceNew.getValue(p.id).await()).sortedByDescending { it.popularity }.take(20)
                    val popular = prep(perServicePopular.getValue(p.id).await())
                    val trendingHere = trend.filter { t -> t.availability.any { it.providerId == p.id } && kidOk(t) }.sortedBy { it.trendRank ?: 999 }
                    val (top, topSub) = if (trendingHere.size >= 6) trendingHere.take(10) to "Trending this week"
                        else popular.sortedByDescending { it.popularity }.take(10) to "Most popular right now"
                    val topIds = top.map { it.id }.toSet()
                    buildList {
                        if (top.size >= 5) add(Row("${FeedLayout.SVC}${p.id}:top10", "Top 10 on ${p.name}", topSub,
                            top.mapIndexed { i, t -> t.copy(availability = listOf(way), badge = "#${i + 1} ON $name") }))
                        if (fresh.size >= 5) add(Row("${FeedLayout.SVC}${p.id}:new", "New on ${p.name}", "Recently released · included with ${p.name}",
                            fresh.map { it.copy(badge = it.badge ?: "NEW") }))
                        popular.filter { it.id !in topIds }.take(20).takeIf { it.size >= 5 }
                            ?.let { add(Row("${FeedLayout.SVC}${p.id}:popular", "Popular on ${p.name}", "Included with ${p.name}", it)) }
                    }
                }.getOrElse { errors += "${p.name}: ${it.message}"; emptyList() }
            }
        }

        val occasionList = occasionRows.awaitAll().filterNotNull()
        // A couple of seasonal picks compete for "For you" too.
        val seasonalPicks = occasionList.firstOrNull()?.items?.take(4).orEmpty()
        seasonalIds = seasonalPicks.map { it.id }.toSet()
        val becauseList = becauseRows.awaitAll().filterNotNull()

        val candidates = (plexItems.filter { !it.plexViewed } + streaming + trend.filter { it.availability.isNotEmpty() } +
            seasonalPicks.filter { !it.plexViewed } + becauseList.flatMap { it.items.take(4) })
            .groupBy(::dedupeKey)
            .map { (_, dupes) -> dupes.maxBy { it.availability.size + (if (it.source == Source.PLEX) 1 else 0) } }
            .filter { !it.plexViewed && kidOk(it) }
            .map(::score)
            .sortedByDescending { it.score }

        // ---- "For you": a deliberate mix, tuned to the time of day ----
        val hour = java.time.LocalTime.now().hour
        val daytime = hour in 6..16
        val ytAllowed = !profile.kidSafe || !youtube.signedIn // kid profiles get the kids pack only
        val ytPicks = if (!ytAllowed) emptyList() else yt.await().take(6).mapIndexed { i, v ->
            v.copy(score = 0.62 - i * 0.03 + (if (daytime) 0.06 else 0.0), badge = "NEW VIDEO", reason = "New from ${v.channel}")
        }
        val resume = cont.await().filter(::kidOk).take(4).mapIndexed { i, c ->
            c.copy(score = 0.66 - i * 0.04, badge = c.badge ?: "CONTINUE", reason = FeedLayout.RESUME_REASON)
        }
        fun timeFit(t: Title): Double {
            val rt = t.runtimeMin ?: (if (t.mediaType == "tv") 45 else 110)
            return when {
                daytime && t.mediaType == "movie" && rt > 140 -> -0.08
                daytime && (t.mediaType == "tv" || t.mediaType == "video") -> 0.04
                !daytime && t.mediaType == "movie" -> 0.04
                else -> 0.0
            }
        }
        val pool = (candidates.map { it.copy(score = it.score + timeFit(it)) } + ytPicks + resume).distinctBy(::dedupeKey)
            .sortedByDescending { it.score }
        // About 50% services, 20% Plex, 15% YouTube and 15% continue watching (per the feed balance),
        // spread evenly, and never the same service twice in a row.
        val tonight = enrichTop(FeedLayout.forYou(pool, balance))
        val featured = tonight.firstOrNull { it.backdropUrl != null && it.source != Source.YOUTUBE }

        // ---- From your Plex: Plex's hubs, a collection spotlight and recently added, merged into one row ----
        val nowSec = System.currentTimeMillis() / 1000
        val hubs = plexHubs.await()
        val spotlight = collections.filter { c -> occasions.none { o -> o.collectionWords.any { c.title.contains(it, true) } } }
            .shuffled(kotlin.random.Random(seed * 31)).firstOrNull()
        val spotItems = spotlight?.let { c -> runCatching { plex.collectionItems(c.key) }.getOrDefault(emptyList()) }.orEmpty()
        val plexRow = buildList {
            added.await().forEach { add(Triple(it, "Recently added to your Plex", 0.0)) }
            hubs.shuffled(kotlin.random.Random(seed)).forEach { h -> h.items.forEach { add(Triple(it, "${h.title} · from your Plex", if (h.promoted) 0.15 else 0.0)) } }
            spotItems.forEach { add(Triple(it, "From your ${spotlight?.title} collection", 0.05)) }
            plexItems.take(30).forEach { add(Triple(it, "Top rated and unwatched in your library", 0.0)) }
        }.filter { kidOk(it.first) }.map { (t, why, lift) ->
            val ageDays = if (t.addedAt > 0) (nowSec - t.addedAt) / 86400 else Long.MAX_VALUE
            val newness = when { ageDays <= 14 -> 0.5; ageDays <= 45 -> 0.25; else -> 0.0 }
            val sc = (t.rating ?: 6.5) / 10.0 + (if (t.plexViewed) -0.5 else 0.45) + newness + lift
            t.copy(score = sc, reason = why, badge = t.badge ?: if (ageDays <= 14) "JUST ADDED" else null)
        }.sortedByDescending { it.score }.distinctBy(::dedupeKey).distinctBy { it.title }.take(30)

        val rows = buildList {
            if (tonight.isNotEmpty()) add(Row(FeedLayout.FOR_YOU, forYouTitle(hour), "A mix from your services, your Plex and YouTube · nothing to buy or rent", tonight))
            addAll(occasionList)
            cont.await().filter(::kidOk).takeIf { it.isNotEmpty() }?.let { add(Row(FeedLayout.CONTINUE, "Continue watching", "From your Plex", it)) }
            yt.await().takeIf { it.isNotEmpty() && ytAllowed }?.let { add(Row(FeedLayout.YOUTUBE, if (youtube.signedIn) "New from your subscriptions" else "Fresh on YouTube", "Opens in your YouTube app", it)) }
            serviceRows.awaitAll().forEach(::addAll)
            addAll(becauseList)
            addAll(moodRows.awaitAll().filterNotNull())
            if (plexRow.size >= 4) add(Row(FeedLayout.PLEX, "From your Plex", "New and unwatched first · changes every visit", plexRow))
            trend.filter { it.availability.isNotEmpty() && kidOk(it) }.map(::score).takeIf { it.isNotEmpty() }
                ?.let { add(Row(FeedLayout.TRENDING, "Trending on your services", "", it.take(20))) }
            (topMovies.await() + topTv.await()).mapNotNull { t -> streaming.firstOrNull { it.id == t.id } }.filter(::kidOk)
                .sortedByDescending { it.rating ?: 0.0 }.takeIf { it.isNotEmpty() }
                ?.let { add(Row(FeedLayout.TOP_RATED, "Top rated on your services", "", it.take(20).map(::score))) }
        }
        // Hard rule: only your Plex, your chosen services, or YouTube. Nothing from services you haven't picked.
        fun allowed(t: Title) = t.source == Source.YOUTUBE ||
            t.availability.any { it.kind == AvailKind.PLEX || (it.providerId != null && it.providerId in services) }
        val cleanRows = rows
            .map { r -> r.copy(items = r.items.filter(::allowed).map { t -> t.copy(availability = t.availability.filter { it.kind == AvailKind.PLEX || it.providerId in services }) }.distinctBy { it.id }) }
            .filter { it.items.size >= 3 }
            .distinctBy { it.key }
        val art = (plexItems + added.await()).mapNotNull { it.backdropUrl }.distinct().shuffled().take(30)
            .ifEmpty { candidates.mapNotNull { it.backdropUrl }.take(30) }
        HomeData(featured?.takeIf(::allowed), cleanRows, art, errors.distinct())
    }

    fun forYouTitle(hour: Int = java.time.LocalTime.now().hour) = when (hour) {
        in 5..11 -> "This morning for you"
        in 12..16 -> "This afternoon for you"
        in 17..21 -> "Tonight for you"
        else -> "Late-night picks"
    }

    /** Adds IMDb/RT ratings, title logos and runtime to the handful of titles shown most prominently. */
    private suspend fun enrichTop(list: List<Title>): List<Title> = coroutineScope {
        list.map { t ->
            async {
                gate.withPermit {
                    runCatching {
                        var out = t
                        if (tmdb.ready && t.tmdbId != null) {
                            val ex = tmdb.extras(t.mediaType, t.tmdbId)
                            out = out.copy(
                                logoUrl = out.logoUrl ?: ex.logoUrl, runtimeMin = out.runtimeMin ?: ex.runtime,
                                imdbId = out.imdbId ?: ex.imdbId, cast = out.cast.ifEmpty { ex.cast },
                                contentRating = out.contentRating.ifBlank { ex.certification },
                            )
                        }
                        tmdb.ratings(out.imdbId)?.imdb?.let { out = out.copy(ratingLabel = "IMDb $it", rating = it.toDoubleOrNull() ?: out.rating) }
                        out
                    }.getOrDefault(t)
                }
            }
        }.awaitAll()
    }

    suspend fun detail(t: Title): DetailData = coroutineScope {
        var title = t
        val tmdbOn = tmdb.ready && t.tmdbId != null
        val plexKey = if (t.mediaType == "tv") t.plexShowKey ?: t.plexKey else t.plexKey
        val plexDetail = async { plexKey?.let { plex.details(it) } }
        val extras = async { if (tmdbOn) runCatching { tmdb.extras(t.mediaType, t.tmdbId!!) }.getOrNull() else null }
        val info = async { if (tmdbOn) runCatching { tmdb.watchInfo(t.mediaType, t.tmdbId!!) }.getOrNull() else null }
        val trailer = async { (if (s.plexReady && plexKey != null) plex.trailerUrl(plexKey) else null) ?: plex.officialTrailer(t) }
        val recs = async { if (tmdbOn) (tmdb.recommendations(t.mediaType, t.tmdbId!!) + tmdb.similar(t.mediaType, t.tmdbId)).distinctBy { it.id } else emptyList() }
        val related = async { if (s.plexReady && plexKey != null) plex.related(plexKey) else emptyList() }
        val plexIdx = async { if (s.plexReady) runCatching { plex.guidIndex(plex.libraryIndex()) }.getOrDefault(emptyMap()) else emptyMap() }

        val pd = plexDetail.await()
        val ex = extras.await()
        val ways = mutableListOf<Availability>()
        (pd?.availability?.firstOrNull() ?: t.availability.firstOrNull { it.kind == AvailKind.PLEX })?.let { ways += it }
        val (mine, elsewhere) = info.await()?.let { tmdb.availabilityFor(it, s.services) } ?: (t.availability.filter { it.kind != AvailKind.PLEX } to t.elsewhere)
        ways += mine

        title = title.copy(
            overview = pd?.overview?.ifBlank { null } ?: title.overview,
            cast = pd?.cast?.ifEmpty { null } ?: ex?.cast ?: title.cast,
            runtimeMin = if (t.mediaType == "tv") ex?.runtime ?: title.runtimeMin else title.runtimeMin ?: pd?.runtimeMin ?: ex?.runtime,
            logoUrl = title.logoUrl ?: pd?.logoUrl ?: ex?.logoUrl,
            backdropUrl = title.backdropUrl ?: pd?.backdropUrl,
            contentRating = title.contentRating.ifBlank { pd?.contentRating ?: ex?.certification ?: "" },
            imdbId = title.imdbId ?: ex?.imdbId,
            genres = title.genres.ifEmpty { ex?.genres ?: emptyList() },
            plexShowKey = title.plexShowKey ?: if (t.mediaType == "tv") pd?.plexKey else null,
        )
        tmdb.ratings(title.imdbId)?.let { r ->
            title = title.copy(ratingLabel = r.imdb?.let { "IMDb $it" } ?: title.ratingLabel, badge = r.rt?.let { "RT $it" } ?: title.badge)
        }
        val idx = plexIdx.await()
        val selfKeys = setOfNotNull(t.id, t.tmdbId?.let { "tmdb:${t.mediaType}:$it" }, plexKey?.let { "plex:$it" })
        fun notSelf(x: Title) = x.id !in selfKeys && !(x.tmdbId != null && x.tmdbId == t.tmdbId)

        // Plex's related rows. Its "similar" row folds into More like this; the rest ("More with…", "More directed by…") stay as rows.
        val hubs = related.await()
        val plexSimilar = hubs.filter { it.title.contains("similar", true) || it.title.contains("like this", true) || it.title.contains("related", true) }.flatMap { it.items }
        val plexRows = hubs.filterNot { h -> plexSimilar.isNotEmpty() && h.items.firstOrNull() in plexSimilar }
            .map { h -> h.title to h.items.filter(::notSelf).distinctBy { it.id } }.filter { it.second.size >= 3 }.take(3)

        val more = async {
            val fromTmdb = availableOnly(recs.await().filter(::notSelf), idx, 18)
            interleave(fromTmdb, plexSimilar.filter(::notSelf)).distinctBy { it.tmdbId?.let { id -> "${it.mediaType}:$id" } ?: it.id }.take(20)
        }
        val lead = ex?.people?.firstOrNull()
        val withLead = async {
            if (lead == null || plexRows.any { it.first.contains(lead.name, true) }) null
            else availableOnly(tmdb.personCredits(lead.id).filter(::notSelf), idx, 14).takeIf { it.size >= 3 }?.let { "More with ${lead.name}" to it }
        }
        val dir = ex?.director
        val fromDirector = async {
            if (dir == null || plexRows.any { it.first.contains(dir.name, true) }) null
            else availableOnly(tmdb.personCredits(dir.id, crew = true).filter(::notSelf), idx, 14).takeIf { it.size >= 3 }
                ?.let { (if (dir.role == "Creator") "More from ${dir.name}" else "Directed by ${dir.name}") to it }
        }
        DetailData(
            title, ways.distinctBy { it.kind to it.providerId }, trailer.await(), ex?.trailerYoutubeKey, more.await(), elsewhere,
            people = ex?.people.orEmpty(), director = dir,
            rows = plexRows + listOfNotNull(withLead.await(), fromDirector.await()),
            showKey = if (t.mediaType == "tv" && s.plexReady) (pd?.plexKey ?: plexKey) else null,
            seasonCount = ex?.seasons,
        )
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) }
    }

    /** Keeps only titles you can watch now; anything in your Plex library becomes your Plex copy. */
    private suspend fun availableOnly(cands: List<Title>, plexIdx: Map<String, Title>, max: Int): List<Title> = coroutineScope {
        cands.filter { it.tmdbId != null && (!profile.kidSafe || it.genres.any { g -> g in kidGenres } || it.contentRating in kidCerts || it.genreIds.any { g -> g == 16 || g == 10751 }) }
            .distinctBy { it.id }.take(max * 2).map { m ->
                async {
                    gate.withPermit {
                        val inPlex = plexIdx["tmdb:${m.mediaType}:${m.tmdbId}"]
                        val wi = runCatching { tmdb.watchInfo(m.mediaType, m.tmdbId!!) }.getOrNull()
                        val mineHere = wi?.let { tmdb.availabilityFor(it, s.services).first }.orEmpty()
                        if (inPlex != null) inPlex.copy(availability = inPlex.availability + mineHere, backdropUrl = m.backdropUrl ?: inPlex.backdropUrl, tmdbId = m.tmdbId)
                        else m.copy(availability = mineHere)
                    }
                }
            }.awaitAll().filter { it.availability.isNotEmpty() }.take(max)
    }

    private val profile get() = s.profile

    /**
     * Pick Together's deck: led by what's new, trending and popular across *all* your services (spread evenly
     * between them), with Plex limited to things you've just added or haven't watched yet. How often Plex
     * comes up follows the feed balance (see [togetherMix]).
     */
    suspend fun togetherCandidates(f: Filters): List<Title> = coroutineScope {
        val types = if (f.type == "any") listOf("movie", "tv") else listOf(f.type)
        val services = s.services
        val gset = f.genres.toSet() + f.genres.mapNotNull { TmdbRepo.tvGenreFor[it] }
        val names = f.genres.mapNotNull { TmdbRepo.genreNames[it] }
        fun genreOk(t: Title) = gset.isEmpty() || t.genreIds.any { it in gset }
        fun plexGenreOk(t: Title) = names.isEmpty() || t.genres.any { g -> names.any { n -> g.contains(n, true) || n.contains(g, true) } }
        fun timeOk(t: Title) = f.maxRuntime == null || t.mediaType == "tv" || (t.runtimeMin ?: 0) <= f.maxRuntime
        fun kidOk(t: Title) = !profile.kidSafe || t.genreIds.any { it == 16 || it == 10751 || it == 10762 } || t.genres.any { it in kidGenres } || t.contentRating in kidCerts
        val tmdbOn = tmdb.ready && services.isNotEmpty()

        val trending = async { if (tmdbOn) tmdb.trending(2).filter { it.mediaType in types && genreOk(it) }.map { it.copy(badge = it.trendRank?.takeIf { r -> r <= 20 }?.let { r -> "TRENDING #$r" } ?: "TRENDING") } else emptyList() }
        val fresh = types.map { ty -> async { if (tmdbOn) tmdb.newOnServices(ty, services).filter(::genreOk).map { it.copy(badge = "NEW") } else emptyList() } }
        val popular = types.map { ty ->
            async { if (tmdbOn) tmdb.discover(ty, services, f.genres, if (ty == "movie") f.maxRuntime else null, minVotes = 300).map { it.copy(badge = "POPULAR") } else emptyList() }
        }
        val plexNew = async {
            if (!s.plexReady) emptyList() else {
                val weekAgo = System.currentTimeMillis() / 1000 - 21 * 86400
                plex.unwatchedNewest(60).filter { it.mediaType in types && plexGenreOk(it) && timeOk(it) && kidOk(it) }
                    .map { it.copy(badge = if (it.addedAt > weekAgo) "JUST ADDED" else "UNWATCHED") }
            }
        }
        val idx = async { if (s.plexReady) runCatching { plex.guidIndex(plex.libraryIndex()) }.getOrDefault(emptyMap()) else emptyMap() }

        // Services: new, trending and popular in turn, then checked against your plans.
        val lists = listOf(fresh.awaitAll().flatten(), trending.await(), popular.awaitAll().flatten())
        val mixed = buildList { val n = lists.maxOf { it.size }; for (i in 0 until n) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
            .filter { it.tmdbId != null && kidOk(it) }.distinctBy { "${it.mediaType}:${it.tmdbId}" }.take(60)
        val library = idx.await()
        val onServices = mixed.map { m ->
            async {
                gate.withPermit {
                    val wi = runCatching { tmdb.watchInfo(m.mediaType, m.tmdbId!!) }.getOrNull() ?: return@withPermit null
                    val mine = tmdb.availabilityFor(wi, services).first
                    if (mine.isEmpty()) null else {
                        val plexCopy = library["tmdb:${m.mediaType}:${m.tmdbId}"]
                        m.copy(availability = mine + plexCopy?.availability.orEmpty(), plexKey = plexCopy?.plexKey, plexShowKey = plexCopy?.plexShowKey)
                    }
                }
            }
        }.awaitAll().filterNotNull()
        // Spread evenly across your services so one service doesn't fill the deck.
        val byService = onServices.groupBy { it.availability.firstOrNull { a -> a.kind != AvailKind.PLEX }?.providerId }.values.toList()
        val balanced = buildList { val n = byService.maxOfOrNull { it.size } ?: 0; for (i in 0 until n) byService.forEach { g -> g.getOrNull(i)?.let(::add) } }

        val fromPlex = plexNew.await().filter { p -> balanced.none { it.tmdbId != null && it.tmdbId == p.tmdbId } }
        togetherMix(balanced, fromPlex, s.feedBalance)
    }

    companion object {
        /**
         * Pick Together's deck order: services and Plex in turn, by the feed balance. More Plex alternates
         * one and one, Balanced is two services then one Plex, More services is four then one.
         */
        fun togetherMix(services: List<Title>, plex: List<Title>, balance: FeedBalance): List<Title> {
            val (svcRun, plexRun) = when (balance) {
                FeedBalance.MORE_PLEX -> 1 to 1
                FeedBalance.BALANCED -> 2 to 1
                FeedBalance.MORE_SERVICES -> 4 to 1
            }
            val out = mutableListOf<Title>()
            var si = 0; var pi = 0
            while (si < services.size || pi < plex.size) {
                repeat(svcRun) { services.getOrNull(si++)?.let(out::add) }
                repeat(plexRun) { plex.getOrNull(pi++)?.let(out::add) }
            }
            return out
        }
    }

    /** The non-AI "What should we watch?" picker. */
    data class Filters(val genres: List<Int>, val maxRuntime: Int?, val type: String /* movie|tv|any */, val unseenOnly: Boolean = true)

    suspend fun pick(f: Filters): List<Title> = coroutineScope {
        val types = if (f.type == "any") listOf("movie", "tv") else listOf(f.type)
        val streaming = types.map { ty ->
            async { tmdb.discover(ty, s.services, f.genres, if (ty == "movie") f.maxRuntime else null, sort = "vote_average.desc", minVotes = 800) }
        }
        val plexAll = async { if (s.plexReady) plex.unwatchedTopRated(120) else emptyList() }
        val names = f.genres.mapNotNull { TmdbRepo.genreNames[it] }.toSet()
        val fromPlex = plexAll.await().filter { p ->
            (f.type == "any" || p.mediaType == f.type) &&
                (names.isEmpty() || p.genres.any { g -> names.any { n -> g.contains(n, true) || n.contains(g, true) } }) &&
                (f.maxRuntime == null || p.mediaType == "tv" || (p.runtimeMin ?: 0) <= f.maxRuntime)
        }
        val plexIdx = plex.guidIndex(fromPlex)
        val streamed = streaming.awaitAll().flatten().distinctBy { it.id }.take(30).map { t ->
            async {
                gate.withPermit {
                    val wi = runCatching { tmdb.watchInfo(t.mediaType, t.tmdbId!!) }.getOrNull()
                    val inPlex = plexIdx["tmdb:${t.mediaType}:${t.tmdbId}"]
                    t.copy(availability = (inPlex?.availability.orEmpty() + (wi?.let { tmdb.availabilityFor(it, s.services).first } ?: emptyList())))
                }
            }
        }.awaitAll().filter { it.availability.isNotEmpty() }
        val plexLift = when (s.feedBalance) { FeedBalance.MORE_PLEX -> 0.4; FeedBalance.BALANCED -> 0.15; FeedBalance.MORE_SERVICES -> 0.0 }
        val merged = (fromPlex + streamed).distinctBy { it.tmdbId?.let { id -> "${it.mediaType}:$id" } ?: it.id }
            .sortedByDescending { (it.rating ?: 0.0) + if (it.source == Source.PLEX) plexLift else 0.0 }
        enrichTop(merged.take(9)) + merged.drop(9).take(15)
    }

    fun searchServicesName(ids: Set<Int>) = ids.mapNotNull { Providers.name(it) }

    /** Search Plex and everything included with your services. Paid-only results are dropped. */
    suspend fun search(q: String): List<Title> = coroutineScope {
        if (q.isBlank()) return@coroutineScope emptyList()
        val p = async { if (s.plexReady) runCatching { plex.search(q) }.getOrDefault(emptyList()) else emptyList() }
        val t = async { if (tmdb.ready) tmdb.search(q).take(20) else emptyList() }
        val plexHits = p.await()
        val idx = plex.guidIndex(plexHits)
        val streamed = t.await().map { m ->
            async {
                gate.withPermit {
                    val wi = runCatching { tmdb.watchInfo(m.mediaType, m.tmdbId!!) }.getOrNull()
                    val (mine, elsewhere) = wi?.let { tmdb.availabilityFor(it, s.services) } ?: (emptyList<Availability>() to emptyList())
                    val inPlex = idx["tmdb:${m.mediaType}:${m.tmdbId}"]
                    m.copy(availability = inPlex?.availability.orEmpty() + mine, elsewhere = elsewhere, plexKey = inPlex?.plexKey)
                }
            }
        }.awaitAll()
        val tmdbIds = streamed.mapNotNull { it.tmdbId }.toSet()
        (plexHits.filter { it.tmdbId == null || it.tmdbId !in tmdbIds } + streamed.filter { it.availability.isNotEmpty() })
            .sortedByDescending { (if (it.availability.isNotEmpty()) 10.0 else 0.0) + (it.rating ?: 0.0) }
    }
}
