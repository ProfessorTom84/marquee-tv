package app.marquee.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.Screen
import app.marquee.tv.data.Title
import app.marquee.tv.recs.Recommender
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.GrainOverlay
import app.marquee.tv.ui.components.MediaRow
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.Scrim
import app.marquee.tv.ui.components.SectionHeader
import app.marquee.tv.ui.components.TitleCard
import app.marquee.tv.ui.components.glass
import app.marquee.tv.ui.home.AppTile
import app.marquee.tv.ui.home.openApp
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val screenBg = Brush.radialGradient(listOf(Color(0xFF2A3670), Color(0xFF111832), Color(0xFF070B18)), radius = 2200f, center = androidx.compose.ui.geometry.Offset(1400f, 100f))

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(
            Modifier.clip(CircleShape)
                .background(if (selected) Color(0x33F5B54A) else if (f) Color(0x29FFFFFF) else Color(0x12FFFFFF))
                .border(if (f) 2.dp else 1.dp, if (f) C.Text else if (selected) Color(0x99F5B54A) else Color(0x1FFFFFFF), CircleShape)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) { Text(label, style = T.sans(11.sp, FontWeight.SemiBold, if (selected) C.AmberSoft else C.Text)) }
    }
}

// ============ "What should we watch?" (filters, no AI) ============

private data class Mood(val label: String, val genres: List<Int>)
private val moods = listOf(
    Mood("Funny", listOf(35)), Mood("Thrilling", listOf(53, 28)), Mood("Feel-good", listOf(10751, 35)),
    Mood("Mind-bending", listOf(878, 9648)), Mood("Scary", listOf(27)), Mood("Romantic", listOf(10749)),
    Mood("Animated", listOf(16)), Mood("True stories", listOf(99, 36)), Mood("Epic", listOf(12, 14, 10752)), Mood("Crime", listOf(80)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(vm: MarqueeVm, nav: Nav) {
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateListOf<Int>() }
    var runtime by remember { mutableStateOf<Int?>(120) }
    var type by remember { mutableStateOf("movie") }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun run() { busy = true; scope.launch { results = runCatching { vm.pick(Recommender.Filters(chosen.flatMap { moods[it].genres }, runtime, type)) }.getOrDefault(emptyList()); busy = false } }

    Box(Modifier.fillMaxSize().background(screenBg)) {
        GrainOverlay()
        LazyColumn(contentPadding = PaddingValues(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("QUICK PICKS · JUST ME", style = T.eyebrow)
                    Text("Pick a mood. Only things you can watch right now, nothing to rent or buy.", style = T.serif(30.sp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        moods.forEachIndexed { i, m ->
                            Box(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                ChoiceChip(m.label, i in chosen) { if (i in chosen) chosen.remove(i) else chosen.add(i) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Length", style = T.label, modifier = Modifier.padding(end = 4.dp))
                        ChoiceChip("Under 90 min", runtime == 90) { runtime = 90 }
                        ChoiceChip("Under 2 hrs", runtime == 120) { runtime = 120 }
                        ChoiceChip("Any length", runtime == null) { runtime = null }
                        Text("Type", style = T.label, modifier = Modifier.padding(start = 16.dp, end = 4.dp))
                        ChoiceChip("Movie", type == "movie") { type = "movie" }
                        ChoiceChip("Show", type == "tv") { type = "tv" }
                        ChoiceChip("Either", type == "any") { type = "any" }
                    }
                    PillButton(if (busy) "Finding picks…" else "Show picks", primary = true, height = 38.dp) { if (!busy) run() }
                }
            }
            results?.let { list ->
                if (list.isEmpty()) item { Text("Nothing matched. Try another mood or a longer length.", style = T.body, modifier = Modifier.padding(horizontal = 48.dp)) }
                else {
                    item {
                        Column {
                            SectionHeader("Best matches", "${list.size} picks · your Plex first, then your services", Modifier.padding(start = 48.dp))
                            MediaRow(list.take(3), 280.dp, 158.dp, onFocus = {}, onClick = { nav.go(Screen.Detail(it)) })
                        }
                    }
                    if (list.size > 3) item {
                        Column {
                            SectionHeader("More like that", modifier = Modifier.padding(start = 48.dp))
                            MediaRow(list.drop(3), 170.dp, 96.dp, onFocus = {}, onClick = { nav.go(Screen.Detail(it)) })
                        }
                    }
                }
            }
        }
    }
}

// ============ Search ============

@Composable
fun SearchScreen(vm: MarqueeVm, nav: Nav) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    fun search(text: String) {
        job?.cancel()
        job = scope.launch { delay(450); results = runCatching { vm.search(text) }.getOrDefault(emptyList()) }
    }
    Box(Modifier.fillMaxSize().background(screenBg)) {
        GrainOverlay()
        Column(Modifier.padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SEARCH YOUR PLEX AND YOUR SERVICES", style = T.eyebrow)
                BasicTextField(
                    value = q, onValueChange = { q = it; search(it) },
                    textStyle = T.serif(30.sp), cursorBrush = SolidColor(C.Amber), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(q) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr).glass(CircleShape).padding(horizontal = 22.dp, vertical = 12.dp),
                    decorationBox = { inner -> if (q.isEmpty()) Text("Type a title, actor or show", style = T.serif(30.sp, C.TextMute)); inner() },
                )
            }
            if (results.isNotEmpty()) {
                val mine = results.filter { it.availability.isNotEmpty() }
                val elsewhere = results.filter { it.availability.isEmpty() }
                if (mine.isNotEmpty()) Column {
                    SectionHeader("You can watch", "Free to you", Modifier.padding(start = 48.dp))
                    MediaRow(mine, 186.dp, 105.dp, onFocus = {}, onClick = { nav.go(Screen.Detail(it)) })
                }
                if (elsewhere.isNotEmpty()) Column {
                    SectionHeader("Not on your services", "For your info · nothing to buy or rent", Modifier.padding(start = 48.dp))
                    MediaRow(elsewhere, 160.dp, 90.dp, onFocus = {}, onClick = { nav.go(Screen.Detail(it)) }, dimmed = true)
                }
            } else if (q.length > 1) Text("Searching…", style = T.body, modifier = Modifier.padding(horizontal = 48.dp))
        }
    }
}

// ============ All apps ============

@Composable
fun AllAppsScreen(vm: MarqueeVm, nav: Nav) {
    val apps by vm.apps.collectAsState()
    Box(Modifier.fillMaxSize().background(screenBg)) {
        GrainOverlay()
        Column(Modifier.padding(top = 36.dp)) {
            Text("All apps", style = T.serif(34.sp), modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
            LazyVerticalGrid(
                GridCells.Fixed(6), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(apps.size) { i -> AppTile(apps[i], 128) { openApp(vm, apps[i]) } }
            }
        }
    }
}

