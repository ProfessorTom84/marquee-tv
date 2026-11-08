package app.marquee.tv.recs

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.FeedBalance
import app.marquee.tv.data.Row
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title

/**
 * How the home feed is put together, kept free of Android and network code so it can be unit tested.
 *
 * Row keys:
 *  - `tonight` ("For you"), `continue`, `youtube`, `plex` ("From your Plex"), `trending`, `toprated`
 *  - `svc:<providerId>:<new|popular|top10>`: one row per service and flavour
 *  - `because:<tmdbId>`: "Because you watched…" from your Plex history, on your services
 *  - `mood:<key>`: services-first mood and genre rows
 *  - `occ:<key>`: seasonal, weather and time-of-week ribbons
 *
 * Plex never gets more than two rows: Continue watching and From your Plex.
 */
object FeedLayout {
    const val FOR_YOU = "tonight"
    const val CONTINUE = "continue"
    const val YOUTUBE = "youtube"
    const val PLEX = "plex"
    const val TRENDING = "trending"
    const val TOP_RATED = "toprated"
    const val SVC = "svc:"
    const val BECAUSE = "because:"
    const val MOOD = "mood:"
    const val OCC = "occ:"

    /** Rows from older versions that the merged "From your Plex" row replaces. */
    private val retired = listOf("hub:", "coll:", "added", "new")
    private fun isRetired(key: String) = retired.any { if (it.endsWith(":")) key.startsWith(it) else key == it }

    fun providerOf(key: String): Int? = if (key.startsWith(SVC)) key.removePrefix(SVC).substringBefore(':').toIntOrNull() else null

    private fun <T> List<T>.rotated(by: Int) = if (isEmpty()) this else (((by % size) + size) % size).let { k -> drop(k) + take(k) }

    /** How many service rows show on one visit. Balanced alternates two and three. */
    fun serviceRowsFor(balance: FeedBalance, turn: Int) = when (balance) {
        FeedBalance.MORE_SERVICES -> 3
        FeedBalance.MORE_PLEX -> 2
        FeedBalance.BALANCED -> if (turn % 2 == 0) 3 else 2
    }

    /**
     * Picks and orders this visit's rows from everything the recommender built.
     * [turn] moves on every time you come home (and every hour), so a different two or three services
     * get a row each time, taking turns so no service takes over. [visit] rotates the cards inside
     * "For you" and "From your Plex" (0 = the first load: leave them as ranked).
     */
    /**
     * The groups of rows you can reorder or hide (Settings → Home rows). "For you" always leads and isn't listed.
     * [defaultOrder] is the order they first appear in Marquee's own mixed layout.
     */
    enum class RowGroup(val label: String, val blurb: String) {
        CONTINUE("Continue watching", "From your Plex"),
        SEASONAL("Seasonal and weather", "Holidays, rainy nights, movie night…"),
        SERVICES("Your services", "New on…, Popular on…, Top 10 on…: two or three services each visit"),
        BECAUSE("Because you watched…", "Like what you finished on Plex, on your services"),
        MOODS("Moods and genres", "Easy comedies, gripping thrillers, under 90 minutes…"),
        PLEX("From your Plex", "New and unwatched in your library"),
        YOUTUBE("YouTube", "New from your subscriptions"),
        TRENDING("Trending on your services", ""),
        TOP_RATED("Top rated on your services", "");

        companion object {
            fun parse(names: Collection<String>) = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }
        }
    }

    fun groupOf(key: String): RowGroup? = when {
        key == CONTINUE -> RowGroup.CONTINUE
        key.startsWith(OCC) -> RowGroup.SEASONAL
        key.startsWith(SVC) -> RowGroup.SERVICES
        key.startsWith(BECAUSE) -> RowGroup.BECAUSE
        key.startsWith(MOOD) -> RowGroup.MOODS
        key == PLEX -> RowGroup.PLEX
        key == YOUTUBE -> RowGroup.YOUTUBE
        key == TRENDING -> RowGroup.TRENDING
        key == TOP_RATED -> RowGroup.TOP_RATED
        else -> null
    }

    /** Where each group first shows up in the mixed layout, for this balance (what Settings shows before you reorder). */
    fun defaultOrder(balance: FeedBalance): List<RowGroup> = buildList {
        add(RowGroup.CONTINUE)
        if (balance == FeedBalance.MORE_PLEX) add(RowGroup.PLEX)
        addAll(listOf(RowGroup.SEASONAL, RowGroup.SERVICES, RowGroup.BECAUSE, RowGroup.YOUTUBE, RowGroup.MOODS))
        if (balance != FeedBalance.MORE_PLEX) add(RowGroup.PLEX)
        addAll(listOf(RowGroup.TRENDING, RowGroup.TOP_RATED))
    }

    /**
     * [order]: your own order of groups (empty = Marquee's mixed layout, which interleaves services, moods and
     * "Because you watched…"). With your own order each group's rows sit together. [hidden] groups never show.
     */
    fun arrange(
        rows: List<Row>, balance: FeedBalance, turn: Int, visit: Int = 0,
        order: List<RowGroup> = emptyList(), hidden: Set<RowGroup> = emptySet(),
    ): List<Row> {
        val byKey = rows.associateBy { it.key }
        fun one(key: String) = byKey[key]

        // Services: round-robin over the services that have rows, two or three per turn, one flavour each.
        val svcGroups = rows.filter { it.key.startsWith(SVC) }.groupBy { providerOf(it.key) }.values.toList()
        val k = serviceRowsFor(balance, turn).coerceAtMost(svcGroups.size)
        val start = if (svcGroups.isEmpty()) 0 else Math.floorMod(turn * k, svcGroups.size)
        val svc = (0 until k).map { i ->
            val group = svcGroups[(start + i) % svcGroups.size]
            group[Math.floorMod(turn + start + i, group.size)]
        }
        val because = rows.filter { it.key.startsWith(BECAUSE) }.rotated(turn).take(2)
        val moods = rows.filter { it.key.startsWith(MOOD) }.rotated(turn).take(if (balance == FeedBalance.MORE_PLEX) 2 else 3)
        val occ = rows.filter { it.key.startsWith(OCC) }

        val forYou = one(FOR_YOU)?.let { if (visit > 0) it.copy(items = it.items.rotated(visit * 3)) else it }
        val plex = one(PLEX)?.let { if (visit > 0) it.copy(items = it.items.rotated(visit * 5)) else it }

        val out = buildList {
            forYou?.let(::add)
            one(CONTINUE)?.let(::add)
            if (balance == FeedBalance.MORE_PLEX) plex?.let(::add)
            addAll(occ)
            svc.getOrNull(0)?.let(::add)
            because.getOrNull(0)?.let(::add)
            one(YOUTUBE)?.let(::add)
            moods.getOrNull(0)?.let(::add)
            svc.getOrNull(1)?.let(::add)
            if (balance == FeedBalance.BALANCED) plex?.let(::add)
            moods.getOrNull(1)?.let(::add)
            because.getOrNull(1)?.let(::add)
            svc.getOrNull(2)?.let(::add)
            moods.getOrNull(2)?.let(::add)
            if (balance == FeedBalance.MORE_SERVICES) plex?.let(::add)
            one(TRENDING)?.let(::add)
            one(TOP_RATED)?.let(::add)
        }
        val known = setOf(FOR_YOU, CONTINUE, YOUTUBE, PLEX, TRENDING, TOP_RATED)
        val extras = rows.filter { r ->
            r.key !in known && listOf(SVC, BECAUSE, MOOD, OCC).none { r.key.startsWith(it) } && !isRetired(r.key)
        }
        val mixed = (out + extras).distinctBy { it.key }.filter { r -> groupOf(r.key)?.let { it !in hidden } ?: true }
        if (order.isEmpty()) return mixed
        // Your order: For you first, then each group's rows together (groups you haven't placed keep their usual spot after).
        val full = order.distinct() + defaultOrder(balance).filter { it !in order }
        val rank = full.withIndex().associate { (i, g) -> g to i }
        return mixed.withIndex().sortedWith(compareBy<IndexedValue<Row>>(
            { if (it.value.key == FOR_YOU) -1 else groupOf(it.value.key)?.let { g -> rank[g] } ?: Int.MAX_VALUE },
            { it.index },
        )).map { it.value }
    }

    // ---------------------------------- "For you" ----------------------------------

    enum class Lane { SVC, PLEX, YT, CONT }

    const val RESUME_REASON = "Pick up where you left off"

    fun laneOf(t: Title) = when {
        t.source == Source.YOUTUBE -> Lane.YT
        t.reason == RESUME_REASON -> Lane.CONT
        t.bestAvailability?.kind == AvailKind.PLEX -> Lane.PLEX
        else -> Lane.SVC
    }

    /** The streaming service a title is shown under (its best non-Plex way to watch). */
    fun serviceOf(t: Title): String? =
        if (laneOf(t) != Lane.SVC) null else t.availability.filter { it.kind != AvailKind.PLEX }.maxByOrNull { it.score }?.name

    fun weights(b: FeedBalance) = mapOf(Lane.SVC to b.svc, Lane.PLEX to b.plex, Lane.YT to b.yt, Lane.CONT to b.cont)

    /**
     * Mixes the lanes to the balance's shares (about 50% services, 20% Plex, 15% YouTube, 15% continue
     * watching when Balanced), spread evenly with a smooth weighted round-robin. Each lane must already be
     * best-first. Services take turns: the least-shown service goes next, and the same service never
     * appears twice in a row (including where the row wraps around when it rotates).
     */
    fun forYou(pool: List<Title>, balance: FeedBalance, size: Int = 16): List<Title> {
        val lanes = pool.groupBy(::laneOf).mapValues { it.value.toMutableList() }.toMutableMap()
        val w = weights(balance)
        val credit = Lane.entries.associateWith { 0 }.toMutableMap()
        val used = mutableMapOf<String, Int>()
        val out = mutableListOf<Title>()

        fun svcPick(): Title? {
            val list = lanes[Lane.SVC]?.takeIf { it.isNotEmpty() } ?: return null
            val avoid = buildSet {
                out.lastOrNull()?.let(::serviceOf)?.let(::add)
                if (out.size == size - 1) out.firstOrNull()?.let(::serviceOf)?.let(::add)
            }
            val best = list.filter { serviceOf(it) !in avoid }
                .groupBy { serviceOf(it) }.entries
                .minWithOrNull(compareBy<Map.Entry<String?, List<Title>>> { used[it.key] ?: 0 }.thenBy { list.indexOf(it.value.first()) })
                ?.value?.first() ?: return null
            return best
        }

        while (out.size < size) {
            val active = Lane.entries.filter { (w[it] ?: 0) > 0 && !lanes[it].isNullOrEmpty() }
            if (active.isEmpty()) break
            val total = active.sumOf { w[it] ?: 0 }
            active.forEach { credit[it] = (credit[it] ?: 0) + (w[it] ?: 0) }
            // Highest credit first; if that's services but every service left would repeat, try the next lane.
            var picked: Title? = null
            for (lane in active.sortedByDescending { credit[it] ?: 0 }) {
                val t = if (lane == Lane.SVC) svcPick() else lanes[lane]?.firstOrNull()
                if (t != null) { picked = t; credit[lane] = (credit[lane] ?: 0) - total; break }
            }
            val t = picked ?: break
            lanes[laneOf(t)]?.remove(t)
            serviceOf(t)?.let { used[it] = (used[it] ?: 0) + 1 }
            out += t
        }
        return out
    }

    // ---------------------------------- Services-first rows ----------------------------------

    /** Round-robin across services so one service doesn't fill a row; keeps each service's own order. */
    fun spreadByService(list: List<Title>): List<Title> {
        val groups = list.groupBy { t -> t.availability.filter { it.kind != AvailKind.PLEX }.maxByOrNull { it.score }?.providerId }.values.toList()
        val n = groups.maxOfOrNull { it.size } ?: 0
        return buildList { for (i in 0 until n) groups.forEach { g -> g.getOrNull(i)?.let(::add) } }
    }

    /**
     * Mood, genre and similar rows: your services lead and Plex fills gaps. More Plex lets Plex in every
     * third card (never first); Balanced lets in a couple, later in the row; More services only uses Plex
     * when the services can't fill the row.
     */
    fun servicesFirst(services: List<Title>, plex: List<Title>, balance: FeedBalance, target: Int = 16): List<Title> {
        val svc = services.take(target)
        val gap = (target - svc.size).coerceAtLeast(0)
        val (every, cap) = when (balance) {
            FeedBalance.MORE_PLEX -> 3 to target / 3
            FeedBalance.BALANCED -> 5 to 2
            FeedBalance.MORE_SERVICES -> 0 to 0
        }
        val extra = plex.take(maxOf(cap, gap))
        if (every == 0 || extra.isEmpty()) return (svc + extra).take(target)
        val out = mutableListOf<Title>()
        var si = 0; var pi = 0
        // Plex fills gaps: never the first card, then one every `every` cards, then whatever is left over.
        while (out.size < target && (si < svc.size || pi < extra.size)) {
            val plexTurn = out.isNotEmpty() && (out.size + 1) % every == 0 && pi < extra.size
            if ((plexTurn || si >= svc.size) && pi < extra.size) out += extra[pi++] else if (si < svc.size) out += svc[si++] else break
        }
        return out
    }

    /** "On Netflix, Hulu and Max" for a row's subtitle. */
    fun servicesPhrase(items: List<Title>): String {
        val names = items.mapNotNull { t -> t.availability.filter { it.kind != AvailKind.PLEX }.maxByOrNull { it.score }?.name }.distinct()
        return when (names.size) {
            0 -> ""
            1 -> "On ${names[0]}"
            2 -> "On ${names[0]} and ${names[1]}"
            3 -> "On ${names[0]}, ${names[1]} and ${names[2]}"
            else -> "On ${names[0]}, ${names[1]}, ${names[2]} and more of your services"
        }
    }
}
