package app.marquee.tv

import app.marquee.tv.ui.components.Creature
import app.marquee.tv.ui.components.Season
import app.marquee.tv.ui.components.Wildlife
import app.marquee.tv.weather.Sky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class WildlifeTest {
    private val october = LocalDate.of(2026, 10, 10)
    private val july = LocalDate.of(2026, 7, 10)
    private val january = LocalDate.of(2026, 1, 15)

    // The sun's height, in degrees.
    private val noon = 40f
    private val dusk = -2f
    private val midnight = -30f

    private val screens = listOf(1920f to 1080f, 3840f to 2160f, 1280f to 720f, 960f to 540f, 1440f to 1080f)

    private fun about(season: Season, sky: Sky, sun: Float, wind: Float = 0.2f, date: LocalDate = october) =
        Wildlife.odds(season, sky, sun, wind, date).keys

    @Test fun nothingFliesInAStorm() {
        for (season in Season.entries) for (sun in listOf(noon, dusk, midnight)) {
            assertTrue(about(season, Sky.STORM, sun).isEmpty())
            repeat(50) { assertNull(Wildlife.pick(Random(it), season, Sky.STORM, sun, 0.5f, october)) }
        }
    }

    @Test fun eachHourHasItsOwnCreatures() {
        val nocturnal = setOf(Creature.OWL, Creature.BATS)
        for (season in Season.entries) for (sky in Sky.entries) {
            assertTrue("$season $sky by day", about(season, sky, noon).none { it in nocturnal })
            assertTrue("$season $sky after dark", about(season, sky, midnight).all { it in nocturnal })
        }
        assertTrue(Creature.STARLINGS in about(Season.AUTUMN, Sky.CLEAR, dusk))
        assertFalse(Creature.STARLINGS in about(Season.AUTUMN, Sky.CLEAR, noon))
        assertTrue(Creature.OWL in about(Season.AUTUMN, Sky.CLEAR, midnight))
        assertTrue(Creature.SQUIRREL in about(Season.AUTUMN, Sky.CLEAR, noon))
        assertTrue("bats are about after dark too", Creature.BATS in about(Season.AUTUMN, Sky.CLEAR, midnight))
    }

    @Test fun seasonsAndWeatherMatter() {
        assertTrue(Creature.BUTTERFLIES in about(Season.SUMMER, Sky.CLEAR, noon, date = july))
        assertFalse("no butterflies in winter", Creature.BUTTERFLIES in about(Season.WINTER, Sky.CLEAR, noon, date = january))
        assertFalse("nor in rain", Creature.BUTTERFLIES in about(Season.SUMMER, Sky.RAIN, noon, date = july))
        assertFalse("nor in a strong wind", Creature.BUTTERFLIES in about(Season.SUMMER, Sky.CLEAR, noon, wind = 0.8f, date = july))
        assertTrue("monarchs pass in early autumn", Creature.BUTTERFLIES in about(Season.AUTUMN, Sky.CLEAR, noon, date = LocalDate.of(2026, 9, 20)))
        assertFalse("but are gone by November", Creature.BUTTERFLIES in about(Season.AUTUMN, Sky.CLEAR, noon, date = LocalDate.of(2026, 11, 20)))
        assertFalse("no bats in winter", Creature.BATS in about(Season.WINTER, Sky.CLEAR, midnight, date = january))
        assertTrue(Creature.HAWK in about(Season.SUMMER, Sky.CLEAR, noon, date = july))
        assertFalse("a hawk needs sun on the ground to soar", Creature.HAWK in about(Season.SUMMER, Sky.CLOUDY, noon, date = july))

        fun weight(c: Creature, season: Season, sun: Float, date: LocalDate) = Wildlife.odds(season, Sky.CLEAR, sun, 0.2f, date)[c] ?: 0f
        assertTrue("geese are likeliest in autumn", weight(Creature.GEESE, Season.AUTUMN, noon, october) > weight(Creature.GEESE, Season.SUMMER, noon, july))
        assertTrue("more bats in Halloween week", weight(Creature.BATS, Season.AUTUMN, midnight, LocalDate.of(2026, 10, 30)) > weight(Creature.BATS, Season.AUTUMN, midnight, october))
    }

    @Test fun rainKeepsMostThingsAway() {
        fun sightings(sky: Sky) = (0 until 400).count { Wildlife.pick(Random(it), Season.AUTUMN, sky, noon, 0.2f, october) != null }
        assertEquals(400, sightings(Sky.CLEAR))
        assertTrue(sightings(Sky.RAIN) in 120..280)
    }

    @Test fun whatIsPickedSuitsTheConditions() {
        for (season in Season.entries) for (sky in Sky.entries) for (sun in listOf(noon, dusk, midnight)) repeat(40) { seed ->
            val v = Wildlife.pick(Random(seed), season, sky, sun, 0.2f, october) ?: return@repeat
            assertTrue("${v.creature} in $season, $sky, sun at $sun", v.creature in about(season, sky, sun))
        }
    }

    @Test fun theWaitIsAMinuteOrTwoAndLongerAfterDark() {
        val r = Random(1)
        repeat(200) {
            assertTrue(Wildlife.gapMs(r, noon) in 45_000L..170_000L)
            assertTrue(Wildlife.gapMs(r, midnight) in 80_000L..260_000L)
        }
    }

    /** Nothing may appear or vanish in mid-air: every creature comes in from beyond an edge and leaves beyond one. */
    @Test fun everySightingStartsAndEndsOutOfView() {
        for (c in Creature.entries) for (season in Season.entries) for (seed in 0 until 60) {
            val v = Wildlife.of(c, seed * 7919 + 3, season)
            assertEquals(c, v.creature)
            assertTrue("$c lasts ${v.duration} s", v.duration in 1f..75f)
            for ((w, h) in screens) {
                val what = "$c, seed $seed, $season, ${w.toInt()}x${h.toInt()}"
                assertFalse("$what: in view as it starts", v.inView(0f, w, h))
                assertFalse("$what: in view as it ends", v.inView(v.duration, w, h))
                val seen = (1 until 40).any { j -> val t = v.duration * j / 40f; (0 until v.count).any { i -> val p = v.place(i, t, w, h); p.x in 0f..w && p.y in 0f..h } }
                assertTrue("$what: never comes into view", seen)
            }
        }
    }

    /** The lower part of the screen belongs to the rows of cards: creatures cross above it. */
    @Test fun creaturesKeepToTheUpperSky() {
        for (c in Creature.entries) for (seed in 0 until 60) {
            val v = Wildlife.of(c, seed * 31 + 7, Season.entries[seed % 4])
            for (j in 0..60) {
                val t = v.duration * j / 60f
                for (i in 0 until v.count) {
                    val p = v.place(i, t, 1920f, 1080f)
                    if (p.x in 0f..1920f) assertTrue("$c, seed $seed: at ${p.y.toInt()} of 1080 down the screen", p.y < 1080f * 0.62f)
                }
            }
        }
    }

    /** The performance check's parade: everything in it is in view from the first moment and through the step. */
    @Test fun theParadeFillsTheSkyForTheWholeStep() {
        for (season in Season.entries) {
            val parade = Wildlife.parade(season)
            assertEquals(listOf(Creature.GEESE, Creature.FLOCK, Creature.CROWS, Creature.STARLINGS), parade.map { it.first.creature })
            assertTrue("more birds than any one sighting", parade.sumOf { it.first.count } > 1200)
            for ((visit, from) in parade) for (later in listOf(0f, 3f, 6f)) {
                val t = from + later
                val seen = (0 until visit.count).count { i -> val p = visit.place(i, t, 1920f, 1080f); p.x in 0f..1920f && p.y in 0f..1080f }
                assertTrue("${visit.creature} $later s in: $seen of ${visit.count} in view", seen >= visit.count / 2 || (visit.creature == Creature.CROWS && seen >= 2))
            }
        }
    }

    @Test fun theSameSeedGivesTheSameFlight() {
        for (c in Creature.entries) {
            val a = Wildlife.of(c, 42, Season.AUTUMN); val b = Wildlife.of(c, 42, Season.AUTUMN)
            assertEquals(a.duration, b.duration, 0f)
            assertEquals(a.count, b.count)
            for (i in 0 until a.count) assertEquals(a.place(i, a.duration * 0.4f, 1920f, 1080f), b.place(i, b.duration * 0.4f, 1920f, 1080f))
        }
    }
}
