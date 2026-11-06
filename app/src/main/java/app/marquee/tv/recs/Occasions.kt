package app.marquee.tv.recs

import app.marquee.tv.weather.Sky
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters

/**
 * A themed ribbon that appears on its own when it fits the date, the weather or the time of day.
 * Picks come from your Plex library first (matched by TMDB keywords, genres, or your own collections),
 * then from what's included with your services.
 */
data class Occasion(
    val key: String,
    val title: String,
    val subtitle: String,
    val badge: String,                 // shown on cards in this ribbon and as the "reason" in Tonight
    val keywords: List<String> = emptyList(), // TMDB keyword names
    val genres: List<Int> = emptyList(),      // TMDB genre ids (used when there are no keywords, or to narrow them)
    val plexGenres: List<String> = emptyList(),
    val collectionWords: List<String> = emptyList(), // match your Plex collections by name
    val type: String = "movie",
    val maxRuntime: Int? = null,
    val priority: Int = 0,             // higher sorts first; seasonal holidays outrank everyday moods
)

object Occasions {
    private fun window(d: LocalDate, from: MonthDay, to: MonthDay): Boolean {
        val md = MonthDay.from(d)
        return if (!from.isAfter(to)) !md.isBefore(from) && !md.isAfter(to) else !md.isBefore(from) || !md.isAfter(to)
    }

    private fun thanksgiving(year: Int) = LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))

    fun active(now: LocalDateTime = LocalDateTime.now(), sky: Sky? = null): List<Occasion> {
        val d = now.toLocalDate()
        val out = mutableListOf<Occasion>()

        // ---- Seasons and holidays ----
        if (window(d, MonthDay.of(10, 1), MonthDay.of(10, 31))) out += Occasion(
            "halloween", if (d.dayOfMonth >= 24) "Halloween week" else "Spooky season",
            "Halloween favourites and scares you can watch free", "SPOOKY SEASON",
            keywords = listOf("halloween"), plexGenres = listOf("Horror"), collectionWords = listOf("halloween", "spooky", "horror"),
            priority = 100,
        )
        val tg = thanksgiving(d.year)
        if (!d.isBefore(tg.minusDays(10)) && !d.isAfter(tg.plusDays(2))) out += Occasion(
            "thanksgiving", "Thanksgiving with family", "Gather-round movies for the long weekend", "THANKSGIVING",
            keywords = listOf("thanksgiving"), genres = listOf(10751), plexGenres = listOf("Family"), collectionWords = listOf("thanksgiving"),
            priority = 90,
        )
        if (window(d, MonthDay.of(11, 24), MonthDay.of(12, 30)) && d.isAfter(tg)) out += Occasion(
            "holidays", if (d.monthValue == 12 && d.dayOfMonth in 20..25) "Christmas week" else "Holiday movies",
            "Festive picks from your library and your services", "HOLIDAY PICK",
            keywords = listOf("christmas", "holiday"), collectionWords = listOf("christmas", "holiday", "xmas"),
            priority = 100,
        )
        if (window(d, MonthDay.of(12, 27), MonthDay.of(1, 1))) out += Occasion(
            "newyear", "New Year's Eve", "Countdown crowd-pleasers", "NEW YEAR'S",
            keywords = listOf("new year's eve"), genres = listOf(35), priority = 80,
        )
        if (window(d, MonthDay.of(2, 1), MonthDay.of(2, 14))) out += Occasion(
            "valentines", "Valentine's", "Romance, from swoony to funny", "VALENTINE'S",
            keywords = listOf("valentine's day"), genres = listOf(10749), plexGenres = listOf("Romance"), collectionWords = listOf("valentine", "romance"),
            priority = 90,
        )
        if (window(d, MonthDay.of(2, 15), MonthDay.of(3, 15))) out += Occasion(
            "awards", "Awards season", "Acclaimed films you can watch tonight", "AWARDS SEASON",
            keywords = listOf("oscar (best picture)", "academy award"), plexGenres = listOf("Drama"), collectionWords = listOf("oscar", "academy award", "best picture"),
            priority = 60,
        )
        if (window(d, MonthDay.of(4, 28), MonthDay.of(5, 4))) out += Occasion(
            "maythe4th", "May the 4th", "A galaxy far, far away", "MAY THE 4TH",
            keywords = listOf("star wars"), collectionWords = listOf("star wars"), type = "any", priority = 85,
        )
        if (window(d, MonthDay.of(6, 15), MonthDay.of(8, 31))) out += Occasion(
            "summer", "Summer blockbusters", "Big-screen fun for long evenings", "SUMMER",
            genres = listOf(28, 12), plexGenres = listOf("Action", "Adventure"), collectionWords = listOf("summer"), priority = 40,
        )
        if (window(d, MonthDay.of(8, 15), MonthDay.of(9, 10))) out += Occasion(
            "school", "Back to school", "Campus comedies and coming-of-age classics", "BACK TO SCHOOL",
            keywords = listOf("high school", "coming of age"), genres = listOf(35, 18), priority = 40,
        )
        if (d.dayOfMonth == 13 && d.dayOfWeek == DayOfWeek.FRIDAY) out += Occasion(
            "friday13", "Friday the 13th", "Tempt fate", "FRIDAY THE 13TH", keywords = listOf("slasher"), plexGenres = listOf("Horror"), priority = 95,
        )

        // ---- Weather ----
        when (sky) {
            Sky.RAIN, Sky.STORM -> out += Occasion("rainy", "Rainy-night comfort", "It's pouring. Something warm and easy.", "RAINY NIGHT",
                genres = listOf(35, 10751, 10749), plexGenres = listOf("Comedy", "Family", "Romance"), priority = 50)
            Sky.SNOW -> out += Occasion("snowy", "Snowed in", "Cozy picks while it's snowing", "SNOW DAY",
                keywords = listOf("snow", "winter"), genres = listOf(10751), plexGenres = listOf("Family"), priority = 55)
            else -> {}
        }

        // ---- Time of week / day ----
        val h = now.hour
        when {
            (d.dayOfWeek == DayOfWeek.FRIDAY || d.dayOfWeek == DayOfWeek.SATURDAY) && h >= 17 -> out += Occasion(
                "movienight", "Big movie night", "Crowd-pleasers worth the big screen", "MOVIE NIGHT",
                genres = listOf(28, 12, 878), plexGenres = listOf("Action", "Adventure", "Science Fiction"), priority = 30)
            d.dayOfWeek == DayOfWeek.SUNDAY && h in 7..13 -> out += Occasion(
                "sunday", "Easy Sunday", "Light, feel-good and family friendly", "EASY SUNDAY",
                genres = listOf(10751, 16, 35), plexGenres = listOf("Family", "Animation", "Comedy"), priority = 30)
            h >= 22 || h < 2 -> out += Occasion(
                "latenight", "Before bed", "Half-hour episodes, nothing too heavy", "SHORT & LIGHT",
                genres = listOf(35), plexGenres = listOf("Comedy"), type = "tv", maxRuntime = 35, priority = 20)
            else -> {}
        }
        return out.sortedByDescending { it.priority }
    }

    /** Stable "random" for the day so rotating spotlights change daily but not on every refresh. */
    fun dailySeed(now: LocalDate = LocalDate.now()) = now.toEpochDay()
}
