package app.marquee.tv

import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.together.Together
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherTest {
    private fun t(i: Int, g: Int) = Title("t$i", Source.STREAMING, "Title $i", genreIds = listOf(g), rating = 7.0 + (i % 100) / 100.0,
        availability = listOf(Availability(AvailKind.INCLUDED, "Netflix", 8)))

    @Test fun deckMatchesAndResults() = runBlocking {
        Together.newSession()
        val a = Together.join("Tom")!!; val b = Together.join("Sam")!!
        Together.setPrefs(a.id, setOf("funny"), 120, "movie")
        Together.setPrefs(b.id, setOf("funny", "scary"), 90, "any")
        val asked = mutableListOf<app.marquee.tv.recs.Recommender.Filters>()
        Together.buildDeck { f -> asked += f; (0 until 12).map { t(it + f.genres.first() * 100, f.genres.first()) } }
        val s = Together.session.value!!
        assertEquals(Together.Phase.VOTING, s.phase)
        // Shared mood first, shortest time wins, types disagree -> either.
        assertEquals(listOf(35), asked.first().genres)
        assertTrue(asked.all { it.maxRuntime == 90 && it.type == "any" })
        assertEquals(18, s.deck.size)
        val love = s.deck[3]; val yes = s.deck[5]
        s.deck.forEach { c ->
            Together.vote(a.id, c.id, if (c.id == love.id) 2 else if (c.id == yes.id) 1 else -1)
            Together.vote(b.id, c.id, if (c.id == love.id || c.id == yes.id) 1 else -1)
        }
        val end = Together.session.value!!
        assertEquals(Together.Phase.RESULTS, end.phase)
        assertEquals(2, end.matchCount)
        val ranked = Together.rank(end)
        assertEquals(love.id, ranked.first().title.id)
        assertEquals(listOf("Tom"), ranked.first().lovedBy)
        Together.end()
    }

    @Test fun roomIsCappedAtFour() {
        Together.newSession()
        repeat(4) { assertTrue(Together.join("P$it") != null) }
        assertEquals(null, Together.join("Fifth"))
        Together.end()
    }
}
