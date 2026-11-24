package app.marquee.tv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.Screen
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.HomeStyle
import app.marquee.tv.data.Row as DataRow
import app.marquee.tv.data.Title
import app.marquee.tv.player.PreviewPlayer
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.ArtBackdrop
import app.marquee.tv.ui.components.BakedBackdrop
import app.marquee.tv.ui.components.Chip
import app.marquee.tv.ui.components.Divider
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.GrainOverlay
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.OutlineTag
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.RatingChip
import app.marquee.tv.ui.components.Scrim
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.Slideshow
import app.marquee.tv.ui.components.WeatherBackdrop
import app.marquee.tv.ui.components.glass
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.time.ZonedDateTime

@Composable
fun HomeScreen(vm: MarqueeVm, nav: Nav) {
    val s by vm.settings.collectAsState()
    val ambient by vm.ambientActive.collectAsState()
    val now = rememberNow()
    val style = remember(s, ambient, now.hour) { vm.styleNow() }
    Crossfade(style, animationSpec = tween(700), label = "style") { st ->
        when (st) {
            HomeStyle.DASHBOARD -> DashboardHome(vm, nav)
            HomeStyle.CINEMATIC -> CinematicHome(vm, nav)
            HomeStyle.AMBIENT -> AmbientHome(vm, onWake = { vm.onInput() })
        }
    }
}

/**
 * The home screen's up-and-down list keeps a screen's worth of rows built above and below what you can see.
 * Measured on the Google TV Streamer, building a row as it scrolled into view (and rebuilding rows you'd
 * scrolled past) cost 35–55 ms of a 16 ms frame, two or three times a second: the hitches while scrolling.
 * With a cache window, upcoming rows are built ahead in spare time, a little per frame, and recent ones are kept.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun rememberHomeListState() = androidx.compose.foundation.lazy.rememberLazyListState(
    cacheWindow = androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f),
)

/**
 * Where focus is among the rows. Plain fields: written as focus moves, read when new picks arrive, never observed
 * (so moving between cards recomposes nothing).
 */
private class Place {
    var row: String? = null       // the row that has focus; null on the top bar, the cards, the apps, or nowhere
    var card: String? = null      // the card last focused; null right after going back to the top (the first card)
    var firstRow: String? = null  // the first row and its first card, as last shown
    var firstCard: String? = null
    fun focus(key: String, has: Boolean) { if (has) row = key else if (row == key) row = null }
}

/**
 * Fresh picks replace the rows a few seconds after a restart, each time you come back to Marquee, and while the TV
 * sits idle. If the card you were on is no longer in its row, Compose hands focus to whatever is nearest: usually a
 * row or two down, which scrolls the top of the page out of sight. So: on the first card you stay on the first card
 * (the new one); on a card that has gone you go back to the top; anywhere else, nothing moves.
 */
@Composable
private fun KeepPlace(home: app.marquee.tv.recs.HomeData?, firstRow: String?, place: Place, toTop: () -> Unit) {
    val top by androidx.compose.runtime.rememberUpdatedState(toTop)
    // Read while composing: the rows haven't been rebuilt yet, so this is still where focus was.
    val before = remember(home) {
        Place().also { it.row = place.row; it.card = place.card; it.firstRow = place.firstRow; it.firstCard = place.firstCard }
    }
    LaunchedEffect(home) {
        val rows = home?.rows.orEmpty()
        place.firstRow = firstRow
        place.firstCard = rows.firstOrNull { it.key == firstRow }?.items?.firstOrNull()?.id
        val row = before.row ?: return@LaunchedEffect
        val onFirstCard = row == before.firstRow && (before.card == null || before.card == before.firstCard)
        val stillThere = rows.any { r -> r.key == row && r.items.any { it.id == before.card } }
        if (onFirstCard || !stillThere) top()
    }
}

private fun cycleProfile(vm: MarqueeVm) = vm.app.store.update { it.copy(activeProfile = (it.activeProfile + 1) % it.profiles.size) }

private fun openTitle(nav: Nav, vm: MarqueeVm, t: Title) {
    if (t.youtubeId != null) vm.app.apps.openYouTube(t.youtubeId, vm.settings.value) else nav.go(Screen.Detail(t))
}

// ============================ Dashboard ============================

@Composable
fun DashboardHome(vm: MarqueeVm, nav: Nav) {
    val s by vm.settings.collectAsState()
    val home by vm.home.collectAsState()
    val weather by vm.weather.collectAsState()
    val news by vm.news.collectAsState()
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    val photo by vm.areaPhoto.collectAsState()
    val preferredYt = remember(s, apps) { vm.app.apps.preferredYouTube(s) }
    val orderedApps = remember(apps, s.services, s.appOrder, s.hiddenApps, preferredYt) { orderedApps(apps, s, preferredYt, vm.app.apps.plexPlayerPackage(s)) }
    val showNews = s.news && !s.profile.kidSafe

    Box(Modifier.fillMaxSize()) {
        // One calm background for everything: it never changes with the tile you're on.
        DashboardBackground(s.background, weather, photo)
        DashboardPage(
            home, vm.homeTaps, onOpen = { openTitle(nav, vm, it) },
            header = { DashboardHeader(s.profile.name, weather, s.hasLocation) },
            actions = { TopActions(s, { nav.go(Screen.Search) }, { nav.go(Screen.Settings()) }, { vm.app.apps.openSystemSettings() }, { cycleProfile(vm) }) },
            news = if (showNews) ({ NewsLead(news, s.cityName.isBlank()) { nav.go(Screen.News(it)) } }) else null,
            apps = { form, onHint -> AppsRow(orderedApps, { openApp(vm, it) }, { nav.go(Screen.Apps) }, vm, shelf = form, onHint = onHint) },
            empty = { EmptyState(loading, s.tmdbKey.isBlank() && !s.plexReady, nav) },
        )
        if (s.background == "PHOTO" && photo != null) Text(
            "Photo · ${photo!!.photographer} on Unsplash", style = T.sans(8.sp, color = Color(0x99FFFFFF)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 8.dp),
        )
    }
}

/** The shelf moves only when the row in focus changes (see [DashboardPage]); a tile taking focus never scrolls it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val StayPut = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * The Dashboard's page. The clock, the weather on one line and the news sit at the top left, the buttons at the top
 * right, and the sky is left open. At the foot is the shelf: a line naming every row, the row you are on, and the row
 * after it (see Shelf.kt). Down and Up change which row that is; nothing above the shelf ever moves.
 *
 * It takes plain data and the pieces to show (no view model), so HomeFocusTest can run it on a simulated TV, press
 * remote buttons and check where the highlight ends up.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DashboardPage(
    home: app.marquee.tv.recs.HomeData?,
    homeTaps: kotlinx.coroutines.flow.Flow<Unit>,
    onOpen: (Title) -> Unit,
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    news: (@Composable () -> Unit)?,
    apps: @Composable (form: () -> Float, onHint: (String, Boolean) -> Unit) -> Unit,
    empty: @Composable () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberHomeListState(),
) {
    val rows = remember(home) { shelves(home) }
    val firstKey = rows.first().key
    val firstTitles = rows.firstOrNull { it is Shelf.Titles } as Shelf.Titles?
    val focusedId = remember { mutableStateOf<String?>(null) }
    val place = remember { Place() }
    val onFocus: (Title) -> Unit = remember { { t -> focusedId.value = t.id; place.card = t.id } }
    // The row you are on: the one with focus, or the one that last had it while focus is up on the news or the buttons.
    var lastRow by remember { mutableStateOf<String?>(null) }
    val current = lastRow?.takeIf { k -> rows.any { it.key == k } } ?: firstKey
    val at = rows.indexOfFirst { it.key == current }.coerceAtLeast(0)
    // The shelf follows the row you are on, and nothing else. (Until you have chosen a row it simply sits on the
    // first one: when picks arrive above the apps, it must not stay on the apps and slide.)
    LaunchedEffect(current, at) {
        if (listState.firstVisibleItemIndex == at && listState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
        if (lastRow == null) listState.scrollToItem(at) else listState.animateScrollToItem(at)
    }

    var resetTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back to the first row with its first tile in focus.
    fun backToTop() { resetTick++; focusedId.value = null; place.card = null; lastRow = null; scope.launch { listState.scrollToItem(0) } }
    // Home on the remote: back to the top (like Google TV).
    LaunchedEffect(Unit) { homeTaps.collect { backToTop() } }
    KeepPlace(home, firstTitles?.key, place) { backToTop() }
    // Back: first return to the top; at the top, Back starts the screensaver (handled in Root).
    val firstId = firstTitles?.data?.items?.firstOrNull()?.id
    val awayFromTop by remember(firstId, firstKey) {
        androidx.compose.runtime.derivedStateOf { (lastRow != null && lastRow != firstKey) || (focusedId.value != null && focusedId.value != firstId) }
    }
    androidx.activity.compose.BackHandler(enabled = awayFromTop) { backToTop() }
    val reset: Any? = resetTick.takeIf { it > 0 }
    var hint by remember { mutableStateOf("" to false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 48.dp, top = 28.dp)) { header() }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 40.dp)) { actions() }
        // With nothing to recommend yet, what to do about it takes the news' place.
        if (firstTitles == null) Box(Modifier.padding(top = 92.dp)) { empty() }
        else if (news != null) Box(Modifier.padding(start = 48.dp, top = 108.dp)) { news() }

        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 27.5.dp).height(ShelfSize.height + 4.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides StayPut) {
                LazyColumn(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(ShelfSize.height), state = listState,
                    // (Room for the last row to come to the top, with nothing under it.)
                    contentPadding = PaddingValues(bottom = ShelfSize.head + ShelfSize.nextTile),
                ) {
                    rows.forEachIndexed { i, shelf ->
                        item(key = shelf.key, contentType = if (shelf is Shelf.Apps) "apps" else "row") {
                            val here = shelf.key == current
                            when (shelf) {
                                is Shelf.Titles -> ShelfRow(shelf.title, here, Modifier.onFocusChanged { if (it.hasFocus) lastRow = shelf.key; place.focus(shelf.key, it.hasFocus) }) { rowT ->
                                    ShelfTitles(shelf.data.items, here, rowT, onFocus, onOpen, initialFocus = i == 0, resetKey = reset)
                                }
                                // (The apps aren't picks: when new picks arrive, focus on an app is left alone.)
                                Shelf.Apps -> ShelfRow(shelf.title, here, Modifier.onFocusChanged { if (it.hasFocus) lastRow = shelf.key }) { rowT ->
                                    apps(rowT) { text, strong -> hint = text to strong }
                                }
                            }
                        }
                    }
                }
            }
            // Over the top of the shelf, where the row in focus keeps a clear strip for its tile's glow.
            RowIndex(rows, current, Modifier.align(Alignment.TopStart).fillMaxWidth())
            if (hint.first.isNotBlank()) ShelfHint(hint.first, hint.second, Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 4.dp + ShelfSize.row + 8.dp))
        }
    }
}

private val HeaderShadow = androidx.compose.ui.graphics.Shadow(Color(0x8C040610), androidx.compose.ui.geometry.Offset(0f, 2f), 14f)

/**
 * Clock, greeting, and the date with the weather on one line. The only part of the Dashboard that changes on the
 * minute, so only it recomposes.
 */
@Composable
internal fun DashboardHeader(profileName: String, weather: app.marquee.tv.weather.Weather?, hasLocation: Boolean, now: java.time.LocalDateTime = rememberNow()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(clockText(now), style = T.serif(58.sp, spacing = (-0.5).sp).copy(shadow = HeaderShadow))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(greeting(now, profileName), style = T.sans(15.sp, FontWeight.SemiBold).copy(shadow = HeaderShadow))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val line = T.sans(11.sp, FontWeight.Medium, Color(0xFFE3E7F0)).copy(shadow = HeaderShadow)
                Text(dateText(now), style = line)
                val after = weather?.let { weatherLine(it, now.hour) } ?: "Add your city in Settings for the weather".takeIf { !hasLocation }
                if (after != null) {
                    Box(Modifier.size(2.5.dp).clip(CircleShape).background(C.Cool))
                    if (weather != null) SkyIcon(weather.sky, weather.isDay, 13)
                    Text(after, style = line)
                }
            }
        }
    }
}

/** "61° and partly cloudy, 47° tonight": the weather as one line. */
internal fun weatherLine(w: app.marquee.tv.weather.Weather, hour: Int): String {
    val now = "${w.temp}° and ${w.summary.lowercase()}"
    return if (hour in 5..17) "$now, ${w.low}° tonight" else "$now, low ${w.low}°"
}

/** The Dashboard backdrop: the live weather sky (default), or a real photo of your area. */
@Composable
private fun DashboardBackground(mode: String, weather: app.marquee.tv.weather.Weather?, photo: app.marquee.tv.photos.AreaPhoto?) {
    // The sky is left clear down to the shelf, then darkens under it.
    val fade = remember { Brush.verticalGradient(0.41f to Color(0x00040610), 0.56f to Color(0xAD040610), 1f to Color(0xF2040610)) }
    if (mode == "PHOTO" && photo != null && app.marquee.tv.system.PerfCheck.liteLevel == 0) BakedBackdrop(photo.url, remember { listOf(fade) })
    else WeatherBackdrop(weather, overlay = fade, grain = true)
    // A soft shade behind the clock and the news, so they read against a bright sky.
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val shade = remember(dens) {
        with(dens) { Brush.radialGradient(listOf(Color(0x8C040610), Color(0x00040610)), center = androidx.compose.ui.geometry.Offset(150.dp.toPx(), 120.dp.toPx()), radius = 440.dp.toPx()) }
    }
    Box(Modifier.size(600.dp, 400.dp).background(shade))
}

@Composable
private fun EmptyState(loading: Boolean, needsSetup: Boolean, nav: Nav) {
    Column(Modifier.padding(horizontal = 48.dp, vertical = 20.dp).glass().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading) Text("Finding tonight's picks…", style = T.rowTitle)
        else if (needsSetup) {
            Text("Let's set up your recommendations", style = T.rowTitle)
            Text("Link Plex and add a free TMDB key from your phone. It takes about two minutes.", style = T.body)
            PillButton("Open setup", primary = true, onClick = { nav.go(Screen.Settings("accounts")) })
        } else Text("Nothing to recommend yet. Check your services in Settings.", style = T.body)
    }
}


/**
 * The news, up front: one story at a time with its picture, changing every 12 seconds through the first story of
 * each category and then the second of each. OK opens the News screen with that story on top.
 */
@Composable
internal fun NewsLead(sections: List<app.marquee.tv.news.NewsSection>, needsCity: Boolean, modifier: Modifier = Modifier, onOpen: (app.marquee.tv.news.Story?) -> Unit) {
    val leads = remember(sections) {
        (0 until 2).flatMap { i -> sections.mapNotNull { sec -> sec.stories.getOrNull(i)?.let { sec to it } } }
    }
    val index by androidx.compose.runtime.produceState(0, leads.size) {
        while (leads.size > 1) { delay(12_000); value = (value + 1) % leads.size }
    }
    val current = leads.getOrNull(index % leads.size.coerceAtLeast(1))
    Focusable(modifier, onClick = { onOpen(current?.second) }) { f ->
        Row(Modifier.height(108.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier.size(192.dp, 108.dp)
                    .then(if (f) Modifier.shadow(18.dp, shape, ambientColor = C.Amber, spotColor = C.Amber) else Modifier)
                    .clip(shape).background(remember(current?.second?.link) { app.marquee.tv.ui.components.fallbackBrush(current?.second?.link ?: "news") })
                    .border(if (f) 2.dp else 1.dp, if (f) Color(0xF2FFF0DC) else Color(0x24FFFFFF), shape),
            ) {
                current?.second?.image?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.width(310.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                if (current == null) {
                    Text("NEWS", style = T.sans(9.sp, FontWeight.ExtraBold, C.Amber, 1.25.sp).copy(shadow = HeaderShadow))
                    Text("Getting the headlines…", style = T.serif(23.sp).copy(lineHeight = 25.sp, shadow = HeaderShadow))
                    Text(if (needsCity) "Add your city in Settings for local and state news" else "", style = T.sans(10.sp, FontWeight.Medium, Color(0xFFD5DAE8)).copy(shadow = HeaderShadow))
                    return@Column
                }
                val (sec, story) = current
                Text(sec.title.uppercase(), style = T.sans(9.sp, FontWeight.ExtraBold, C.Amber, 1.25.sp).copy(shadow = HeaderShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(story.title, style = T.serif(23.sp).copy(lineHeight = 25.sp, shadow = HeaderShadow), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (f) "Press OK for the news" else listOf(story.source, app.marquee.tv.news.NewsFeeds.ago(story.published)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = T.sans(10.sp, if (f) FontWeight.Bold else FontWeight.Medium, if (f) C.AmberSoft else Color(0xFFD5DAE8)).copy(shadow = HeaderShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    // Where this story is among the ones it turns through.
                    val n = leads.size.coerceAtMost(6)
                    if (n > 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(n) { i -> Box(Modifier.size(if (i == index % n) 17.dp else 6.dp, 2.5.dp).clip(CircleShape).background(if (i == index % n) C.Amber else Color(0x80FFFFFF))) }
                    }
                }
            }
        }
    }
}

// ============================ Cinematic ============================

@Composable
fun CinematicHome(vm: MarqueeVm, nav: Nav) {
    androidx.compose.runtime.CompositionLocalProvider(app.marquee.tv.ui.components.LocalCardPreviews provides null) { CinematicContent(vm, nav) }
}

@Composable
private fun CinematicContent(vm: MarqueeVm, nav: Nav) {
    val s by vm.settings.collectAsState()
    val home by vm.home.collectAsState()
    val weather by vm.weather.collectAsState()
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    // Rows write focus here; the hero (its own composable) follows once focus settles, so moving between cards
    // never recomposes this whole screen.
    val focusState = remember { mutableStateOf<Title?>(null) }
    val cinePlace = remember { Place() }
    val onFocus: (Title) -> Unit = remember { { t -> focusState.value = t; cinePlace.card = t.id } }
    val cineList = rememberHomeListState()
    var cineReset by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val cineScope = androidx.compose.runtime.rememberCoroutineScope()
    fun cineTop() { cineReset++; focusState.value = null; cinePlace.card = null; cineScope.launch { cineList.scrollToItem(0); delay(320); cineList.animateScrollToItem(0) } }
    LaunchedEffect(Unit) { vm.homeTaps.collect { cineTop() } }
    KeepPlace(home, home?.rows?.firstOrNull { it.key != "apps" }?.key, cinePlace) { cineTop() }
    val cineAway by remember { androidx.compose.runtime.derivedStateOf { cineList.firstVisibleItemIndex > 0 || focusState.value != null && focusState.value?.id != home?.rows?.firstOrNull()?.items?.firstOrNull()?.id } }
    androidx.activity.compose.BackHandler(enabled = cineAway) { cineTop() }
    val preferredYt = remember(s, apps) { vm.app.apps.preferredYouTube(s) }
    val orderedApps = remember(apps, s.services, s.appOrder, s.hiddenApps, preferredYt) { orderedApps(apps, s, preferredYt, vm.app.apps.plexPlayerPackage(s)) }

    val chrome = 1f

    Box(Modifier.fillMaxSize()) {
        // Same live sky as the Dashboard; only the hero's words change as you move.
        WeatherBackdrop(
            weather,
            overlay = remember { Brush.verticalGradient(0f to Color(0x6607060B), 0.22f to Color.Transparent, 0.5f to Color(0x1407060B), 0.72f to Color(0xD907060B), 0.92f to C.Ink) },
            grain = true,
            sideScrim = remember { Brush.horizontalGradient(0f to Color(0xB307060B), 0.5f to Color(0x4007060B), 1f to Color.Transparent) },
        )

        // Top: text navigation + one quiet glass strip.
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 22.dp).alphaOf(chrome), verticalAlignment = Alignment.CenterVertically) {
            NavText("Home", true) {}
            NavText("Search") { nav.go(Screen.Search) }
            NavText("Apps") { nav.go(Screen.Apps) }
            NavText("Marquee settings") { nav.go(Screen.Settings()) }
            NavText("TV settings") { vm.app.apps.openSystemSettings() }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.glass(CircleShape, C.GlassWarm).height(38.dp).padding(start = 14.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                weather?.let { w -> SkyIcon(w.sky, w.isDay, 14); Text("${w.temp}°  ", style = T.sans(11.sp, FontWeight.Bold)); Divider() }
                ClockText(T.serif(20.sp))
                ProfileButton(s) { cycleProfile(vm) }
            }
        }

        CinematicHero(focusState, home?.featured, loading, s, weather, cineReset)

        // Rows: the first one sits at the bottom of the screen; moving down scrolls the rest into view.
        val rows = home?.rows.orEmpty().distinctBy { it.key }.filter { it.key != "apps" }
        LazyColumn(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(232.dp),
            state = cineList,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEachIndexed { i, r ->
                item(key = r.key, contentType = "row") {
                    Column(Modifier.onFocusChanged { cinePlace.focus(r.key, it.hasFocus) }) {
                        SectionHeader(r.title, if (i == 0) r.subtitle else "", Modifier.padding(start = 48.dp))
                        val yt = r.key == "youtube"
                        MediaRow(r.items, if (yt) 190.dp else 186.dp, if (yt) 107.dp else 105.dp, onFocus = onFocus, onClick = { openTitle(nav, vm, it) }, initialFocus = i == 0, resetKey = cineReset.takeIf { it > 0 })
                    }
                }
                if (i == 0) item(key = "apps") { AppsRow(orderedApps, { openApp(vm, it) }, { nav.go(Screen.Apps) }, vm) }
            }
            if (rows.isEmpty()) item { AppsRow(orderedApps, { openApp(vm, it) }, { nav.go(Screen.Apps) }, vm) }
        }
    }
}

@Composable
private fun ClockText(style: androidx.compose.ui.text.TextStyle) {
    val now = rememberNow()
    Text(clockText(now), style = style)
}

/** The big title on the left. Only this recomposes as focus moves between cards. */
@Composable
private fun CinematicHero(
    focusState: androidx.compose.runtime.State<Title?>, featured: Title?, loading: Boolean, s: app.marquee.tv.data.Settings,
    weather: app.marquee.tv.weather.Weather?, reset: Int,
) {
    var focused by remember { mutableStateOf<Title?>(null) }
    LaunchedEffect(reset) { if (reset > 0) focused = null }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { focusState.value }.collectLatest { t -> if (focused != null) delay(280); focused = t }
    }
    val hero = focused ?: featured
    Column(Modifier.padding(start = 48.dp, top = 108.dp).widthIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hero == null) {
            Text(if (loading) "Finding tonight's picks…" else "Set up Plex and TMDB in Settings to see picks here.", style = T.rowTitle)
            return@Column
        }
        Text(if (focused == null) "${if (java.time.LocalTime.now().hour >= 17 || java.time.LocalTime.now().hour < 4) "TONIGHT'S" else "TODAY'S"} PICK${if (s.profile.name != "Me") " FOR ${s.profile.name.uppercase()}" else ""}" else (hero.reason ?: "SELECTED").uppercase(), style = T.eyebrow)
        if (hero.logoUrl != null) AsyncImage(hero.logoUrl, hero.title, Modifier.height(86.dp).widthIn(max = 380.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
        else Text(hero.title, style = T.serif(if (hero.title.length > 18) 46.sp else 64.sp, spacing = 1.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        conciergeLine(hero, weather)?.let { Text(it, style = T.sans(13.sp, FontWeight.Medium, Color(0xFFEFE6DA)), maxLines = 3) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RatingChip(hero.ratingLabel)
            hero.bestAvailability?.quality?.forEach { OutlineTag(it) }
            Text(
                listOfNotNull(hero.year.ifBlank { null }, hero.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                    hero.bestAvailability?.let { Title.availabilityPhrase(it) }).joinToString(" · "),
                style = T.sans(10.sp, color = C.TextDim), modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun Modifier.alphaOf(a: Float) = this.then(Modifier.alpha(a))

@Composable
private fun NavText(label: String, active: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Column(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = T.sans(12.sp, if (active || f) FontWeight.Bold else FontWeight.SemiBold, if (active || f) C.Text else C.TextDim))
            Box(Modifier.padding(top = 3.dp).size(if (f) 18.dp else 3.dp, 3.dp).clip(CircleShape).background(if (active || f) C.Amber else Color.Transparent))
        }
    }
}

// ============================ Ambient ============================

@Composable
fun AmbientHome(vm: MarqueeVm, onWake: () -> Unit) {
    val home by vm.home.collectAsState()
    val weather by vm.weather.collectAsState()
    AmbientContent(home?.ambientArt.orEmpty(), weather, interactive = true, onWake = onWake)
}

@Composable
fun AmbientContent(art: List<String>, weather: app.marquee.tv.weather.Weather?, interactive: Boolean, onWake: () -> Unit = {}, now: java.time.LocalDateTime = rememberNow()) {
    var index by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxSize()
            .then(if (interactive) Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyUp) onWake(); true } else Modifier),
    ) {
        if (art.isNotEmpty() && runCatching { !app.marquee.tv.MarqueeApp.instance.store.value.ambientScene }.getOrDefault(true)) Slideshow(art, onIndex = { index = it }) else WeatherBackdrop(weather, orbs = true)
        Scrim(Brush.horizontalGradient(0f to Color(0xBF03040A), 0.45f to Color(0x3303040A), 0.7f to Color.Transparent))
        Scrim(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB303040A)))
        GrainOverlay(0.05f)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 70.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clockText(now), style = T.serif(150.sp, spacing = (-3).sp), modifier = Modifier.offset(x = (-4).dp))
            Text(dateText(now), style = T.sans(16.sp, FontWeight.Medium, Color(0xFFE3DDD3)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                weather?.let { w ->
                    SkyIcon(w.sky, w.isDay, 16)
                    Text("${w.temp}°", style = T.sans(12.sp, FontWeight.Bold))
                    Text("${w.summary}, ${w.low}° overnight", style = T.sans(12.sp, color = C.TextDim))
                }
            }
        }
        if (art.isNotEmpty()) Column(Modifier.align(Alignment.BottomEnd).padding(end = 60.dp, bottom = 70.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("From your Plex library", style = T.sans(9.sp, color = Color(0xFFB9B2A8)))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val n = art.size.coerceAtMost(6)
                repeat(n) { i -> Box(Modifier.size(if (i == index % n) 14.dp else 3.dp, 3.dp).clip(CircleShape).background(if (i == index % n) C.Text else Color(0x59FFFFFF))) }
            }
        }
        if (interactive) {
            val t = rememberInfiniteTransition(label = "breathe")
            val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "a")
            Text("PRESS ANY BUTTON", style = T.sans(9.sp, color = C.TextDim, spacing = 1.5.sp), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).alphaOf(a))
        }
    }
}

