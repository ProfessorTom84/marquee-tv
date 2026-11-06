package app.marquee.tv

import app.marquee.tv.recs.Occasions
import app.marquee.tv.weather.Sky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OccasionsTest {
    private fun keys(t: LocalDateTime, sky: Sky? = null) = Occasions.active(t, sky).map { it.key }

    @Test fun halloweenWeekLeadsOnOctober31() {
        val o = Occasions.active(LocalDateTime.of(2026, 10, 31, 19, 0))
        assertEquals("halloween", o.first().key)
        assertEquals("Halloween week", o.first().title)
    }

    @Test fun thanksgivingIsTheFourthThursday() {
        assertTrue("thanksgiving" in keys(LocalDateTime.of(2026, 11, 26, 12, 0)))
        assertFalse("holidays" in keys(LocalDateTime.of(2026, 11, 26, 12, 0)))
        assertTrue("holidays" in keys(LocalDateTime.of(2026, 11, 28, 12, 0)))
    }

    @Test fun newYearWindowWrapsTheYear() {
        assertTrue("newyear" in keys(LocalDateTime.of(2027, 1, 1, 20, 0)))
        assertTrue("newyear" in keys(LocalDateTime.of(2026, 12, 31, 20, 0)))
        assertFalse("newyear" in keys(LocalDateTime.of(2027, 1, 3, 20, 0)))
    }

    @Test fun weatherAndTimeOfDayRibbons() {
        assertTrue("rainy" in keys(LocalDateTime.of(2026, 4, 7, 20, 0), Sky.RAIN))
        assertTrue("movienight" in keys(LocalDateTime.of(2026, 4, 10, 20, 0)))   // a Friday evening
        assertTrue("latenight" in keys(LocalDateTime.of(2026, 4, 7, 23, 0)))
        assertTrue("friday13" in keys(LocalDateTime.of(2026, 11, 13, 21, 0)))
    }
}
