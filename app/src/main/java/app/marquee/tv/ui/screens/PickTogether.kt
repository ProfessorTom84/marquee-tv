package app.marquee.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.Screen
import app.marquee.tv.data.Title
import app.marquee.tv.setup.SetupServer
import app.marquee.tv.together.Together
import app.marquee.tv.together.Together.Phase
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.BakedBackdrop
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.QrImage
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.WeatherBackdrop
import app.marquee.tv.ui.components.glass
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pick Together on the TV: the lobby with the join code, live progress while everyone votes on their phones
 * (or takes turns on the remote), then the reveal.
 */
@Composable
fun PickTogetherScreen(vm: MarqueeVm, nav: Nav) {
    val session by Together.session.collectAsState()
    val weather by vm.weather.collectAsState()
    LaunchedEffect(Unit) {
        vm.app.ensureSetupServer()
        if (Together.session.value == null) Together.newSession()
    }
    BackHandler { Together.end(); nav.back() }
    val s = session ?: return
    Box(Modifier.fillMaxSize()) {
        if (s.phase != Phase.RESULTS) WeatherBackdrop(
            weather, overlay = remember { Brush.verticalGradient(0f to Color(0x99040610), 0.5f to Color(0xC7040610), 1f to Color(0xF5040610)) }, grain = true,
        )
        AnimatedContent(s.phase to s.remote, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) }, label = "pt") { (phase, remote) ->
            KeepVisibleScope {
                when (phase) {
                    Phase.LOBBY -> if (remote) RemoteSetup(vm) else Lobby(vm, nav)
                    Phase.BUILDING -> Centered("Shuffling a deck from your Plex and your services…", "Only things you can watch right now. Nothing to rent or buy.")
                    Phase.VOTING -> if (remote) RemoteVoting() else Progress()
                    Phase.RESULTS -> Results(vm, nav)
                }
            }
        }
    }
}

/** Vertical lists here only scroll when the focused thing would go off-screen (TV lists otherwise re-centre it). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val KeepVisible = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepVisibleScope(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides KeepVisible) { content() }

@Composable
private fun Centered(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PICK TOGETHER", style = T.eyebrow)
        Text(title, style = T.serif(34.sp), modifier = Modifier.padding(top = 10.dp))
        Text(sub, style = T.body, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lobby(vm: MarqueeVm, nav: Nav) {
    val session by Together.session.collectAsState()
    val s = session ?: return
    val scope = rememberCoroutineScope()
    val url = remember(s.id) { SetupServer.localIp()?.let { "http://$it:${SetupServer.PORT}/t/${s.id}" } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }
    val ready = s.players.count { it.ready }
    val me = vm.settings.collectAsState().value.profile.name.let { if (it == "Me") "You" else it }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 44.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("PICK TOGETHER", style = T.eyebrow)
            Text("Find something you'll both love.", style = T.serif(44.sp))
            Text("Scan the code with your phones. Each of you picks a mood, then swipes privately through the same short deck: Nah, Yes or Love it. Marquee reveals what you all said yes to.", style = T.body, modifier = Modifier.widthIn(max = 520.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Step("1", "Scan & join"); Step("2", "Pick your moods"); Step("3", "Swipe privately"); Step("4", "See your match")
            }
            s.notice?.let { Text(it, style = T.sans(12.sp, FontWeight.SemiBold, C.Warn)) }
            Text(if (s.players.isEmpty()) "WAITING FOR PHONES" else "IN THIS ROUND", style = T.label, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.players.isEmpty()) Text("Nobody yet. Up to four people can join.", style = T.small)
                s.players.forEach { p -> PlayerChip(p.name, if (p.ready) "Ready" else "Choosing moods…", p.ready) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    when { ready == 0 -> "Start voting"; ready == 1 && s.players.size == 1 -> "Start · just ${s.players.first().name}"; else -> "Start voting · $ready ready" },
                    primary = ready > 0, height = 40.dp, modifier = Modifier.focusRequester(first),
                ) { if (ready > 0) vm.startTogetherRound() }
                PillButton("Take turns on the remote", height = 40.dp) {
                    Together.newSession(remote = true, localNames = listOf(me, "Partner"))
                }
                PillButton("Just me", height = 40.dp) { nav.go(Screen.QuickPicks) }
            }
        }
        Column(Modifier.width(300.dp).glass().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("JOIN ON YOUR PHONE", style = T.label)
            if (url != null) {
                QrImage(url, 220.dp)
                Text(url.removePrefix("http://"), style = T.sans(12.sp, FontWeight.Bold, C.AmberSoft))
                Text("Use the same Wi-Fi as the TV. Your votes stay on your phone until the reveal.", style = T.small)
            } else Text("Connect the TV to Wi-Fi to join from phones, or take turns on the remote.", style = T.body)
        }
    }
}

@Composable
private fun Step(n: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0x33F5B54A)), contentAlignment = Alignment.Center) {
            Text(n, style = T.sans(11.sp, FontWeight.ExtraBold, C.AmberSoft))
        }
        Text(label, style = T.sans(11.sp, FontWeight.SemiBold))
    }
}

@Composable
private fun PlayerChip(name: String, status: String, ok: Boolean) {
    Row(
        Modifier.glass(RoundedCornerShape(14.dp), stroke = if (ok) Color(0x997FD6A2) else C.Stroke).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF3A2A20)).border(1.5.dp, C.Amber, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = T.sans(12.sp, FontWeight.Bold))
        }
        Column {
            Text(name, style = T.sans(13.sp, FontWeight.Bold))
            Text(status, style = T.sans(10.sp, color = if (ok) C.Good else C.TextDim))
        }
    }
}

// ---------------- Taking turns on the remote ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteSetup(vm: MarqueeVm) {
    val session by Together.session.collectAsState()
    val s = session ?: return
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateListOf<String>() }
    var minutes by remember { mutableStateOf<Int?>(120) }
    var type by remember { mutableStateOf("any") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 44.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PICK TOGETHER · TAKING TURNS", style = T.eyebrow)
                Text("What are you two in the mood for?", style = T.serif(40.sp))
                Text("Choose together, then pass the remote. Each of you votes on the same deck while the other looks away.", style = T.body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Together.moods.forEachIndexed { i, m ->
                        Box(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            ChoiceChip(m.label, m.key in chosen) { if (m.key in chosen) chosen.remove(m.key) else chosen.add(m.key) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Time", style = T.label, modifier = Modifier.padding(end = 4.dp))
                    ChoiceChip("Under 90 min", minutes == 90) { minutes = 90 }
                    ChoiceChip("Under 2 hrs", minutes == 120) { minutes = 120 }
                    ChoiceChip("Any length", minutes == null) { minutes = null }
                    Text("Type", style = T.label, modifier = Modifier.padding(start = 16.dp, end = 4.dp))
                    ChoiceChip("Movie", type == "movie") { type = "movie" }
                    ChoiceChip("Show", type == "tv") { type = "tv" }
                    ChoiceChip("Either", type == "any") { type = "any" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Voting", style = T.label, modifier = Modifier.padding(end = 4.dp))
                    s.players.forEach { p -> PlayerChip(p.name, "Votes in turn", true) }
                    if (s.players.size < 4) ChoiceChip("+ Add someone", false) {
                        Together.session.value = s.copy(players = s.players + Together.Player("tv${s.players.size}", "Guest ${s.players.size}", viaPhone = false))
                    }
                    if (s.players.size > 2) ChoiceChip("– Remove", false) { Together.removePlayer(s.players.last().id) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    PillButton("Shuffle the deck", primary = true, height = 40.dp) {
                        Together.setAllPrefs(chosen.toSet(), minutes, type)
                        vm.startTogetherRound()
                    }
                    PillButton("Use phones instead", height = 40.dp) { Together.newSession() }
                }
            }
        }
    }
}

@Composable
private fun RemoteVoting() {
    val session by Together.session.collectAsState()
    val s = session ?: return
    // Whose turn: the first player who hasn't finished the deck.
    val turn = s.players.firstOrNull { s.votedCount(it.id) < s.deck.size } ?: return
    val index = s.votedCount(turn.id)
    val card = s.deck.getOrNull(index) ?: return
    var handedOver by remember(turn.id) { mutableStateOf(false) }
    val focus = remember(turn.id, handedOver) { FocusRequester() }
    LaunchedEffect(turn.id, handedOver, index) { delay(200); runCatching { focus.requestFocus() } }

    if (!handedOver) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (s.players.first().id == turn.id) "FIRST UP" else "NO PEEKING", style = T.eyebrow)
            Text("Pass the remote to ${turn.name}", style = T.serif(44.sp), modifier = Modifier.padding(top = 10.dp))
            Text("${s.deck.size} titles. Nobody sees anyone's votes until the reveal.", style = T.body, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            PillButton("I'm ${turn.name}, let's go", primary = true, height = 42.dp, modifier = Modifier.focusRequester(focus)) { handedOver = true }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        BakedBackdrop(card.backdropUrl ?: card.posterUrl, remember {
            listOf(Brush.horizontalGradient(0f to Color(0xF207060B), 0.5f to Color(0xB307060B), 1f to Color(0x4007060B)), Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE607060B)))
        }, grain = 0.05f)
        Column(Modifier.padding(start = 56.dp, top = 48.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${turn.name.uppercase()}'S TURN · ${index + 1} OF ${s.deck.size}", style = T.eyebrow)
            Box(Modifier.width(320.dp).height(3.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(index / s.deck.size.toFloat()).height(3.dp).background(C.Amber))
            }
            if (card.logoUrl != null) AsyncImage(card.logoUrl, card.title, Modifier.height(90.dp).widthIn(max = 380.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            else Text(card.title, style = T.serif(46.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(card.badge?.takeUnless { it.startsWith("RT ") }, if (card.mediaType == "tv") "Series" else "Movie", card.year.ifBlank { null },
                card.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }, card.ratingLabel,
                card.bestAvailability?.let { Title.availabilityPhrase(it) }).joinToString(" · "), style = T.sans(12.sp, FontWeight.Bold, C.AmberSoft))
            Text(card.overview, style = T.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                PillButton("✕  Nah", height = 44.dp) { Together.vote(turn.id, card.id, -1) }
                PillButton("✓  Yes", primary = true, height = 44.dp, modifier = Modifier.focusRequester(focus)) { Together.vote(turn.id, card.id, 1) }
                PillButton("★  Love it", height = 44.dp) { Together.vote(turn.id, card.id, 2) }
            }
        }
    }
}

// ---------------- Voting on phones ----------------

@Composable
private fun Progress() {
    val session by Together.session.collectAsState()
    val s = session ?: return
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }
    val pulse by animateFloatAsState(if (s.matchCount % 2 == 0) 1f else 1.08f, tween(300), label = "pulse")
    // A quiet chime each time a new match lands.
    var heard by remember { mutableIntStateOf(s.matchCount) }
    LaunchedEffect(s.matchCount) { if (s.matchCount > heard) app.marquee.tv.ui.components.SoundFx.match(); heard = s.matchCount }
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PICK TOGETHER · VOTING", style = T.eyebrow)
        Text("Swipe away on your phones.", style = T.serif(44.sp))
        Text("${s.deck.size} titles from your Plex and your services. The TV keeps everyone's votes secret until the end.", style = T.body)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            s.players.forEach { p ->
                val n = s.votedCount(p.id)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(p.name, style = T.sans(15.sp, FontWeight.Bold), modifier = Modifier.width(140.dp), maxLines = 1)
                    Box(Modifier.width(420.dp).height(6.dp).clip(CircleShape).background(Color(0x26FFFFFF))) {
                        Box(Modifier.fillMaxWidth(if (s.deck.isEmpty()) 0f else n / s.deck.size.toFloat()).fillMaxHeight().clip(CircleShape).background(C.Amber))
                    }
                    Text(if (n >= s.deck.size) "Done ✓" else "$n of ${s.deck.size}", style = T.sans(12.sp, FontWeight.SemiBold, if (n >= s.deck.size) C.Good else C.TextDim))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Box(Modifier.glass(RoundedCornerShape(16.dp), C.GlassWarm).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(
                    when (s.matchCount) { 0 -> "No matches yet"; 1 -> "1 match so far"; else -> "${s.matchCount} matches so far" },
                    style = T.sans((16 * pulse).sp, FontWeight.ExtraBold, if (s.matchCount > 0) C.AmberSoft else C.Text),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Reveal now", primary = s.matchCount > 0, height = 40.dp, modifier = Modifier.focusRequester(first)) { Together.finishNow() }
            PillButton("Start over", height = 40.dp) { Together.newSession() }
        }
    }
}

// ---------------- The reveal ----------------

@Composable
private fun Results(vm: MarqueeVm, nav: Nav) {
    val session by Together.session.collectAsState()
    val s = session ?: return
    val settings by vm.settings.collectAsState()
    val ranked = remember(s) { Together.rank(s) }
    val matches = ranked.filter { it.match }
    val close = ranked.filter { !it.match && it.yes > 0 && it.nah <= 1 }.take(10)
    var pickIndex by remember(s.id) { mutableIntStateOf(0) }
    var spinning by remember { mutableStateOf(false) }
    val pool = matches.ifEmpty { close }
    val winner = pool.getOrNull(pickIndex)
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(400); runCatching { playFocus.requestFocus() }; if (matches.isNotEmpty()) app.marquee.tv.ui.components.SoundFx.match() }

    Box(Modifier.fillMaxSize()) {
        BakedBackdrop(winner?.title?.backdropUrl, remember {
            listOf(Brush.horizontalGradient(0f to Color(0xF207060B), 0.45f to Color(0xA607060B), 1f to Color(0x2607060B)), Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xF207060B)))
        }, grain = 0.05f)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 48.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.padding(horizontal = 56.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (winner == null) {
                        Text("PICK TOGETHER", style = T.eyebrow)
                        Text("Tough crowd.", style = T.serif(48.sp))
                        Text("Nothing got a yes from everyone this time. Try different moods or a longer length.", style = T.body)
                    } else {
                        val t = winner.title
                        Text(
                            when {
                                spinning -> "SHUFFLING…"
                                matches.isNotEmpty() && matches.size == 1 -> "IT'S A MATCH"
                                matches.isNotEmpty() -> "IT'S A MATCH · ${pickIndex + 1} OF ${matches.size}"
                                else -> "NO PERFECT MATCH · YOUR CLOSEST PICK"
                            }, style = T.eyebrow,
                        )
                        if (t.logoUrl != null) AsyncImage(t.logoUrl, t.title, Modifier.height(96.dp).widthIn(max = 420.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(t.title, style = T.serif(52.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(why(winner, s.players.size), style = T.sans(14.sp, FontWeight.SemiBold, Color(0xFFEFE6DA)))
                        Text(listOfNotNull(t.year.ifBlank { null }, t.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }, t.ratingLabel,
                            t.bestAvailability?.let { Title.availabilityPhrase(it) }).joinToString(" · "), style = T.sans(12.sp, FontWeight.Bold, C.AmberSoft))
                        Text(t.overview, style = T.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                            PillButton("▶  Play now", primary = true, height = 42.dp, modifier = Modifier.focusRequester(playFocus)) { vm.app.apps.open(t, t.bestAvailability, settings) }
                            PillButton("Details", height = 42.dp) { nav.go(Screen.Detail(t)) }
                            if (pool.size > 1) PillButton("Surprise us", height = 42.dp) {
                                if (!spinning) scope.launch {
                                    spinning = true
                                    val target = (pool.indices - pickIndex).random()
                                    var d = 70L
                                    repeat(10) { pickIndex = (pickIndex + 1) % pool.size; delay(d); d += 25 }
                                    pickIndex = target; spinning = false
                                }
                            }
                            PillButton("New round", height = 42.dp) {
                                Together.newSession(remote = s.remote, localNames = if (s.remote) s.players.map { it.name } else emptyList())
                            }
                        }
                    }
                }
            }
            val others = matches.filter { it.title.id != winner?.title?.id }.map { it.title }
            if (others.isNotEmpty()) item {
                Column {
                    SectionHeader("Your other matches", "Everyone said yes", Modifier.padding(start = 48.dp))
                    MediaRow(others, 200.dp, 112.dp, onFocus = {}, onClick = { t -> pickIndex = pool.indexOfFirst { it.title.id == t.id }.coerceAtLeast(0) })
                }
            }
            val closeTitles = (if (matches.isEmpty()) close.drop(1) else close).map { it.title }
            if (closeTitles.isNotEmpty()) item {
                Column {
                    SectionHeader("Close calls", "Most of you liked these", Modifier.padding(start = 48.dp))
                    MediaRow(closeTitles, 170.dp, 96.dp, onFocus = {}, onClick = { nav.go(Screen.Detail(it)) })
                }
            }
        }
    }
}

private fun why(x: Together.Scored, players: Int): String = when {
    x.love == players && players > 1 -> "You all loved it."
    x.lovedBy.isNotEmpty() && x.match -> "${x.lovedBy.joinToString(" & ")} loved it, and everyone said yes."
    x.match && players == 1 -> "You said yes."
    x.match -> if (players == 2) "You both said yes." else "All $players of you said yes."
    else -> "${x.yes} of $players said yes" + (if (x.lovedBy.isNotEmpty()) " · ${x.lovedBy.joinToString(" & ")} loved it" else "") + "."
}
