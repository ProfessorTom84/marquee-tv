package app.marquee.tv

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Row as DataRow
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.news.NewsCategory
import app.marquee.tv.news.NewsSection
import app.marquee.tv.news.Story
import app.marquee.tv.recs.FeedLayout
import app.marquee.tv.recs.HomeData
import app.marquee.tv.system.TvApp
import app.marquee.tv.ui.components.LiveSky
import app.marquee.tv.ui.components.SkyRendering
import app.marquee.tv.ui.home.AppsRow
import app.marquee.tv.ui.home.DashboardPage
import app.marquee.tv.ui.home.TopActions
import app.marquee.tv.ui.theme.MarqueeTheme
import app.marquee.tv.weather.Sky
import app.marquee.tv.weather.Weather
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the real Dashboard with the remote in use, for looking at (not a test of anything): the page is run on
 * a simulated TV, buttons are pressed, and what is on screen is saved as a PNG.
 *
 * Only runs when asked, and on its own (it draws with a different engine from the screenshot tests, and the two
 * can't share a process):  MARQUEE_PREVIEW=<folder> ./gradlew :app:testDebugUnitTest --tests "*HomePreview*"
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomePreview {
    @get:Rule(order = 0) val tv = object : TestWatcher() {
        override fun starting(description: Description) {
            System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
            val app: Application = ApplicationProvider.getApplicationContext()
            // A TV is driven by a remote, not by touch: only then can things that are clicked also take focus.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            shadowOf(app.packageManager).apply {
                setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
                addOrUpdateActivity(ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName })
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    /** Where the pictures go. (The build script leaves this class out of every run unless MARQUEE_PREVIEW is set.) */
    private val out by lazy {
        File(checkNotNull(System.getenv("MARQUEE_PREVIEW")?.takeIf { it.isNotBlank() }) { "set MARQUEE_PREVIEW to a folder to make the pictures" }).also { it.mkdirs() }
    }

    private val plex = Availability(AvailKind.PLEX, "Living Room", quality = listOf("4K", "HDR10"), score = 120)
    private fun on(service: String) = Availability(AvailKind.INCLUDED, service, 1, score = 50)
    private fun t(name: String, a: Availability, minutes: Int? = null, badge: String? = null, progress: Float? = null) =
        Title(name, Source.STREAMING, name, availability = listOf(a), runtimeMin = minutes, badge = badge, progress = progress)
    private fun row(key: String, title: String, vararg items: Title) = DataRow(key, title, "", items.toList())

    private val home = HomeData(null, listOf(
        row(FeedLayout.FOR_YOU, "This afternoon for you",
            t("Dune: Part Two", plex, 166, "TRENDING · ON YOUR PLEX"), t("Shōgun", on("Hulu"), 58), t("Arcane", on("Netflix"), 41), t("Oppenheimer", plex, 180),
            t("Slow Horses", on("Apple TV+"), 47), t("Severance", on("Apple TV+"), 52), t("The Bear", on("Hulu"), 34), t("Andor", on("Disney+"), 45)),
        row(FeedLayout.CONTINUE, "Continue watching",
            t("The Holdovers", plex, 133, progress = 0.42f), t("Ripley", on("Netflix"), 55, progress = 0.7f), t("Past Lives", plex, 106, progress = 0.2f)),
        row(FeedLayout.PLEX, "From your Plex",
            t("Oppenheimer ", plex, 180, "UNWATCHED · 4K"), t("Past Lives ", plex, 106), t("Poor Things", plex, 141), t("Blade Runner 2049", plex, 164),
            t("The Holdovers ", plex, 133), t("Andor ", plex, 45), t("The Bear ", plex, 34), t("Tár", plex, 158)),
        row("svc:8:new", "New on Netflix",
            t("Ripley ", on("Netflix"), 55), t("Blue Eye Samurai", on("Netflix"), 45), t("The Diplomat", on("Netflix"), 50), t("3 Body Problem", on("Netflix"), 56),
            t("Beef", on("Netflix"), 35), t("Arcane ", on("Netflix"), 41), t("One Day", on("Netflix"), 30), t("The Gentlemen", on("Netflix"), 50)),
        row("svc:1899:popular", "Popular on Max", t("The Last of Us", on("Max"), 55), t("Hacks", on("Max"), 30), t("Succession", on("Max"), 60), t("The White Lotus", on("Max"), 58), t("Barry", on("Max"), 30)),
        row("because:1", "Because you watched Severance", t("Silo ", on("Apple TV+"), 50), t("Dark", on("Netflix"), 52), t("Devs", on("Hulu"), 50), t("Counterpart", on("Prime Video"), 55), t("Westworld", on("Max"), 60)),
        row("mood:comedy", "Easy comedies", t("Ted Lasso", on("Apple TV+"), 32), t("Abbott Elementary", on("Hulu"), 22), t("Schitt's Creek", on("Netflix"), 22), t("Parks and Recreation", on("Peacock"), 22), t("Brooklyn Nine-Nine", on("Netflix"), 22)),
        row(FeedLayout.YOUTUBE, "New from your subscriptions", t("Why the sky is blue", on("YouTube"), 12), t("A week in Kyoto", on("YouTube"), 18), t("Restoring a 1964 Mustang", on("YouTube"), 24), t("The physics of curveballs", on("YouTube"), 9), t("Sourdough from scratch", on("YouTube"), 15)),
        row("mood:thriller", "Gripping thrillers", t("Gone Girl", on("Max"), 149), t("Prisoners", on("Netflix"), 153), t("Zodiac", on("Paramount+"), 157), t("Nightcrawler", on("Netflix"), 117), t("Sicario", on("Hulu"), 121)),
        row(FeedLayout.TRENDING, "Trending on your services", t("Fallout", on("Prime Video"), 60), t("Shrinking", on("Apple TV+"), 33), t("Silo", on("Apple TV+"), 50), t("Reacher", on("Prime Video"), 48), t("Loki", on("Disney+"), 50)),
    ), emptyList())

    private val weather = Weather(61, 64, 47, Sky.PARTLY, true, "Partly cloudy", emptyList(), 0)
    private val news = listOf(
        NewsSection(NewsCategory.LOCAL, "Brooklyn", listOf(Story(NewsCategory.LOCAL, "City council approves new waterfront park after two-year fight", "The Local Times", "https://example.com/1", System.currentTimeMillis() - 14 * 60_000, null, ""))),
        NewsSection(NewsCategory.NATIONAL, "", listOf(Story(NewsCategory.NATIONAL, "Senate passes infrastructure bill", "PBS NewsHour", "https://example.com/2", System.currentTimeMillis() - 50 * 60_000, null, ""))),
        NewsSection(NewsCategory.TECH, "", listOf(Story(NewsCategory.TECH, "A new chip promises laptops that last two days", "Ars Technica", "https://example.com/3", System.currentTimeMillis() - 90 * 60_000, null, ""))),
    )
    private val apps = listOf("Plex", "Netflix", "Max", "Hulu", "Disney+", "Prime Video", "TizenTube", "Apple TV").map { TvApp(it, "pkg.$it", null, null) }
    private val settings = app.marquee.tv.data.Settings().let { s -> s.copy(profiles = s.profiles.mapIndexed { i, p -> if (i == 0) p.copy(name = "Tom") else p }) }

    private fun settle(ms: Long = 1500) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun press(key: Key, times: Int = 1) = repeat(times) { compose.onRoot().performKeyInput { pressKey(key) }; settle(700) }
    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        var result = -1
        // Let Android draw what Compose has changed since its last frame, then copy the screen.
        compose.runOnIdle { view.invalidate() }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(120))
        compose.runOnIdle {
            android.view.PixelCopy.request(compose.activity.window, bmp, { result = it }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        check(result == android.view.PixelCopy.SUCCESS) { "couldn't copy the screen ($result)" }
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private var shown: HomeData? = home

    private fun show(sky: Sky, hour: Int, minute: Int) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        SkyRendering.synchronous = true
        SkyRendering.stillTime = 40f
        compose.mainClock.autoAdvance = false
        val date = java.time.LocalDate.of(2026, 10, 10)
        compose.setContent {
            MarqueeTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveSky(sky, null, latitude = 40.99, longitude = -73.78, date = date, now = java.time.LocalTime.of(hour, minute), grain = true,
                        overlay = Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)))
                    DashboardPage(
                        shown, MutableSharedFlow(), onOpen = {},
                        topBar = {
                            androidx.tv.material3.Text("%d:%02d".format(if (hour % 12 == 0) 12 else hour % 12, minute), style = app.marquee.tv.ui.theme.T.serif(52.sp))
                            androidx.compose.foundation.layout.Column(Modifier.padding(start = 14.dp)) {
                                androidx.tv.material3.Text(if (hour < 12) "Good morning, Tom" else if (hour < 17) "Good afternoon, Tom" else "Good evening, Tom", style = app.marquee.tv.ui.theme.T.sans(14.sp, androidx.compose.ui.text.font.FontWeight.SemiBold))
                                androidx.tv.material3.Text("Saturday, October 10 · ${weather.summary.lowercase()}", style = app.marquee.tv.ui.theme.T.sans(10.sp, color = app.marquee.tv.ui.theme.C.Cool))
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            TopActions(settings, {}, {}, {}, {})
                        },
                        cards = {
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 48.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                app.marquee.tv.ui.home.WeatherCard(weather, "Brooklyn", true, Modifier.width(360.dp).fillMaxHeight())
                                app.marquee.tv.ui.home.NewsCard(news, false, Modifier.weight(1f).fillMaxHeight()) {}
                            }
                        },
                        apps = { AppsRow(apps, {}, {}, null) },
                        empty = { androidx.tv.material3.Text("Finding tonight's picks…", style = app.marquee.tv.ui.theme.T.rowTitle, modifier = Modifier.padding(start = 48.dp, top = 20.dp)) },
                    )
                }
            }
        }
        settle()
    }

    @Test fun beforeThereAreAnyPicks() {
        shown = null
        show(Sky.RAIN, 19, 30)
        save("8-no-picks-yet")
    }

    @Test fun atRestAndAFewRowsDown() {
        show(Sky.PARTLY, 14, 14)
        save("1-at-rest")
        press(Key.DirectionRight, 2); save("2-along-the-row")
        press(Key.DirectionDown); save("3-the-apps")
        press(Key.DirectionDown, 3); save("4-three-rows-down")
        press(Key.DirectionUp, 5); save("5-the-cards")
    }
}
