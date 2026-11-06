package app.marquee.tv

import app.marquee.tv.calendar.IcsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsParserTest {
    private val ny = ZoneId.of("America/New_York")
    private val from = ZonedDateTime.of(2026, 10, 30, 0, 0, 0, 0, ny)
    private val to = from.plusDays(14)

    private val ics = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:dinner
DTSTART;TZID=America/New_York:20261030T203000
DTEND;TZID=America/New_York:20261030T220000
SUMMARY:Dinner at Lucia's
LOCATION:123 Main St\, New York\, NY
END:VEVENT
BEGIN:VEVENT
UID:standup
DTSTART:20261026T140000Z
DTEND:20261026T141500Z
RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10
EXDATE:20261102T140000Z
SUMMARY:Standup
LOCATION:https://meet.google.com/abc-defg-hij
END:VEVENT
BEGIN:VEVENT
UID:holiday
DTSTART;VALUE=DATE:20261103
DTEND;VALUE=DATE:20261104
SUMMARY:Election Day
END:VEVENT
BEGIN:VEVENT
UID:long-line
DTSTART:20261031T150000Z
SUMMARY:A very long title that is folded
  across two lines
END:VEVENT
END:VCALENDAR
""".trimIndent()

    @Test fun parsesTimezonesLocationsAndRecurrence() {
        val events = IcsParser.parse(ics, ny, from, to)
        val dinner = events.first { it.uid == "dinner" }
        assertEquals(20, dinner.start.hour)
        assertEquals("123 Main St, New York, NY", dinner.location)
        assertTrue(dinner.needsTravel)

        val standups = events.filter { it.uid == "standup" }
        // Oct 30 (Fri), Nov 4, 6, 9, 11 (Wed/Fri/Mon/Wed) ... minus the Nov 2 exception, inside the 14-day window
        assertTrue(standups.isNotEmpty())
        assertFalse(standups.any { it.start.dayOfMonth == 2 && it.start.monthValue == 11 })
        assertFalse(standups.first().needsTravel)
        assertEquals(10, standups.first().start.hour) // 14:00Z is 10:00 in New York (EDT)

        val holiday = events.first { it.uid == "holiday" }
        assertTrue(holiday.allDay)

        assertEquals("A very long title that is folded across two lines", events.first { it.uid == "long-line" }.title)
    }
}
