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
import app.marquee.tv.ui.home.ShelfTile
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
 * each test checks which tile is highlighted and which row is at the top of the shelf.
 *
 * The page shows two rows: the one you are on and the one after it. Down and Up change which row that is. These
 * also guard against the two ways focus used to go astray: Compose scrolling whatever takes focus a third of the
 * way down the screen, and focus being handed to the nearest thing when the highlighted tile disappears.
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

    /** "For you" (a1…a8) and six more rows (b…g), eight tiles each. On the shelf the apps come second. */
    private val first = home(
        row(FeedLayout.FOR_YOU, *cards("a")), row("svc:8:new", *cards("b")), row("mood:comedy", *cards("c")),
        row("plex", *cards("d")), row("trending", *cards("e")), row("toprated", *cards("f")), row("mood:thriller", *cards("g")),
    )

    private var data by mutableStateOf<HomeData?>(null)
    private val homeButton = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var shelf: LazyListState

    private fun show(start: HomeData?) {
        data = start
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Stand-ins for the clock, the buttons, the news and the apps; the rows of titles are the real ones.
            DashboardPage(
                data, homeButton, onOpen = {},
                header = { Text("7:42") },
                actions = { Row { Button("Search", 34, 34); Button("Settings", 34, 34) } },
                news = { Button("News", 520, 108) },
                apps = { form, _ -> Row(Modifier.padding(start = 48.dp)) { ShelfTile(form, onClick = {}) { Text("Netflix") }; ShelfTile(form, onClick = {}) { Text("Plex") } } },
                empty = { Text("Finding tonight's picks") },
                listState = rememberHomeListState().also { shelf = it },
            )
        }
        settle()
    }

    @androidx.compose.runtime.Composable
    private fun Button(label: String, w: Int, h: Int) = Box(Modifier.size(w.dp, h.dp).clickable {}) { Text(label) }

    /** Lets a change reach the screen, then runs the clock (the page waits a moment before it takes focus or settles). */
    private fun settle(ms: Long = 1500) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun press(key: Key, times: Int = 1, pause: Long = 600) = repeat(times) { compose.onRoot().performKeyInput { pressKey(key) }; settle(pause) }

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString("|") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.firstOrNull()?.text ?: "?"
    }
    private fun assertOn(label: String) = assertEquals("the highlight", label, focused())
    /** The row in focus sits exactly at the top of the shelf: [row] counts from 0 ("For you"), with the apps second. */
    private fun assertRow(row: Int) = assertEquals("the row at the top of the shelf", row to 0, compose.runOnIdle { shelf.firstVisibleItemIndex to shelf.firstVisibleItemScrollOffset })

    @Test fun opensOnTheFirstTile() {
        show(first)
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun picksArrivingAfterAnEmptyStartOpenOnTheFirstTile() {
        show(null)
        data = first; settle()
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun movingAlongARowMovesNothingElse() {
        show(first)
        press(Key.DirectionRight, 3)
        assertOn("Title a4"); assertRow(0)
    }

    /** Where a tile is across the screen: its left edge, in dp. */
    private fun leftOf(title: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(title)).fetchSemanticsNodes().single().boundsInRoot.left / 2f

    @Test fun theTileInFocusAlwaysSitsAtTheMargin() {
        show(home(row(FeedLayout.FOR_YOU, *cards("a", 5)), *first.rows.drop(1).toTypedArray())) // a short row: its end is close
        assertEquals(48f, leftOf("Title a1"), 0.5f)
        press(Key.DirectionRight, 2)
        assertOn("Title a3"); assertEquals(48f, leftOf("Title a3"), 0.5f)
        press(Key.DirectionUp)   // to the news: the tile shrinks where it is
        assertEquals(48f, leftOf("Title a3"), 0.5f)
        press(Key.DirectionDown, 3); press(Key.DirectionUp, 2) // away down the rows and back
        assertOn("Title a3"); assertEquals(48f, leftOf("Title a3"), 0.5f)
        press(Key.DirectionRight, 2)                           // the last tile in the row
        assertOn("Title a5"); assertEquals(48f, leftOf("Title a5"), 0.5f)
    }

    @Test fun downAndUpChangeTheRowYouAreOn() {
        show(first)
        press(Key.DirectionDown)
        assertOn("Netflix"); assertRow(1)
        press(Key.DirectionDown, 2)
        assertOn("Title c1"); assertRow(3)
        press(Key.DirectionRight, 2); press(Key.DirectionUp); press(Key.DirectionDown)
        assertOn("Title c3"); assertRow(3) // back on the tile you left
        press(Key.DirectionUp, 3)
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun holdingDownGoesStraightThroughTheRows() {
        show(first)
        press(Key.DirectionDown, 7, pause = 48) // faster than the shelf can follow
        settle()
        assertOn("Title g1"); assertRow(7)
        press(Key.DirectionDown) // the last row: nothing below it
        assertOn("Title g1"); assertRow(7)
        press(Key.DirectionUp, 7, pause = 48)
        settle()
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun upFromTheFirstRowReachesTheNewsAndTheButtons() {
        show(first)
        press(Key.DirectionRight); press(Key.DirectionUp)
        assertOn("News"); assertRow(0)
        press(Key.DirectionUp)
        assertTrue("a button at the top right, not ${focused()}", focused() in setOf("Search", "Settings"))
        press(Key.DirectionDown)
        assertOn("News")
        press(Key.DirectionDown)
        assertOn("Title a2"); assertRow(0)
    }

    @Test fun continueWatchingComesBeforeTheApps() {
        show(home(first.rows[0], row(FeedLayout.CONTINUE, *cards("k", 3)), *first.rows.drop(1).toTypedArray()))
        press(Key.DirectionDown)
        assertOn("Title k1"); assertRow(1)
        press(Key.DirectionDown)
        assertOn("Netflix"); assertRow(2)
    }

    @Test fun newPicksKeepYouOnTheFirstTile() {
        show(first)
        // The tile that was first has gone altogether.
        data = home(row(FeedLayout.FOR_YOU, *cards("x")), *first.rows.drop(1).toTypedArray()); settle()
        assertOn("Title x1"); assertRow(0)
        // The tile that was first is now third.
        data = home(row(FeedLayout.FOR_YOU, "x3", "x2", "x1", "x4", "x5", "x6"), *first.rows.drop(1).toTypedArray()); settle()
        assertOn("Title x3"); assertRow(0)
    }

    @Test fun newPicksLeaveYouOnATileThatIsStillThere() {
        show(first)
        press(Key.DirectionDown, 3); press(Key.DirectionRight)
        assertOn("Title c2"); assertRow(3)
        // Its row is now one further down, and the tile second in it.
        data = home(row(FeedLayout.FOR_YOU, *cards("x")), first.rows[1], first.rows[3], row("mood:comedy", "c9", "c2", "c1", "c3"), *first.rows.drop(4).toTypedArray()); settle()
        assertOn("Title c2"); assertRow(4)
    }

    @Test fun newPicksWithoutYourTileTakeYouBackToTheTop() {
        show(first)
        press(Key.DirectionDown, 3)
        assertOn("Title c1")
        // Its tile has gone from the row…
        data = home(first.rows[0], first.rows[1], row("mood:comedy", "c5", "c6", "c7", "c8"), *first.rows.drop(3).toTypedArray()); settle()
        assertOn("Title a1"); assertRow(0)
        // …or the whole row has.
        press(Key.DirectionDown, 3)
        assertOn("Title c5")
        data = home(first.rows[0], first.rows[1], *first.rows.drop(3).toTypedArray()); settle()
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun newPicksLeaveYouOnTheApps() {
        show(first)
        press(Key.DirectionDown); press(Key.DirectionRight)
        assertOn("Plex"); assertRow(1)
        data = home(row(FeedLayout.FOR_YOU, *cards("x")), *first.rows.drop(2).toTypedArray()); settle()
        assertOn("Plex"); assertRow(1)
    }

    @Test fun homeGoesBackToTheTop() {
        show(first)
        press(Key.DirectionDown, 4); press(Key.DirectionRight, 2)
        assertOn("Title d3"); assertRow(4)
        homeButton.tryEmit(Unit); settle()
        assertOn("Title a1"); assertRow(0)
    }

    @Test fun aRowComingBackIntoViewNeverTakesTheHighlight() {
        show(first)
        homeButton.tryEmit(Unit); settle() // one reset, as after any press of Home
        press(Key.DirectionDown, 7)        // far enough that "For you" is no longer kept built
        assertOn("Title g1")
        press(Key.DirectionUp, 3)          // …and back up towards it
        assertOn("Title d1"); assertRow(4)
        press(Key.DirectionUp, 4)
        assertOn("Title a1"); assertRow(0)
    }
}
