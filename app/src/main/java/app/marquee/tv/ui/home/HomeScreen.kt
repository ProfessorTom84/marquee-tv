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
import androidx.compose.ui.focus.focusRequester
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
import app.marquee.tv.system.TimeFmt
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
private fun rememberHomeListState() = androidx.compose.foundation.lazy.rememberLazyListState(
    cacheWindow = androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f),
)

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
    val headlines by vm.headlines.collectAsState()
    val up by vm.upNext.collectAsState()
    val events by vm.events.collectAsState()
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    val photo by vm.areaPhoto.collectAsState()
    val preferredYt = remember(s, apps) { vm.app.apps.preferredYouTube(s) }
    val orderedApps = remember(apps, s.services, s.appOrder, s.hiddenApps, preferredYt) { orderedApps(apps, s, preferredYt, vm.app.apps.plexPlayerPackage(s)) }

    // One calm background for everything: it never changes with the card you're on.
    val focusedId = remember { mutableStateOf<String?>(null) }
    val onFocus: (Title) -> Unit = remember { { t -> focusedId.value = t.id } }
    val listState = rememberHomeListState()
    var resetTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Scroll up, refocus the first card, then settle at the very top again (focusing nudges the list).
    fun backToTop() { resetTick++; focusedId.value = null; scope.launch { listState.scrollToItem(0); delay(320); listState.animateScrollToItem(0) } }
    // Home on the remote: back to the top, first card focused (like Google TV).
    LaunchedEffect(Unit) { vm.homeTaps.collect { backToTop() } }
    // Back: first return to the top; at the top, Back starts the screensaver (handled in Root).
    val firstId = home?.rows?.firstOrNull { it.key == "tonight" }?.items?.firstOrNull()?.id
    val awayFromTop by remember(firstId) {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 || (focusedId.value != null && focusedId.value != firstId)
        }
    }
    androidx.activity.compose.BackHandler(enabled = awayFromTop) { backToTop() }
    val reset: Any? = resetTick.takeIf { it > 0 }

    // Continue-watching takes the calendar's place when no calendar is connected.
    val resume = remember(home) { home?.rows?.firstOrNull { it.key == "continue" }?.items?.firstOrNull() }

    run {
        Box(Modifier.fillMaxSize()) {
            DashboardBackground(s.background, weather, photo)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    // The top bar fades away as you scroll into the rows (read in the draw phase: no recomposition).
                    Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp).graphicsLayer {
                        alpha = if (listState.firstVisibleItemIndex > 0) 0f else (1f - listState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
                        // Fade each glyph/shape directly instead of via an offscreen buffer (cheaper on the TV's GPU).
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }, verticalAlignment = Alignment.CenterVertically) {
                        DashboardClock(s.profile.name, weather?.summary)
                        Spacer(Modifier.weight(1f))
                        PillButton("Pick together", icon = { androidx.tv.material3.Icon(app.marquee.tv.ui.components.MarqueeIcons.People, null, modifier = Modifier.size(15.dp)) }, onClick = { nav.go(Screen.PickTogether) })
                        Spacer(Modifier.width(10.dp))
                        TopActions(s, { nav.go(Screen.Search) }, { nav.go(Screen.Settings()) }, { vm.app.apps.openSystemSettings() }, { cycleProfile(vm) })
                    }
                }
                item(key = "cards", contentType = "cards") {
                    Box {
                        Row(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WeatherCard(weather, s.cityName, s.hasLocation, Modifier.width(if (vm.app.calendar.configured || resume != null) 240.dp else 360.dp).fillMaxHeight())
                            when {
                                vm.app.calendar.configured -> CalendarCard(up, events, true, Modifier.width(320.dp).fillMaxHeight())
                                resume != null -> ResumeCard(resume, Modifier.width(320.dp).fillMaxHeight()) { openTitle(nav, vm, resume) }
                            }
                            val news = s.newsSource != app.marquee.tv.news.NewsSource.OFF && !s.profile.kidSafe
                            if (news) HeadlinesCard(headlines, s.newsSource, s.cityName, Modifier.weight(1f).fillMaxHeight()) { nav.go(Screen.Article(it)) }
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                }
                val rows = home?.rows.orEmpty().distinctBy { it.key }
                if (rows.isEmpty()) item { EmptyState(loading, s.tmdbKey.isBlank() && !s.plexReady, nav) }
                rows.firstOrNull { it.key == "tonight" }?.let { r ->
                    item(key = "tonight", contentType = "row") {
                        Column {
                            SectionHeader(r.title, r.subtitle, Modifier.padding(start = 48.dp, top = 8.dp))
                            MediaRow(r.items, 220.dp, 124.dp, onFocus = onFocus, onClick = { openTitle(nav, vm, it) }, initialFocus = true, resetKey = reset)
                        }
                    }
                }
                item(key = "apps", contentType = "apps") { AppsRow(orderedApps, { openApp(vm, it) }, { nav.go(Screen.Apps) }, vm) }
                rows.filter { it.key != "tonight" && it.key != "apps" && it.key != "cards" }.forEach { r -> item(key = r.key, contentType = "row") { RowBlock(r, vm, nav, onFocus, reset) } }
            }
            if (s.background == "PHOTO" && photo != null) Text(
                "Photo · ${photo!!.photographer} on Unsplash", style = T.sans(8.sp, color = Color(0x99FFFFFF)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 10.dp),
            )
        }
    }
}

/** Clock, greeting and date: the only part of the Dashboard that changes on the minute, so only it recomposes. */
@Composable
private fun DashboardClock(profileName: String, summary: String?) {
    val now = rememberNow()
    Text(clockText(now), style = T.serif(52.sp))
    Column(Modifier.padding(start = 14.dp)) {
        Text(greeting(now, profileName), style = T.sans(14.sp, FontWeight.SemiBold))
        Text(dateText(now) + (summary?.let { " · ${it.lowercase()}" } ?: ""), style = T.sans(10.sp, color = C.Cool))
    }
}

/** The Dashboard backdrop: the live weather sky (default), or a real photo of your area. */
@Composable
private fun DashboardBackground(mode: String, weather: app.marquee.tv.weather.Weather?, photo: app.marquee.tv.photos.AreaPhoto?) {
    val fade = remember { Brush.verticalGradient(0f to Color(0x2E040610), 0.36f to Color(0x47040610), 0.6f to Color(0xC7040610), 1f to Color(0xF5040610)) }
    if (mode == "PHOTO" && photo != null && app.marquee.tv.system.PerfCheck.liteLevel == 0) BakedBackdrop(photo.url, remember { listOf(fade) })
    else WeatherBackdrop(weather, overlay = fade, grain = true)
}

@Composable
fun ResumeCard(t: Title, modifier: Modifier, onClick: () -> Unit) {
    Focusable(modifier, onClick = onClick) { f ->
        Row(
            Modifier.fillMaxSize().glass(stroke = if (f) C.Text else C.Stroke).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.fillMaxHeight().width(150.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x33000000))) {
                AsyncImage(t.backdropUrl ?: t.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                t.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p).height(3.dp).background(C.Amber))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("CONTINUE WATCHING", style = T.label)
                Text(t.title, style = T.sans(13.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                t.badge?.let { Text(it, style = T.sans(10.sp, color = C.TextDim)) }
                Text(if (f) "Press OK to resume" else "", style = T.sans(9.sp, FontWeight.Bold, C.AmberSoft))
            }
        }
    }
}

@Composable
fun RowBlock(r: DataRow, vm: MarqueeVm, nav: Nav, onFocus: (Title) -> Unit = {}, resetKey: Any? = null) {
    Column {
        SectionHeader(r.title, r.subtitle, Modifier.padding(start = 48.dp, top = 4.dp))
        val yt = r.key == "youtube"
        MediaRow(
            r.items, if (yt) 190.dp else 170.dp, if (yt) 107.dp else 96.dp,
            onFocus = onFocus, onClick = { openTitle(nav, vm, it) }, resetKey = resetKey,
        )
    }
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

@Composable
fun WeatherCard(w: app.marquee.tv.weather.Weather?, city: String, hasLocation: Boolean, modifier: Modifier) {
    val shade = remember { Brush.verticalGradient(listOf(Color(0x40000000), Color(0x8C000000))) }
    Box(modifier.glass()) {
        // The card's background is the live weather itself: sun and sky colour, drifting cloud, rain or snow.
        if (w != null) app.marquee.tv.ui.components.LiveSky(
            w.sky, w.isDay, Modifier.matchParentSize(), windKph = w.windKph, sunrise = w.sunrise, sunset = w.sunset,
            overlay = shade, landscape = false, animate = false, orbs = false,
        )
    Column(Modifier.matchParentSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(city.uppercase().ifBlank { "WEATHER" }, style = T.label)
                if (w != null) Row(verticalAlignment = Alignment.Bottom) {
                    Text("${w.temp}°", style = T.serif(40.sp))
                    Text("  ${w.summary} · H ${w.high}° L ${w.low}°", style = T.sans(10.sp, color = C.Text), modifier = Modifier.padding(bottom = 7.dp))
                } else Text(if (hasLocation) "Loading…" else "Add your city in Settings", style = T.body, modifier = Modifier.padding(top = 8.dp))
            }
            SkyIcon(w?.sky, w?.isDay ?: (java.time.LocalTime.now().hour in 7..18), 26)
        }
        if (w != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hourly.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.label, style = T.sans(8.sp, color = C.Cool))
                    Text("${h.temp}°", style = T.sans(10.sp, FontWeight.SemiBold))
                }
            }
        }
    }
    }
}

@Composable
fun CalendarCard(up: app.marquee.tv.system.UpNext?, events: List<app.marquee.tv.calendar.CalEvent>, configured: Boolean, modifier: Modifier) {
    val leaveSoon = up?.minutesUntilLeave()?.let { it in -10..60 } == true
    Row(
        modifier.glass(tint = if (leaveSoon) Color(0x6B30221E) else C.GlassCool, stroke = if (leaveSoon) Color(0x59F5B54A) else C.Stroke)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            if (up == null) {
                Text("UP NEXT", style = T.label)
                Text(if (configured) "Nothing else today" else "Connect your calendar in Settings", style = T.sans(14.sp, FontWeight.Bold))
                Text(if (configured) "Enjoy the evening" else "Uses your private iCal link", style = T.small)
            } else {
                val mins = up.minutesUntilStart()
                Text("UP NEXT · ${TimeFmt.until(mins).uppercase()}", style = T.label.copy(color = if (leaveSoon) Color(0xFFF5C77A) else C.Cool))
                Column {
                    Text(up.event.title, style = T.sans(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${TimeFmt.full(up.event.start)}${up.event.location.takeIf { it.isNotBlank() }?.let { " · ${it.substringBefore(',')}" } ?: ""}", style = T.sans(10.sp, color = C.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    up.leaveBy?.let { lb ->
                        Box(Modifier.clip(CircleShape).background(C.Amber).padding(horizontal = 9.dp, vertical = 4.dp)) {
                            Text("Leave by ${TimeFmt.short(lb)}", style = T.sans(9.sp, FontWeight.ExtraBold, Color(0xFF1A1206)))
                        }
                    }
                    up.drive?.let { Text("${it.minutes} min drive · ${it.trafficLabel}", style = T.sans(9.sp, color = C.Text)) }
                }
            }
        }
        val later = events.filter { e -> !e.allDay && e.start.isAfter(ZonedDateTime.now()) && e.uid != up?.event?.uid }.take(2)
        if (later.isNotEmpty()) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x24FFFFFF)))
            Column(Modifier.width(100.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LATER", style = T.label)
                later.forEach { e ->
                    val day = if (e.start.toLocalDate() == java.time.LocalDate.now()) "" else e.start.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " "
                    Column {
                        Text(day + TimeFmt.full(e.start), style = T.sans(9.sp, FontWeight.Bold, C.Amber))
                        Text(e.title, style = T.sans(10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * News headlines (free feeds, see NewsFeeds): three at a time, moving on every 15 seconds. OK opens the full list
 * with a QR code for each story, so you can read it on your phone.
 */
@Composable
fun HeadlinesCard(
    items: List<app.marquee.tv.news.Headline>, source: app.marquee.tv.news.NewsSource, city: String, modifier: Modifier,
    onRead: (app.marquee.tv.news.Headline) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val perPage = 2
    val pages = ((items.size + perPage - 1) / perPage).coerceAtLeast(1)
    val page by androidx.compose.runtime.produceState(0, pages) { while (true) { delay(15_000); value = (value + 1) % pages } }
    val shown = items.drop((page % pages) * perPage).take(perPage)
    Focusable(modifier, onClick = { if (items.isNotEmpty()) open = true }) { f ->
        Column(
            Modifier.fillMaxSize().glass(stroke = if (f) C.Text else C.Stroke).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HEADLINES · ${(if (source == app.marquee.tv.news.NewsSource.LOCAL && city.isNotBlank()) city else source.label).uppercase()}",
                    style = T.label, modifier = Modifier.weight(1f), maxLines = 1)
                if (f) Text("OK for more", style = T.sans(8.sp, FontWeight.Bold, C.AmberSoft))
            }
            when {
                items.isEmpty() && source == app.marquee.tv.news.NewsSource.LOCAL && city.isBlank() ->
                    Text("Add your city in Settings for local news", style = T.sans(12.sp, FontWeight.Bold))
                items.isEmpty() -> Text("Getting the headlines…", style = T.sans(12.sp, FontWeight.Bold))
                // Two at a time, room to wrap, then the outlet and how long ago.
                else -> shown.forEach { h ->
                    Column {
                        Text(h.title, style = T.sans(11.sp, FontWeight.Bold).copy(lineHeight = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(h.source, app.marquee.tv.news.NewsFeeds.ago(h.published)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = T.sans(8.sp, color = C.Cool), maxLines = 1)
                    }
                }
            }
        }
    }
    if (open) HeadlinesDialog(items, onClose = { open = false }, onRead = { open = false; onRead(it) })
}

@Composable
private fun HeadlinesDialog(items: List<app.marquee.tv.news.Headline>, onClose: () -> Unit, onRead: (app.marquee.tv.news.Headline) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var chosen by remember { mutableStateOf(items.firstOrNull()) }
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Row(
            Modifier.padding(40.dp).fillMaxWidth().glass(tint = Color(0xF20B0F1E)).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Headlines", style = T.serif(28.sp), modifier = Modifier.padding(bottom = 6.dp))
                items.take(10).forEachIndexed { i, h ->
                    Focusable(
                        Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        onClick = { onRead(h) }, onFocus = { if (it) chosen = h },
                    ) { f ->
                        Column(
                            Modifier.fillMaxWidth().background(if (f) Color(0x24FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(h.title, style = T.sans(12.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(h.source, app.marquee.tv.news.NewsFeeds.ago(h.published)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = T.sans(9.sp, color = C.Cool))
                        }
                    }
                }
            }
            chosen?.takeIf { it.link.isNotBlank() }?.let { h ->
                Column(Modifier.width(220.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    app.marquee.tv.ui.components.QrImage(h.link, 200.dp)
                    Text(if (app.marquee.tv.news.ArticleReader.canOpen(h.link)) "OK to read it here, or scan to read on your phone" else "Scan to read this story on your phone", style = T.small)
                    Text("Back to close", style = T.sans(9.sp, FontWeight.Bold, C.AmberSoft))
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
    val up by vm.upNext.collectAsState()
    val events by vm.events.collectAsState()
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    // Rows write focus here; the hero (its own composable) follows once focus settles, so moving between cards
    // never recomposes this whole screen.
    val focusState = remember { mutableStateOf<Title?>(null) }
    val onFocus: (Title) -> Unit = remember { { t -> focusState.value = t } }
    val cineList = rememberHomeListState()
    var cineReset by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val cineScope = androidx.compose.runtime.rememberCoroutineScope()
    fun cineTop() { cineReset++; focusState.value = null; cineScope.launch { cineList.scrollToItem(0); delay(320); cineList.animateScrollToItem(0) } }
    LaunchedEffect(Unit) { vm.homeTaps.collect { cineTop() } }
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
            NavText("Pick together") { nav.go(Screen.PickTogether) }
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
                up?.let { u ->
                    Text(u.event.title.take(18), style = T.sans(11.sp, FontWeight.Bold))
                    u.leaveBy?.let { Box(Modifier.clip(CircleShape).background(C.Amber).padding(horizontal = 7.dp, vertical = 3.dp)) { Text("Leave ${TimeFmt.short(it)}", style = T.sans(9.sp, FontWeight.ExtraBold, Color(0xFF1A1206))) } }
                        ?: Text(TimeFmt.full(u.event.start), style = T.sans(11.sp, color = C.TextDim))
                    Divider()
                }
                ClockText(T.serif(20.sp))
                ProfileButton(s) { cycleProfile(vm) }
            }
        }

        CinematicHero(focusState, home?.featured, loading, s, up, events, weather, cineReset)

        // Rows: the first one sits at the bottom of the screen; moving down scrolls the rest into view.
        val rows = home?.rows.orEmpty().distinctBy { it.key }.filter { it.key != "apps" }
        LazyColumn(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(232.dp),
            state = cineList,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEachIndexed { i, r ->
                item(key = r.key, contentType = "row") {
                    Column {
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
    up: app.marquee.tv.system.UpNext?, events: List<app.marquee.tv.calendar.CalEvent>, weather: app.marquee.tv.weather.Weather?, reset: Int,
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
        conciergeLine(hero, up, events, weather)?.let { Text(it, style = T.sans(13.sp, FontWeight.Medium, Color(0xFFEFE6DA)), maxLines = 3) }
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
    val up by vm.upNext.collectAsState()
    AmbientContent(home?.ambientArt.orEmpty(), weather, up, interactive = true, onWake = onWake)
}

@Composable
fun AmbientContent(art: List<String>, weather: app.marquee.tv.weather.Weather?, up: app.marquee.tv.system.UpNext?, interactive: Boolean, onWake: () -> Unit = {}) {
    val now = rememberNow()
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
                up?.let { u ->
                    if (weather != null) Box(Modifier.width(1.dp).height(14.dp).background(Color(0x38FFFFFF)))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(C.Amber))
                    Text(u.event.title, style = T.sans(12.sp, FontWeight.Bold))
                    val lv = u.minutesUntilLeave()
                    Text(TimeFmt.short(u.event.start) + (lv?.let { " · leave ${TimeFmt.until(it)}" } ?: ""), style = T.sans(12.sp, color = C.TextDim))
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

