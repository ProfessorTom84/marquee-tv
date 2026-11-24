package app.marquee.tv

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import app.marquee.tv.data.Row as DataRow
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.recs.FeedLayout
import app.marquee.tv.recs.HomeData
import app.marquee.tv.ui.home.DashboardPage
import app.marquee.tv.ui.home.rememberHomeListState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Dashboard's real page on a simulated 1080p Android TV: remote buttons are pressed and new picks arrive, and
 * each test checks which card is highlighted and whether the top of the page is still in view.
 *
 * On a TV, Compose scrolls whatever takes focus to about a third of the way down the screen. That, and focus being
 * handed to the nearest thing when the highlighted card disappears, is what these guard against.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeFocusTest {
    @get:Rule(order = 0) val tv = object : TestWatcher() {
        override fun starting(description: Description) {
            val app: Application = ApplicationProvider.getApplicationContext()
            // A TV is driven by a remote, not by touch: only then can things that are clicked also take focus.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            shadowOf(app.packageManager).apply {
                setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
                addOrUpdateActivity(ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = app.packageName })
            }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun row(key: String, vararg ids: String) = DataRow(key, "Row $key", "", ids.map { Title(it, Source.STREAMING, "Title $it") })
    private fun cards(prefix: String, n: Int = 8) = Array(n) { "$prefix${it + 1}" }
    private fun home(vararg rows: DataRow) = HomeData(null, rows.toList(), emptyList())

    /** "For you" (a1…a8) and six more rows (b…g), eight cards each. */
    private val first = home(
        row(FeedLayout.FOR_YOU, *cards("a")), row("svc:8:new", *cards("b")), row("mood:comedy", *cards("c")),
        row("plex", *cards("d")), row("trending", *cards("e")), row("toprated", *cards("f")), row("mood:thriller", *cards("g")),
    )

    private var data by mutableStateOf<HomeData?>(null)
    private val homeButton = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var page: LazyListState

    private fun show(start: HomeData?) {
        data = start
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Stand-ins for the top bar, the cards and the apps, the same heights as the real ones.
            DashboardPage(
                data, homeButton, onOpen = {},
                topBar = { Button("Pick together", 120, 62); Button("Search", 60, 62) },
                cards = { Row(Modifier.padding(horizontal = 48.dp)) { Button("Weather", 240, 128); Button("News", 500, 128) } },
                apps = { Row(Modifier.padding(horizontal = 48.dp)) { Button("Netflix", 150, 96); Button("Plex", 150, 96) } },
                empty = { Text("Finding tonight's picks") },
                listState = rememberHomeListState().also { page = it },
            )
        }
        settle()
    }

    @androidx.compose.runtime.Composable
    private fun Button(label: String, w: Int, h: Int) = Box(Modifier.size(w.dp, h.dp).clickable {}) { Text(label) }

    /** Lets a change reach the screen, then runs the clock (the page waits a moment before it takes focus or settles). */
    private fun settle(ms: Long = 1500) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun press(key: Key, times: Int = 1) = repeat(times) { compose.onRoot().performKeyInput { pressKey(key) }; settle(600) }

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString("|") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.firstOrNull()?.text ?: "?"
    }
    private fun assertOn(label: String) = assertEquals("the highlight", label, focused())
    private fun assertAtTop() = assertEquals("the page is at the very top", 0 to 0, compose.runOnIdle { page.firstVisibleItemIndex to page.firstVisibleItemScrollOffset })

    @Test fun opensAtTheTopOnTheFirstCard() {
        show(first)
        assertOn("Title a1"); assertAtTop()
    }

    @Test fun picksArrivingAfterAnEmptyStartStillOpenAtTheTop() {
        show(null)
        data = first; settle()
        assertOn("Title a1"); assertAtTop()
    }

    @Test fun movingAlongTheFirstRowKeepsTheTopInView() {
        show(first)
        press(Key.DirectionRight, 3)
        assertOn("Title a4"); assertAtTop()
    }

    @Test fun downTheRowsAndBackUpEndsAtTheTop() {
        show(first)
        press(Key.DirectionDown) // the apps
        assertOn("Netflix")
        press(Key.DirectionDown, 2)
        assertOn("Title c1")
        assertTrue("further down, the page has moved", compose.runOnIdle { page.firstVisibleItemIndex } > 0)
        press(Key.DirectionUp, 3)
        assertOn("Title a1"); assertAtTop()
    }

    @Test fun newPicksKeepYouOnTheFirstCard() {
        show(first)
        // The card that was first has gone altogether.
        data = home(row(FeedLayout.FOR_YOU, *cards("x")), *first.rows.drop(1).toTypedArray()); settle()
        assertOn("Title x1"); assertAtTop()
        // The card that was first is now third.
        data = home(row(FeedLayout.FOR_YOU, "x3", "x2", "x1", "x4", "x5", "x6"), *first.rows.drop(1).toTypedArray()); settle()
        assertOn("Title x3"); assertAtTop()
    }

    @Test fun newPicksLeaveYouOnACardThatIsStillThere() {
        show(first)
        press(Key.DirectionDown, 3); press(Key.DirectionRight)
        assertOn("Title c2")
        val before = compose.runOnIdle { page.firstVisibleItemIndex to page.firstVisibleItemScrollOffset }
        data = home(row(FeedLayout.FOR_YOU, *cards("x")), first.rows[1], row("mood:comedy", "c9", "c2", "c1", "c3"), *first.rows.drop(3).toTypedArray()); settle()
        assertOn("Title c2")
        assertEquals("and the page hasn't moved", before, compose.runOnIdle { page.firstVisibleItemIndex to page.firstVisibleItemScrollOffset })
    }

    @Test fun newPicksWithoutYourCardTakeYouBackToTheTop() {
        show(first)
        press(Key.DirectionDown, 3)
        assertOn("Title c1")
        // Its card has gone from the row…
        data = home(first.rows[0], first.rows[1], row("mood:comedy", "c5", "c6", "c7", "c8"), *first.rows.drop(3).toTypedArray()); settle()
        assertOn("Title a1"); assertAtTop()
        // …or the whole row has.
        press(Key.DirectionDown, 3)
        assertOn("Title c5")
        data = home(first.rows[0], first.rows[1], *first.rows.drop(3).toTypedArray()); settle()
        assertOn("Title a1"); assertAtTop()
    }

    @Test fun homeGoesBackToTheTop() {
        show(first)
        press(Key.DirectionDown, 4); press(Key.DirectionRight, 2)
        assertOn("Title d3")
        homeButton.tryEmit(Unit); settle()
        assertOn("Title a1"); assertAtTop()
    }

    @Test fun aRowComingBackIntoViewNeverTakesTheHighlight() {
        show(first)
        homeButton.tryEmit(Unit); settle() // one reset, as after any press of Home
        press(Key.DirectionDown, 7)      // far enough that "For you" is no longer kept built
        assertOn("Title g1")
        press(Key.DirectionUp, 3)        // …and back up towards it
        assertOn("Title d1")
        press(Key.DirectionUp, 4)
        assertOn("Title a1"); assertAtTop()
    }
}
