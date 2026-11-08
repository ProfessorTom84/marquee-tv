package app.marquee.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.Screen
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.player.TrailerActivity
import app.marquee.tv.plex.PlexRepo
import app.marquee.tv.recs.DetailData
import app.marquee.tv.tmdb.Person
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.BakedBackdrop
import app.marquee.tv.ui.components.CalmScroll
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.OutlineTag
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.RatingChip
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.screens.ChoiceChip
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A title's page, modelled on Plex: the art and the one button that plays it, then everything you'd want next
 * as you scroll: episodes (with your progress), the cast, more like this, and Plex's related rows.
 */
@Composable
fun DetailScreen(vm: MarqueeVm, nav: Nav, initial: Title) {
    val s by vm.settings.collectAsState()
    var data by remember(initial.id) { mutableStateOf<DetailData?>(null) }
    var loading by remember(initial.id) { mutableStateOf(true) }
    val ctx = LocalContext.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(initial.id) {
        data = runCatching { vm.detail(initial) }.getOrNull()
        loading = false
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    val t = data?.title ?: initial
    val ways = data?.ways ?: initial.availability
    val best = ways.firstOrNull()
    val list = rememberLazyListState()
    val open: (Title) -> Unit = { x -> if (x.youtubeId != null) vm.app.apps.openYouTube(x.youtubeId, s) else nav.go(Screen.Detail(x)) }

    Box(Modifier.fillMaxSize()) {
        BakedBackdrop(t.backdropUrl, remember {
            listOf(
                Brush.horizontalGradient(0f to Color(0xF207060B), 0.42f to Color(0xB307060B), 0.7f to Color(0x4007060B), 1f to Color(0x8C07060B)),
                Brush.verticalGradient(0.55f to Color.Transparent, 0.95f to C.Ink),
            )
        }, grain = 0.06f)
        // As you scroll into the rows, the art dims so they read cleanly.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / 300f).coerceIn(0f, 1f)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        }.background(Color(0xCC07060B)))

        CalmScroll {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                item("hero") {
                    Column(
                        Modifier.heightIn(min = 400.dp).padding(start = 48.dp, top = 54.dp).widthIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(eyebrow(best, t), style = T.eyebrow)
                        if (t.logoUrl != null) AsyncImage(t.logoUrl, t.title, Modifier.height(96.dp).widthIn(max = 400.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(t.title, style = T.serif(if (t.title.length > 18) 48.sp else 72.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RatingChip(t.ratingLabel)
                            t.badge?.takeIf { it.startsWith("RT ") }?.let { RatingChip(it) }
                            Text(
                                listOfNotNull(
                                    t.year.ifBlank { null },
                                    if (t.mediaType == "tv") data?.seasonCount?.let { if (it == 1) "1 season" else "$it seasons" }
                                    else t.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                                    t.contentRating.ifBlank { null }, t.genres.take(2).joinToString(", ").ifBlank { null },
                                ).joinToString(" · "),
                                style = T.sans(10.sp, color = Color(0xFFE2D6C6)), modifier = Modifier.padding(start = 4.dp),
                            )
                            best?.quality?.take(3)?.forEach { OutlineTag(it) }
                        }
                        Text(t.overview, style = T.sans(12.sp, color = Color(0xFFEFE6DA)), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (best != null) PillButton(
                                when (best.kind) {
                                    AvailKind.PLEX -> (if ((t.progress ?: 0f) > 0.02f) "Resume" else "Play") + if (best.quality.contains("4K")) " in 4K" else ""
                                    else -> "Play on ${best.name}"
                                },
                                Modifier.focusRequester(playFocus), primary = true, height = 40.dp,
                                icon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(15.dp), tint = Color(0xFF120D08)) },
                            ) { vm.app.apps.open(t, best, s) }
                            val trailerUrl = data?.trailerUrl
                            val yt = data?.trailerYoutube
                            if (trailerUrl != null || yt != null) PillButton(
                                "Trailer", height = 40.dp, modifier = if (best == null) Modifier.focusRequester(playFocus) else Modifier,
                                icon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(13.dp)) },
                            ) { if (trailerUrl != null) TrailerActivity.start(ctx, trailerUrl) else vm.app.apps.openYouTube(yt!!, s) }
                        }
                        // Everything here is already free to you, so no "best way" box: just the other places it's included.
                        val others = ways.drop(1)
                        if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Also on", style = T.small)
                            others.forEach { w -> PillButton(if (w.kind == AvailKind.PLEX) "Your Plex" else w.name, height = 30.dp) { vm.app.apps.open(t, w, s) } }
                        }
                        if (ways.isEmpty() && !loading) {
                            val elsewhere = data?.elsewhere ?: t.elsewhere
                            Text(
                                if (elsewhere.isNotEmpty()) "Not on your services. Streaming on ${elsewhere.joinToString(", ")}."
                                else "Not on your services right now.", style = T.small,
                            )
                        }
                    }
                }
                val showKey = data?.showKey
                if (showKey != null) item("episodes") { Episodes(vm, showKey, t) }
                val people = data?.people.orEmpty()
                if (people.isNotEmpty()) item("cast") { Cast(people, data?.director) }
                val more = data?.moreLikeThis.orEmpty()
                if (more.isNotEmpty()) item("more") {
                    Column {
                        SectionHeader("More like this", "Similar picks on your Plex and your services", Modifier.padding(start = 48.dp))
                        MediaRow(more, 200.dp, 112.dp, onFocus = {}, onClick = open)
                    }
                }
                data?.rows.orEmpty().forEach { (name, items) ->
                    item("row:$name") {
                        Column {
                            SectionHeader(name, modifier = Modifier.padding(start = 48.dp))
                            MediaRow(items, 170.dp, 96.dp, onFocus = {}, onClick = open)
                        }
                    }
                }
                if (loading) item("loading") { Text("Finding more like this…", style = T.small, modifier = Modifier.padding(start = 48.dp)) }
            }
        }
    }
}

private fun eyebrow(best: app.marquee.tv.data.Availability?, t: Title): String = when (best?.kind) {
    AvailKind.PLEX -> (listOf("ON YOUR PLEX") + best.quality.filter { it == "4K" || it == "Dolby Vision" || it == "HDR10" || it == "Dolby Atmos" }.map { it.uppercase() }).joinToString(" · ")
    AvailKind.INCLUDED -> "INCLUDED WITH ${best.name.uppercase()}"
    AvailKind.FREE, AvailKind.WITH_ADS -> "FREE ON ${best.name.uppercase()}"
    null -> if (t.isElsewhere) "NOT ON YOUR SERVICES" else ""
}

// ---------------- Seasons & episodes (from your Plex, with your progress) ----------------

@Composable
private fun Episodes(vm: MarqueeVm, showKey: String, show: Title) {
    val s by vm.settings.collectAsState()
    val seasons by produceState(emptyList<PlexRepo.Season>(), showKey) { value = withContext(Dispatchers.IO) { vm.app.plex.seasons(showKey) } }
    if (seasons.isEmpty()) return
    // Open on the season you're in the middle of (the first with unwatched episodes).
    var chosen by remember(seasons) { mutableStateOf(seasons.firstOrNull { it.watched < it.episodes } ?: seasons.last()) }
    val episodes by produceState(emptyList<PlexRepo.Episode>(), chosen.key) { value = withContext(Dispatchers.IO) { vm.app.plex.episodes(chosen.key) } }
    val row = rememberLazyListState()
    LaunchedEffect(episodes) {
        val next = episodes.indexOfFirst { !it.watched }
        if (next > 0) row.scrollToItem(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Episodes", "${chosen.title} · ${chosen.watched} of ${chosen.episodes} watched", Modifier.padding(start = 48.dp))
        if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(seasons, key = { _, x -> x.key }) { _, x ->
                ChoiceChip((if (x.index == 0) "Specials" else "Season ${x.index}") + if (x.episodes > 0 && x.watched >= x.episodes) "  ✓" else "", x.key == chosen.key) { chosen = x }
            }
        }
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(episodes, key = { _, e -> e.key }) { _, e ->
                EpisodeCard(e) {
                    vm.app.apps.openPlex(Title(id = "plex:${e.key}", source = Source.PLEX, title = show.title, mediaType = "tv", plexKey = e.key), s)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: PlexRepo.Episode, onClick: () -> Unit) {
    Focusable(Modifier.width(230.dp), onClick = onClick) { f ->
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.fillMaxWidth().height(129.dp).graphicsLayer { val k = if (f) 1.05f else 1f; scaleX = k; scaleY = k }
                    .clip(shape).background(Color(0x22FFFFFF)).border(if (f) 2.dp else 0.dp, if (f) Color(0xF2FFF0DC) else Color.Transparent, shape),
            ) {
                if (e.thumb != null) AsyncImage(e.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
                Text("E${e.index}", style = T.sans(11.sp, FontWeight.ExtraBold), modifier = Modifier.align(Alignment.BottomStart).padding(9.dp))
                e.minutes?.let { Text("${it}m", style = T.sans(10.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp)) }
                if (e.watched) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).clip(CircleShape).background(Color(0xCC1E2A20)), contentAlignment = Alignment.Center) {
                    Text("✓", style = T.sans(11.sp, FontWeight.ExtraBold, C.Good))
                }
                e.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(C.Amber))
                    }
                }
            }
            Text(e.title, style = T.sans(11.sp, FontWeight.Bold, if (f) C.Text else C.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f && e.summary.isNotBlank()) Text(e.summary, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- Cast ----------------

@Composable
private fun Cast(people: List<Person>, director: Person?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Cast & crew", modifier = Modifier.padding(start = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val all = listOfNotNull(director) + people
            itemsIndexed(all, key = { i, p -> "${p.id}:$i" }) { _, p ->
                Focusable(Modifier.width(96.dp), onClick = {}) { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(78.dp).graphicsLayer { val k = if (f) 1.08f else 1f; scaleX = k; scaleY = k }
                                .clip(CircleShape).background(Color(0xFF2A2433)).border(if (f) 2.dp else 1.dp, if (f) C.Amber else C.Stroke, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(p.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), style = T.sans(18.sp, FontWeight.Bold, C.TextDim))
                            if (p.photoUrl != null) AsyncImage(p.photoUrl, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text(p.name, style = T.sans(10.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(p.role, style = T.sans(9.sp, color = C.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
