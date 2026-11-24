package app.marquee.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.stats.AppShare
import app.marquee.tv.stats.WeekStats
import app.marquee.tv.ui.components.GrainOverlay
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.Scrim
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.WeatherBackdrop
import app.marquee.tv.ui.home.AmbientContent
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.MarqueeTheme
import app.marquee.tv.ui.theme.T
import app.marquee.tv.weather.HourForecast
import app.marquee.tv.weather.Sky
import app.marquee.tv.weather.Weather
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        maxPercentDifference = 1.0,
        environment = app.cash.paparazzi.detectEnvironment().copy(compileSdkVersion = 34),
    )

    private val weather = Weather(54, 61, 47, Sky.CLEAR, false, "Clear",
        listOf(HourForecast("8 PM", 53, Sky.CLEAR), HourForecast("9 PM", 52, Sky.CLEAR), HourForecast("10 PM", 50, Sky.CLEAR), HourForecast("11 PM", 49, Sky.CLEAR), HourForecast("12 AM", 48, Sky.CLEAR)), 0)
    private val news = listOf(app.marquee.tv.news.NewsSection(app.marquee.tv.news.NewsCategory.LOCAL, "New York", listOf(
        app.marquee.tv.news.Story(app.marquee.tv.news.NewsCategory.LOCAL, "City council approves new waterfront park after two-year fight", "The Local Times",
            "https://example.com/1", System.currentTimeMillis() - 14 * 60_000, null, ""),
    )))
    @Suppress("unused") private val stats = WeekStats(580, 660, listOf(192, 60, 96, 48, 132, 0, 0), 4,
        listOf(AppShare("Plex", "p", 300, 52), AppShare("Netflix", "n", 160, 28), AppShare("TizenTube", "t", 120, 20)))

    private fun t(id: String, name: String, rating: String, a: Availability, badge: String? = null) = Title(
        id, Source.STREAMING, name, ratingLabel = rating, availability = listOf(a), badge = badge, reason = "Trending this week")
    private val plex = Availability(AvailKind.PLEX, "Living Room", quality = listOf("4K", "HDR10", "Dolby Atmos"), score = 120)
    private fun inc(n: String) = Availability(AvailKind.INCLUDED, n, 1, score = 50)
    private val picks = listOf(
        t("1", "Dune: Part Two", "IMDb 8.5", plex, "TRENDING · ON YOUR PLEX"),
        t("2", "Shōgun", "IMDb 8.6", inc("Hulu"), "#1 ON HULU"),
        t("3", "Arcane", "IMDb 9.0", inc("Netflix"), "#3 ON NETFLIX"),
        t("4", "Oppenheimer", "IMDb 8.3", plex),
        t("5", "The Bear", "IMDb 8.5", inc("Hulu"), "#2 ON HULU"),
    )

    /**
     * The Dashboard's real page over an afternoon sky, as it is before anything has focus (a screenshot can't press
     * the remote; HomePreview makes pictures with the highlight, and HomeFocusTest checks where it goes).
     */
    @Test fun dashboard() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 40f
        val date = java.time.LocalDate.of(2026, 10, 10); val time = java.time.LocalTime.of(14, 14)
        val day = Weather(61, 64, 47, Sky.PARTLY, true, "Partly cloudy", emptyList(), 0)
        fun row(key: String, title: String, items: List<Title>) = app.marquee.tv.data.Row(key, title, "", items)
        val home = app.marquee.tv.recs.HomeData(null, listOf(
            row(app.marquee.tv.recs.FeedLayout.FOR_YOU, "This afternoon for you", picks + picks.map { it.copy(id = it.id + "b", title = it.title + " II") }),
            row(app.marquee.tv.recs.FeedLayout.CONTINUE, "Continue watching", picks.take(3).map { it.copy(id = it.id + "c", progress = 0.4f) }),
            row(app.marquee.tv.recs.FeedLayout.PLEX, "From your Plex", picks.map { it.copy(id = it.id + "p") }),
        ), emptyList())
        val apps = listOf("Plex", "Netflix", "Max", "Hulu", "Disney+", "Prime Video", "TizenTube", "Apple TV").map { app.marquee.tv.system.TvApp(it, "pkg.$it", null, null) }
        val me = app.marquee.tv.data.Settings()
        paparazzi.snapshot {
            MarqueeTheme {
                Box(Modifier.fillMaxSize()) {
                    app.marquee.tv.ui.components.LiveSky(Sky.PARTLY, null, latitude = 40.99, longitude = -73.78, date = date, now = time, grain = true,
                        overlay = Brush.verticalGradient(0.41f to Color(0x00040610), 0.56f to Color(0xAD040610), 1f to Color(0xF2040610)))
                    app.marquee.tv.ui.home.DashboardPage(
                        home, kotlinx.coroutines.flow.MutableSharedFlow(), onOpen = {},
                        header = { app.marquee.tv.ui.home.DashboardHeader("Tom", day, true, java.time.LocalDateTime.of(date, time)) },
                        actions = { app.marquee.tv.ui.home.TopActions(me, {}, {}, {}, {}) },
                        news = { app.marquee.tv.ui.home.NewsLead(news, false) {} },
                        apps = { form, onHint -> app.marquee.tv.ui.home.AppsRow(apps, {}, {}, null, shelf = form, onHint = onHint) },
                        empty = {},
                    )
                }
            }
        }
    }

    @Test fun ambient() = paparazzi.snapshot {
        MarqueeTheme { AmbientContent(emptyList(), weather, interactive = true, now = java.time.LocalDateTime.of(2026, 10, 30, 19, 42)) }
    }

    @Test fun skies() = paparazzi.snapshot {
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        data class Case(val label: String, val sky: Sky, val time: java.time.LocalTime, val date: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 31))
        val cases = listOf(
            Case("Autumn · clear midday", Sky.CLEAR, java.time.LocalTime.of(12, 30)),
            Case("Partly cloudy · afternoon", Sky.PARTLY, java.time.LocalTime.of(15, 0)),
            Case("Clear · golden hour", Sky.CLEAR, java.time.LocalTime.of(17, 55)),
            Case("Partly cloudy · sunset", Sky.PARTLY, java.time.LocalTime.of(18, 25)),
            Case("Clear · blue hour", Sky.CLEAR, java.time.LocalTime.of(19, 10)),
            Case("Clear · night, waxing moon", Sky.CLEAR, java.time.LocalTime.of(22, 0), java.time.LocalDate.of(2026, 10, 24)),
            Case("Overcast", Sky.CLOUDY, java.time.LocalTime.of(13, 0)),
            Case("Rain", Sky.RAIN, java.time.LocalTime.of(11, 0)),
            Case("Storm", Sky.STORM, java.time.LocalTime.of(16, 0), java.time.LocalDate.of(2026, 7, 10)),
            Case("Snow", Sky.SNOW, java.time.LocalTime.of(10, 0), java.time.LocalDate.of(2026, 1, 15)),
            Case("Fog · morning", Sky.FOG, java.time.LocalTime.of(8, 0)),
            Case("Partly cloudy · night", Sky.PARTLY, java.time.LocalTime.of(23, 0), java.time.LocalDate.of(2026, 11, 2)),
            Case("Autumn · rain + leaves", Sky.RAIN, java.time.LocalTime.of(14, 0)),
            Case("Spring · petals", Sky.PARTLY, java.time.LocalTime.of(13, 0), java.time.LocalDate.of(2026, 4, 20)),
            Case("Summer night · fireflies", Sky.CLEAR, java.time.LocalTime.of(22, 30), java.time.LocalDate.of(2026, 7, 10)),
        )
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            cases.chunked(3).forEach { rowCases ->
                androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                    rowCases.forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            app.marquee.tv.ui.components.LiveSky(k.sky, null, latitude = 40.9, date = k.date, now = k.time, grain = true,
                                sunset = java.time.LocalTime.of(17, 50), sunrise = java.time.LocalTime.of(7, 25), windKph = 12.0)
                            Text(k.label, style = T.sans(9.sp, FontWeight.Bold), modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun autumnRain() = paparazzi.snapshot {
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 52f
        app.marquee.tv.ui.components.LiveSky(Sky.RAIN, true, latitude = 40.9, now = java.time.LocalTime.of(14, 0), date = java.time.LocalDate.of(2026, 10, 31),
            sunset = java.time.LocalTime.of(17, 50), sunrise = java.time.LocalTime.of(7, 25), grain = true, windKph = 18.0,
            overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
    }

    @Test fun autumnClear() = paparazzi.snapshot {
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 40f
        app.marquee.tv.ui.components.LiveSky(Sky.PARTLY, true, latitude = 40.9, now = java.time.LocalTime.of(15, 0), date = java.time.LocalDate.of(2026, 10, 31),
            sunset = java.time.LocalTime.of(17, 50), sunrise = java.time.LocalTime.of(7, 25), grain = true, windKph = 10.0,
            overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
    }

    @Test fun stormStrike() = paparazzi.snapshot {
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = (0..20000).map { it / 100f }.first { it > 30f && app.marquee.tv.ui.components.stormCycle(it).second >= 1f } + 0.02f
        app.marquee.tv.ui.components.LiveSky(Sky.STORM, true, latitude = 40.9, now = java.time.LocalTime.of(20, 30), date = java.time.LocalDate.of(2026, 7, 10),
            sunset = java.time.LocalTime.of(20, 20), sunrise = java.time.LocalTime.of(5, 35), grain = true, windKph = 25.0,
            overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
    }

    @Test fun sunsetSequence() = paparazzi.snapshot {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 40f
        val times = listOf(16 to 30, 17 to 15, 17 to 35, 17 to 48, 17 to 58, 18 to 10, 18 to 22, 18 to 40, 19 to 10)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            times.chunked(3).forEach { row ->
                androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                    row.forEach { (hh, mm) ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            val t = java.time.LocalTime.of(hh, mm)
                            app.marquee.tv.ui.components.LiveSky(Sky.PARTLY, null, latitude = 40.99, longitude = -73.78, date = java.time.LocalDate.of(2026, 10, 31), now = t, grain = true, animate = false)
                            val e = app.marquee.tv.weather.Solar.position(40.99, -73.78, java.time.ZonedDateTime.of(java.time.LocalDate.of(2026, 10, 31), t, java.time.ZoneId.of("America/New_York"))).elevation
                            Text("%d:%02d  sun %.1f°".format(hh - 12, mm, e), style = T.sans(10.sp, FontWeight.Bold), modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    /**
     * Wildlife, frozen part-way across the Dashboard's sky. Each sighting is (creature, seed, how far through its
     * crossing). In the app only one crosses at a time, a minute or two apart; here a few share the frame, and
     * between them the three pictures show all ten creatures.
     */
    private fun wildlife(sky: Sky, hour: Int, minute: Int, vararg sightings: Triple<app.marquee.tv.ui.components.Creature, Int, Float>) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        val date = java.time.LocalDate.of(2026, 10, 10)
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 40f
        app.marquee.tv.ui.components.SkyRendering.stillVisits = sightings.map { (creature, seed, at) ->
            val v = app.marquee.tv.ui.components.Wildlife.of(creature, seed, app.marquee.tv.ui.components.Season.AUTUMN)
            v to v.duration * at
        }
        try {
            paparazzi.snapshot {
                app.marquee.tv.ui.components.LiveSky(sky, null, latitude = 40.99, longitude = -73.78, date = date, now = java.time.LocalTime.of(hour, minute), grain = true,
                    overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
            }
        } finally {
            app.marquee.tv.ui.components.SkyRendering.stillVisits = emptyList() // or every later sky would show them too
        }
    }

    @Test fun wildlifeByDay() = wildlife(Sky.PARTLY, 14, 0,
        Triple(app.marquee.tv.ui.components.Creature.GEESE, 2, 0.5f), Triple(app.marquee.tv.ui.components.Creature.SQUIRREL, 11, 0.52f),
        Triple(app.marquee.tv.ui.components.Creature.SONGBIRDS, 6, 0.45f), Triple(app.marquee.tv.ui.components.Creature.HAWK, 1, 0.2f),
        Triple(app.marquee.tv.ui.components.Creature.FLOCK, 5, 0.3f), Triple(app.marquee.tv.ui.components.Creature.BUTTERFLIES, 0, 0.5f))

    @Test fun wildlifeAtDusk() = wildlife(Sky.CLEAR, 18, 30,
        Triple(app.marquee.tv.ui.components.Creature.STARLINGS, 7, 0.5f), Triple(app.marquee.tv.ui.components.Creature.CROWS, 4, 0.5f))

    @Test fun wildlifeAtNight() = wildlife(Sky.CLEAR, 22, 0,
        Triple(app.marquee.tv.ui.components.Creature.OWL, 2, 0.5f), Triple(app.marquee.tv.ui.components.Creature.BATS, 3, 0.45f))
}
