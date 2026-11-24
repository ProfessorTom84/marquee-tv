package app.marquee.tv.ui.screens

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

