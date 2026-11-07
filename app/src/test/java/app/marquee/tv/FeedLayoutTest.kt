package app.marquee.tv

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.FeedBalance
import app.marquee.tv.data.Row
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.recs.FeedLayout
import app.marquee.tv.recs.FeedLayout.Lane
import app.marquee.tv.recs.Recommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLayoutTest {
    private val services = listOf(8 to "Netflix", 15 to "Hulu", 1899 to "Max", 337 to "Disney+", 531 to "Paramount+")

    private fun svc(i: Int, pid: Int, name: String, score: Double = 1.0 - i / 100.0) =
        Title("tmdb:movie:$pid$i", Source.STREAMING, "$name $i", tmdbId = pid * 1000 + i, score = score,
            availability = listOf(Availability(AvailKind.INCLUDED, name, pid, score = 50)))
    private fun plex(i: Int) = Title("plex:$i", Source.PLEX, "Plex $i", score = 0.9 - i / 100.0,
        availability = listOf(Availability(AvailKind.PLEX, "Plex", score = 100)))
    private fun yt(i: Int) = Title("yt:$i", Source.YOUTUBE, "Video $i", mediaType = "video", youtubeId = "v$i", score = 0.6)
    private fun cont(i: Int) = plex(100 + i).copy(id = "plex:c$i", reason = FeedLayout.RESUME_REASON)
    private fun row(key: String, n: Int = 6) = Row(key, key, items = (0 until n).map { plex(it) })

    private fun allRows() = buildList {
        add(row(FeedLayout.FOR_YOU, 16)); add(row(FeedLayout.CONTINUE)); add(row(FeedLayout.YOUTUBE)); add(row(FeedLayout.PLEX, 20))
        add(row(FeedLayout.TRENDING)); add(row(FeedLayout.TOP_RATED)); add(row("occ:halloween"))
        services.forEach { (id, _) -> listOf("top10", "new", "popular").forEach { add(row("svc:$id:$it")) } }
        (1..3).forEach { add(row("because:movie:$it")) }
        (1..5).forEach { add(row("mood:m$it")) }
        // Rows from an older cached home screen.
        add(row("hub:top:0")); add(row("coll:12")); add(row("added")); add(row("new"))
    }

    private fun plexRows(rows: List<Row>) = rows.count { it.key == FeedLayout.CONTINUE || it.key == FeedLayout.PLEX || it.key.startsWith("hub:") || it.key.startsWith("coll:") || it.key == "added" }

    @Test fun plexIsCappedAtTwoRows() {
        FeedBalance.entries.forEach { b ->
            (0..10).forEach { turn ->
                val out = FeedLayout.arrange(allRows(), b, turn)
                assertEquals("$b turn $turn", 2, plexRows(out))
                assertEquals(FeedLayout.FOR_YOU, out.first().key)
                assertEquals(FeedLayout.CONTINUE, out[1].key)
            }
        }
    }

    @Test fun twoOrThreeServicesPerVisitTakingTurns() {
        val seen = mutableMapOf<Int, Int>()
        (0 until 10).forEach { turn ->
            val out = FeedLayout.arrange(allRows(), FeedBalance.BALANCED, turn)
            val svc = out.filter { it.key.startsWith(FeedLayout.SVC) }
            assertTrue(svc.size in 2..3)
            val providers = svc.map { FeedLayout.providerOf(it.key)!! }
            assertEquals("one row per service", providers.size, providers.toSet().size)
            providers.forEach { seen[it] = (seen[it] ?: 0) + 1 }
        }
        // Over ten visits every service gets its turn, and none takes over.
        assertEquals(services.size, seen.size)
        assertTrue(seen.values.max() - seen.values.min() <= 1)
        assertEquals(3, FeedLayout.arrange(allRows(), FeedBalance.MORE_SERVICES, 1).count { it.key.startsWith(FeedLayout.SVC) })
        assertEquals(2, FeedLayout.arrange(allRows(), FeedBalance.MORE_PLEX, 0).count { it.key.startsWith(FeedLayout.SVC) })
    }

    @Test fun feedChangesBetweenVisits() {
        val a = FeedLayout.arrange(allRows(), FeedBalance.BALANCED, 4, visit = 1)
        val b = FeedLayout.arrange(allRows(), FeedBalance.BALANCED, 5, visit = 2)
        assertNotEquals(a.map { it.key }, b.map { it.key })
        assertNotEquals(a.first { it.key == FeedLayout.PLEX }.items.first().id, b.first { it.key == FeedLayout.PLEX }.items.first().id)
        assertTrue(a.count { it.key.startsWith(FeedLayout.BECAUSE) } <= 2)
        assertEquals(3, a.count { it.key.startsWith(FeedLayout.MOOD) })
    }

    @Test fun morePlexMovesPlexUp() {
        val more = FeedLayout.arrange(allRows(), FeedBalance.MORE_PLEX, 0).map { it.key }
        val fewer = FeedLayout.arrange(allRows(), FeedBalance.MORE_SERVICES, 0).map { it.key }
        assertEquals(2, more.indexOf(FeedLayout.PLEX))
        assertTrue(fewer.indexOf(FeedLayout.PLEX) > more.indexOf(FeedLayout.PLEX) + 5)
    }

    private fun pool() = buildList {
        services.forEach { (id, name) -> (0 until 8).forEach { add(svc(it, id, name, score = 0.95 - it * 0.05 - id / 100000.0)) } }
        // Netflix dominates on score, which is exactly when a naive ranking would show it back to back.
        (0 until 8).forEach { add(svc(50 + it, 8, "Netflix", score = 2.0)) }
        (0 until 10).forEach { add(plex(it)) }
        (0 until 6).forEach { add(yt(it)) }
        (0 until 4).forEach { add(cont(it)) }
    }.sortedByDescending { it.score }

    @Test fun forYouMatchesTheBalancedShares() {
        val out = FeedLayout.forYou(pool(), FeedBalance.BALANCED)
        assertEquals(16, out.size)
        val lanes = out.groupingBy(FeedLayout::laneOf).eachCount()
        assertEquals(8, lanes[Lane.SVC])   // 50%
        assertEquals(3, lanes[Lane.PLEX])  // 20%
        assertTrue(lanes[Lane.YT] in 2..3) // 15%
        assertTrue(lanes[Lane.CONT] in 2..3)
    }

    @Test fun noServiceTwiceInARow() {
        FeedBalance.entries.forEach { b ->
            val out = FeedLayout.forYou(pool(), b)
            val ring = out + out.first() // the row rotates between visits, so the ends meet too
            ring.zipWithNext().forEach { (x, y) ->
                val sx = FeedLayout.serviceOf(x); val sy = FeedLayout.serviceOf(y)
                assertFalse("$b: ${x.title} then ${y.title}", sx != null && sx == sy)
            }
            // Services take turns rather than the highest-scoring one taking over.
            val counts = out.mapNotNull(FeedLayout::serviceOf).groupingBy { it }.eachCount()
            assertTrue("$b: $counts", counts.values.max() - counts.values.min() <= 1)
        }
    }

    @Test fun balanceShiftsTheMix() {
        fun plexShare(b: FeedBalance) = FeedLayout.forYou(pool(), b).count { FeedLayout.laneOf(it) == Lane.PLEX }
        assertTrue(plexShare(FeedBalance.MORE_PLEX) > plexShare(FeedBalance.BALANCED))
        assertTrue(plexShare(FeedBalance.BALANCED) > plexShare(FeedBalance.MORE_SERVICES))
    }

    @Test fun servicesLeadMoodRowsAndPlexFillsGaps() {
        val svcList = (0 until 20).map { svc(it, services[it % 5].first, services[it % 5].second) }
        val plexList = (0 until 10).map { plex(it) }
        val full = FeedLayout.servicesFirst(svcList, plexList, FeedBalance.MORE_SERVICES)
        assertTrue(full.none { it.source == Source.PLEX })
        val balanced = FeedLayout.servicesFirst(svcList, plexList, FeedBalance.BALANCED)
        assertEquals(2, balanced.count { it.source == Source.PLEX })
        assertTrue(balanced.take(4).none { it.source == Source.PLEX })
        val more = FeedLayout.servicesFirst(svcList, plexList, FeedBalance.MORE_PLEX)
        assertEquals(Source.STREAMING, more.first().source)
        assertTrue(more.count { it.source == Source.PLEX } >= 5)
        // Few service titles: Plex fills the gap even on More services.
        val thin = FeedLayout.servicesFirst(svcList.take(6), plexList, FeedBalance.MORE_SERVICES)
        assertEquals(16, thin.size)
        assertTrue(thin.take(6).all { it.source == Source.STREAMING })
    }

    @Test fun spreadAndPhrase() {
        val list = (0 until 6).map { svc(it, 8, "Netflix") } + (0 until 2).map { svc(it, 15, "Hulu") }
        val spread = FeedLayout.spreadByService(list)
        assertEquals(listOf("Netflix", "Hulu", "Netflix", "Hulu", "Netflix"), spread.take(5).map { FeedLayout.serviceOf(it) })
        assertEquals("On Netflix and Hulu", FeedLayout.servicesPhrase(spread))
    }

    @Test fun pickTogetherFollowsTheBalance() {
        val s = (0 until 12).map { svc(it, 8, "Netflix") }
        val p = (0 until 12).map { plex(it) }
        fun firstSix(b: FeedBalance) = Recommender.togetherMix(s, p, b).take(6).count { it.source == Source.PLEX }
        assertEquals(3, firstSix(FeedBalance.MORE_PLEX))
        assertEquals(2, firstSix(FeedBalance.BALANCED))
        assertEquals(1, firstSix(FeedBalance.MORE_SERVICES))
    }
}
