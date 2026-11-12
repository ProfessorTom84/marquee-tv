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
import app.marquee.tv.calendar.CalEvent
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.stats.AppShare
import app.marquee.tv.stats.WeekStats
import app.marquee.tv.system.UpNext
import app.marquee.tv.traffic.Drive
import app.marquee.tv.ui.components.GrainOverlay
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.Scrim
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.WeatherBackdrop
import app.marquee.tv.ui.home.AmbientContent
import app.marquee.tv.ui.home.CalendarCard
import app.marquee.tv.ui.home.HeadlinesCard
import app.marquee.tv.ui.home.WeatherCard
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
import java.time.ZonedDateTime

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
    private val now = ZonedDateTime.now()
    private val dinner = CalEvent("d", "Dinner at Lucia's", now.plusMinutes(48), now.plusMinutes(140), false, "Upper West Side, New York")
    private val up = UpNext(dinner, Drive(24, 18, 9.5, 0), now.plusMinutes(20))
    private val events = listOf(dinner,
        CalEvent("s", "Kids' soccer", now.plusDays(1).withHour(9).withMinute(0), now.plusDays(1).withHour(10), false, "Prospect Park"),
        CalEvent("f", "Flight to Chicago", now.plusDays(1).withHour(18).withMinute(15), now.plusDays(1).withHour(20), false, "LGA"))
    private val headlines = listOf(
        app.marquee.tv.news.Headline("City council approves new waterfront park after two-year fight", "The Local Times", "https://example.com/1", java.time.Instant.now().minusSeconds(14 * 60)),
        app.marquee.tv.news.Headline("Storm system to bring heavy rain to the coast this weekend", "Weather Desk", "https://example.com/2", java.time.Instant.now().minusSeconds(52 * 60)),
        app.marquee.tv.news.Headline("Underdogs win in overtime to reach the final", "Sports Daily", "https://example.com/3", java.time.Instant.now().minusSeconds(3 * 3600)),
    )
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

    @Test fun dashboard() = paparazzi.snapshot {
        MarqueeTheme {
            Box(Modifier.fillMaxSize()) {
                WeatherBackdrop(Sky.CLEAR, false)
                Scrim(Brush.verticalGradient(0f to Color(0x33040610), 0.35f to Color(0x59040610), 0.62f to Color(0xE0040610), 0.92f to Color(0xFF04060F)))
                GrainOverlay()
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                        Text("7:42", style = T.serif(52.sp))
                        Column(Modifier.padding(start = 14.dp, top = 10.dp)) {
                            Text("Good evening, Tom", style = T.sans(14.sp, FontWeight.SemiBold))
                            Text("Friday, October 30 · clear", style = T.sans(10.sp, color = C.Cool))
                        }
                        Spacer(Modifier.weight(1f))
                        PillButton("What should we watch?") {}
                    }
                    Row(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeatherCard(weather, "New York", true, Modifier.width(240.dp).fillMaxHeight())
                        CalendarCard(up, events, true, Modifier.width(320.dp).fillMaxHeight())
                        HeadlinesCard(headlines, app.marquee.tv.news.NewsSource.TOP, "", Modifier.weight(1f).fillMaxHeight())
                    }
                    SectionHeader("Tonight for you", "Trending and top-rated · only what you can watch free", Modifier.padding(start = 48.dp, top = 8.dp))
                    MediaRow(picks, 220.dp, 124.dp, onFocus = {}, onClick = {})
                }
            }
        }
    }

    @Test fun ambient() = paparazzi.snapshot {
        MarqueeTheme { AmbientContent(emptyList(), weather, up, interactive = true) }
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

    @Test fun skyDashboard() = paparazzi.snapshot {
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        MarqueeTheme {
            Box(Modifier.fillMaxSize()) {
                app.marquee.tv.ui.components.LiveSky(Sky.PARTLY, true, latitude = 40.9, now = java.time.LocalTime.of(16, 40),
                    sunset = java.time.LocalTime.of(17, 50), sunrise = java.time.LocalTime.of(7, 25), grain = true,
                    overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
                Column(Modifier.padding(start = 48.dp, top = 260.dp)) {
                    Text("This afternoon for you", style = T.rowTitle)
                    MediaRow(picks, 220.dp, 124.dp, onFocus = {}, onClick = {})
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

    private fun nightHome(orbs: Boolean) = paparazzi.snapshot {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        app.marquee.tv.ui.components.SkyRendering.synchronous = true
        app.marquee.tv.ui.components.SkyRendering.stillTime = 40f
        MarqueeTheme {
            Box(Modifier.fillMaxSize()) {
                app.marquee.tv.ui.components.LiveSky(Sky.CLEAR, false, latitude = 40.99, longitude = -73.78, date = java.time.LocalDate.of(2026, 11, 6),
                    now = java.time.LocalTime.of(21, 30), grain = true, orbs = orbs,
                    overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                        Text("9:30", style = T.serif(52.sp))
                        Column(Modifier.padding(start = 14.dp, top = 10.dp)) {
                            Text("Good evening, Tom", style = T.sans(14.sp, FontWeight.SemiBold))
                            Text("Friday, November 6 · clear", style = T.sans(10.sp, color = C.Cool))
                        }
                        Spacer(Modifier.weight(1f))
                        PillButton("Pick together") {}
                    }
                    Row(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeatherCard(weather, "Scarsdale", true, Modifier.width(240.dp).fillMaxHeight())
                        CalendarCard(up, events, true, Modifier.width(320.dp).fillMaxHeight())
                        HeadlinesCard(headlines, app.marquee.tv.news.NewsSource.TOP, "", Modifier.weight(1f).fillMaxHeight())
                    }
                    SectionHeader("Tonight for you", "A mix from your Plex, your services and YouTube", Modifier.padding(start = 48.dp, top = 8.dp))
                    MediaRow(picks, 220.dp, 124.dp, onFocus = {}, onClick = {})
                }
            }
        }
    }

    @Test fun nightHomeBefore() = nightHome(orbs = true)
    @Test fun nightHomeAfter() = nightHome(orbs = false)
}
