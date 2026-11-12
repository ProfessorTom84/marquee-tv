package app.marquee.tv.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.data.HomeStyle
import app.marquee.tv.data.Profile
import app.marquee.tv.data.Providers
import app.marquee.tv.data.Settings
import app.marquee.tv.plex.PlexPin
import app.marquee.tv.setup.SetupServer
import app.marquee.tv.system.MarqueeAccessibilityService
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.components.GrainOverlay
import app.marquee.tv.ui.components.PillButton
import app.marquee.tv.ui.components.QrImage
import app.marquee.tv.ui.components.Toggle
import app.marquee.tv.ui.components.ValueRow
import app.marquee.tv.ui.components.glass
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import app.marquee.tv.youtube.DeviceCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val sections = listOf(
    "style" to "Home style",
    "profiles" to "Profiles",
    "accounts" to "Accounts & setup",
    "services" to "Your services",
    "calendar" to "Calendar & alerts",
    "apps" to "Apps on home",
    "rows" to "Home rows",
    "home" to "Home button & players",
    "weather" to "Weather & background",
    "permissions" to "Permissions & privacy",
    "updates" to "Updates",
    "perf" to "Performance check",
)

@Composable
fun SettingsScreen(vm: MarqueeVm, nav: Nav, initial: String) {
    var section by remember { mutableStateOf(initial) }
    val s by vm.settings.collectAsState()
    val navFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { navFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1C2550), Color(0xFF0B1024), Color(0xFF05070F)), radius = 2400f, center = androidx.compose.ui.geometry.Offset(1500f, 0f)))) {
        GrainOverlay()
        Row(Modifier.fillMaxSize()) {
            // The menu scrolls: with every section listed it's taller than the screen.
            app.marquee.tv.ui.components.CalmScroll {
            Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0x08FFFFFF)).verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 40.dp, end = 16.dp, top = 30.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Settings", style = T.serif(30.sp), modifier = Modifier.padding(bottom = 14.dp))
                SystemLink("Google TV settings") { vm.app.apps.openSystemSettings() }
                SystemLink("Wi-Fi & network") { vm.app.apps.openSystemSettings(AndroidSettings.ACTION_WIFI_SETTINGS) }
                SystemLink("Display & sound") { vm.app.apps.openSystemSettings(AndroidSettings.ACTION_DISPLAY_SETTINGS) }
                Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
                Text("MARQUEE", style = T.label, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                sections.forEach { (key, label) ->
                    // Sections open on OK, not on focus: if focus jumps (for example when a button disappears)
                    // the page you're on stays put.
                    Focusable(Modifier.fillMaxWidth().then(if (key == initial) Modifier.focusRequester(navFocus) else Modifier), onClick = { section = key }) { f ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(if (section == key) Color(0x1AFFFFFF) else Color.Transparent)
                                .then(if (f) Modifier.border(1.5.dp, C.Amber, RoundedCornerShape(9.dp)) else Modifier)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) { Text(label, style = T.sans(11.sp, if (section == key) FontWeight.ExtraBold else FontWeight.SemiBold, if (section == key || f) C.Text else C.Cool)) }
                    }
                }
            }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 36.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    when (section) {
                        "style" -> StyleSection(vm, s)
                        "profiles" -> ProfilesSection(vm, s)
                        "accounts" -> AccountsSection(vm, s)
                        "services" -> ServicesSection(vm, s)
                        "calendar" -> CalendarSection(vm, s)
                        "apps" -> AppsSection(vm, s)
                        "rows" -> RowsSection(vm, s)
                        "home" -> HomeButtonSection(vm, s)
                        "weather" -> WeatherSection(vm, s, nav)
                        "permissions" -> PermissionsSection(vm)
                        "updates" -> UpdatesSection(vm, s)
                        "perf" -> PerfSection(vm, nav)
                    }
                }
            }
        }
    }
}

@Composable private fun H(title: String, sub: String = "") {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, style = T.sans(20.sp, FontWeight.ExtraBold))
        if (sub.isNotBlank()) Text(sub, style = T.body)
    }
}

@Composable private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private val pickable = HomeStyle.entries.filter { it != HomeStyle.AMBIENT }
private fun next(st: HomeStyle) = pickable[(pickable.indexOf(st) + 1).mod(pickable.size)]

@Composable
private fun StyleSection(vm: MarqueeVm, s: Settings) {
    val store = vm.app.store
    H("Home style", "Choose how your home screen looks. Profiles can override this.")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        pickable.forEach { st ->
            Focusable(Modifier.width(190.dp), onClick = {
                // Picking a style means "show me this": it wins over the time-of-day schedule and the profile's own style.
                store.update { cur ->
                    cur.copy(homeStyle = st, autoSwitch = false,
                        profiles = cur.profiles.mapIndexed { i, p -> if (i == cur.activeProfile) p.copy(style = "AUTO") else p })
                }
            }) { f ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sel = vm.styleNow() == st
                    Box(
                        Modifier.fillMaxWidth().height(107.dp).clip(RoundedCornerShape(12.dp))
                            .background(
                                when (st) {
                                    HomeStyle.DASHBOARD -> Brush.verticalGradient(listOf(Color(0xFF0A1230), Color(0xFF24305E), Color(0xFF4B3D6E)))
                                    HomeStyle.CINEMATIC -> Brush.linearGradient(listOf(Color(0xFF3A1E0E), Color(0xFFB8692E), Color(0xFFE7A35A)))
                                    HomeStyle.AMBIENT -> Brush.radialGradient(listOf(Color(0xFF3A2F66), Color(0xFF161638), Color(0xFF03040A)))
                                },
                            )
                            .border(if (f) 3.dp else if (sel) 2.dp else 1.dp, if (f) C.Text else if (sel) C.Amber else C.Stroke, RoundedCornerShape(12.dp)),
                    ) {
                        when (st) {
                            HomeStyle.DASHBOARD -> Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("7:42", style = T.serif(18.sp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(50.dp, 22.dp).glass(RoundedCornerShape(5.dp))) } }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(4) { Box(Modifier.size(38.dp, 21.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x66FFFFFF))) } }
                            }
                            HomeStyle.CINEMATIC -> Column(Modifier.padding(10.dp)) {
                                Text("TITLE", style = T.serif(24.sp, spacing = 4.sp))
                                Box(Modifier.height(30.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(4) { Box(Modifier.size(38.dp, 21.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x66FFFFFF))) } }
                            }
                            HomeStyle.AMBIENT -> Text("7:42", style = T.serif(40.sp), modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                        }
                        if (sel) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp).clip(RoundedCornerShape(9.dp)).background(C.Amber), contentAlignment = Alignment.Center) {
                            Text("✓", style = T.sans(10.sp, FontWeight.ExtraBold, Color(0xFF1A1206)))
                        }
                    }
                    Text(st.label, style = T.sans(13.sp, FontWeight.ExtraBold))
                    Text(st.blurb, style = T.small)
                }
            }
        }
    }
    Text("Showing now: ${vm.styleNow().label} · ${vm.styleReason()}", style = T.sans(11.sp, FontWeight.SemiBold, C.AmberSoft), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
    Group {
        Toggle(s.autoSwitch, "Change style by time of day", "Off: always your choice above. On: the schedule below takes over", { store.update { it.copy(autoSwitch = !it.autoSwitch) } })
        if (s.autoSwitch) {
            ValueRow("Mornings · 5 – 11 AM", s.morningStyle.label, onClick = { store.update { it.copy(morningStyle = next(it.morningStyle)) } })
            ValueRow("Evenings · 5 PM onward", s.eveningStyle.label, onClick = { store.update { it.copy(eveningStyle = next(it.eveningStyle)) } })
        }
        Toggle(s.uiSounds, "Interface sounds", "A soft tick as you move, a quiet chime when Pick Together finds a match", {
            store.update { it.copy(uiSounds = !it.uiSounds) }
            app.marquee.tv.ui.components.SoundFx.enabled = !s.uiSounds
        })
        Toggle(
            s.news, "News on the Dashboard",
            "Local, state and national headlines across politics, tech and business. Every story opens on the TV. " +
                (if (s.cityName.isBlank()) "Add your city (Weather & background) for local and state news. " else "") + "Never shown on kids' profiles",
            { store.update { it.copy(news = !it.news) } },
        )
        ScreensaverSettings(vm, s)
    }
}

/**
 * One screensaver choice for both going idle and pressing Back on the home screen, plus what Android itself will
 * run when idle (its system screensaver), which can be left pointing at Marquee's even after picking Google
 * Photos in Google TV's settings.
 */
@Composable
private fun ScreensaverSettings(vm: MarqueeVm, s: Settings) {
    val store = vm.app.store
    val ctx = LocalContext.current
    val ss = app.marquee.tv.system.Screensaver
    var tick by remember { mutableStateOf(0) }
    val google = remember { ss.google(ctx) }
    val marquee = remember { ss.marquee(ctx) }
    val canChange = remember(tick) { ss.canChange(ctx) }
    val readable = remember(tick) { ss.readable(ctx) }
    val current = remember(tick, s.backAction) { ss.current(ctx) }
    val want = when (s.backAction) { "GOOGLE" -> google?.component; "AMBIENT" -> marquee; else -> null }
    fun applySystem(choice: String) {
        val target = when (choice) { "GOOGLE" -> google?.component; "AMBIENT" -> marquee; else -> null } ?: return
        if (ss.canChange(ctx)) ss.use(ctx, target)
        tick++
    }
    ValueRow(
        "Screensaver", when (s.backAction) { "AMBIENT" -> "Marquee Ambient"; "NONE" -> "None"; else -> "Google TV screensaver" },
        sub = when (s.backAction) {
            "AMBIENT" -> "Clock, weather and the live sky, when the TV is idle and when you press Back on the home screen"
            "NONE" -> "Back just returns to the top. The TV's own screensaver timer still applies"
            else -> "Google Photos, art or whatever you've picked in Google TV's settings. It starts after the time set there, and when you press Back on the home screen (needs the Marquee accessibility service)"
        },
        onClick = {
            val next = when (s.backAction) { "GOOGLE" -> "AMBIENT"; "AMBIENT" -> "NONE"; else -> "GOOGLE" }
            store.update { it.copy(backAction = next) }
            applySystem(next)
        },
    )
    if (s.backAction == "AMBIENT") {
        Toggle(s.idleAmbient, "Start Marquee Ambient when idle", "After a while without input", { store.update { it.copy(idleAmbient = !it.idleAmbient) } })
        if (s.idleAmbient) ValueRow("Start it after", "${s.idleMinutes} min", onClick = {
            val opts = listOf(5, 10, 15, 30, 60); store.update { it.copy(idleMinutes = opts[(opts.indexOf(it.idleMinutes) + 1) % opts.size]) }
        })
    }
    // What Android itself runs when the TV is idle.
    if (want != null) {
        val ok = current != null && current == want
        val wantName = if (want == marquee) "Marquee Ambient" else "Google TV's screensaver"
        Text(
            when {
                ok -> "Android's screensaver: ${ss.describe(ctx, current)}. All set."
                current != null -> "Android is set to run ${ss.describe(ctx, current)} when the TV is idle, not $wantName."
                readable -> "Android has no screensaver set."
                else -> "Marquee can't check which screensaver Android runs when idle."
            },
            style = T.sans(11.sp, FontWeight.SemiBold, if (ok) C.Good else C.AmberSoft), modifier = Modifier.padding(top = 4.dp),
        )
        if (!ok) {
            if (canChange) PillButton("Use $wantName when idle", primary = true, height = 34.dp) { ss.use(ctx, want); tick++ }
            else {
                Text("To fix it, run these once from a computer (or let Marquee do it: the first one lets Marquee change it for you):", style = T.small)
                Text(ss.GRANT_COMMAND, style = T.sans(10.sp, FontWeight.Bold, C.Text))
                Text(ss.command(want), style = T.sans(10.sp, FontWeight.Bold, C.Text))
                PillButton("Check again", height = 30.dp) { tick++ }
            }
        }
    }
}

@Composable
private fun PerfSection(vm: MarqueeVm, nav: Nav) {
    val check = app.marquee.tv.system.PerfCheck
    val phase by check.phase.collectAsState()
    val activity = LocalContext.current as? android.app.Activity
    val last = remember(phase) { (phase as? app.marquee.tv.system.PerfCheck.Phase.Done)?.result?.lines() }
    val saved = remember(phase) { if (last == null) check.lastText(vm.app) else null }
    H("Performance check", "Measures how smoothly the home screen scrolls on this TV and what costs the most. It takes about ${app.marquee.tv.system.PerfCheck.SECONDS} seconds in ${app.marquee.tv.system.PerfCheck.steps.size} steps: first don't press anything, then scroll up and down the home screen until it's done (the sky and card effects switch off for a moment while it measures them). Follow the banner at the top. The results come back here, and on the phone setup page so you can copy them.")
    Group {
        PillButton("Start check", primary = true, height = 38.dp) {
            if (activity != null) {
                check.start(activity) {
                    val st = vm.settings.value
                    val w = vm.weather.value
                    listOf(
                        "Home screen" to "${vm.styleNow().label} style · background ${if (st.background == "PHOTO") "photo" else "live sky"} · " +
                            (w?.let { "${it.sky.name.lowercase()}, ${if (it.isDay) "day" else "night"}" } ?: "no weather") +
                            " · ${vm.home.value?.rows?.size ?: 0} rows",
                    )
                }
                nav.home()
            }
        }
        when {
            last != null -> last.forEach { (k, v) -> ValueRow(k, v, onClick = {}) }
            saved != null -> {
                Text("LAST CHECK", style = T.label, modifier = Modifier.padding(top = 8.dp))
                saved.lines().forEach { line -> Text(line, style = T.body) }
            }
            else -> Text("No check run yet.", style = T.body, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun UpdatesSection(vm: MarqueeVm, s: Settings) {
    val st by app.marquee.tv.system.Updater.state.collectAsState()
    val (version, _) = remember { app.marquee.tv.system.Updater.currentVersion(vm.app) }
    H("Updates", "Marquee updates itself from your private GitHub repo. New versions download in the background and install overnight while the TV is idle, so Android has time to optimise them before you watch.")
    Group {
        ValueRow("This version", version, onClick = {})
        ValueRow("Update source", s.updateRepo.ifBlank { "Not set up" },
            sub = if (s.updateRepo.isBlank()) "Add the repo on the phone setup page (Accounts & setup)"
                else if (s.updateToken.isNotBlank()) "Using the token saved on this TV"
                else if (app.marquee.tv.BuildConfig.UPDATE_TOKEN.isNotBlank()) "Using the built-in read-only token"
                else "No token yet: add one on the phone setup page",
            onClick = {})
        Toggle(s.autoUpdate, "Update automatically", "Check every few hours and install overnight while the TV is idle", { vm.app.store.update { it.copy(autoUpdate = !it.autoUpdate) } })
        Text(st.message.ifBlank { "Not checked yet" }, style = T.sans(12.sp, FontWeight.SemiBold, if (st.phase == app.marquee.tv.system.Updater.Phase.ERROR) C.Warn else C.AmberSoft), modifier = Modifier.padding(vertical = 4.dp))
        if (st.notes.isNotBlank() && st.latest != null) Text("What's new in ${st.latest}: ${st.notes}", style = T.small, maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val working = st.phase == app.marquee.tv.system.Updater.Phase.CHECKING || st.phase == app.marquee.tv.system.Updater.Phase.DOWNLOADING
            PillButton(if (working) "Checking…" else "Check now", height = 34.dp) { app.marquee.tv.system.Updater.checkNow(vm.app) }
            if (st.phase == app.marquee.tv.system.Updater.Phase.READY) PillButton("Install now", primary = true, height = 34.dp) { app.marquee.tv.system.Updater.install(vm.app) }
        }
    }
}

@Composable
private fun RowsSection(vm: MarqueeVm, s: Settings) {
    val custom = app.marquee.tv.recs.FeedLayout.RowGroup.parse(s.rowOrder)
    val hidden = app.marquee.tv.recs.FeedLayout.RowGroup.parse(s.hiddenRows).toSet()
    // What's shown now: your order, or Marquee's own (by feed balance) until you move something.
    val order = (custom + app.marquee.tv.recs.FeedLayout.defaultOrder(s.feedBalance)).distinct()
    val shown = order.filter { it !in hidden }
    H("Home rows", "Put the rows you care about first and hide the ones you don't. \"For you\" always comes first. " +
        if (custom.isEmpty()) "Right now Marquee mixes your services, moods and \"Because you watched…\" rows together; moving a row switches to your own order." else "Using your own order.")
    Group {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1", style = T.sans(11.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(vm.app.recommender.forYouTitle(), style = T.sans(13.sp, FontWeight.Bold))
                Text("Always first", style = T.small)
            }
        }
        shown.forEachIndexed { i, g ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 2}", style = T.sans(11.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.label, style = T.sans(13.sp, FontWeight.Bold), maxLines = 1)
                    if (g.blurb.isNotBlank()) Text(g.blurb, style = T.small, maxLines = 1)
                }
                PillButton("◀ Earlier", height = 30.dp) { vm.moveRowGroup(shown + hidden.toList(), g, -1) }
                PillButton("Later ▶", height = 30.dp) { vm.moveRowGroup(shown + hidden.toList(), g, 1) }
                PillButton("Hide", height = 30.dp) { vm.setRowGroupHidden(g, true) }
            }
        }
    }
    if (hidden.isNotEmpty()) {
        Text("HIDDEN", style = T.label, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Group {
            order.filter { it in hidden }.forEach { g ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.label, style = T.sans(13.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.weight(1f), maxLines = 1)
                    PillButton("Show on home", height = 30.dp) { vm.setRowGroupHidden(g, false) }
                }
            }
        }
    }
    if (custom.isNotEmpty() || hidden.isNotEmpty()) PillButton("Back to Marquee's mix", height = 34.dp, modifier = Modifier.padding(top = 12.dp)) { vm.resetRows() }
}

@Composable
private fun AppsSection(vm: MarqueeVm, s: Settings) {
    val all by vm.apps.collectAsState()
    val preferredYt = remember(s, all) { vm.app.apps.preferredYouTube(s) }
    val shown = remember(all, s.services, s.appOrder, s.hiddenApps, preferredYt) {
        app.marquee.tv.ui.home.orderedApps(all, s, preferredYt, vm.app.apps.plexPlayerPackage(s))
    }
    val hidden = all.filter { it.packageName in s.hiddenApps }
    H("Apps on home", "Put your favourites first and hide the ones you never open. Tip: on the home screen, hold OK on an app to move or hide it right there.")
    Group {
        shown.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}", style = T.sans(11.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.width(24.dp))
                Text(a.label, style = T.sans(13.sp, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
                if (i < 12) Text("In ribbon", style = T.sans(10.sp, color = C.Good)) else Text("In All apps", style = T.sans(10.sp, color = C.TextDim))
                PillButton("◀ Earlier", height = 30.dp) { vm.moveApp(shown.map { it.packageName }, a.packageName, -1) }
                PillButton("Later ▶", height = 30.dp) { vm.moveApp(shown.map { it.packageName }, a.packageName, 1) }
                PillButton("Hide", height = 30.dp) { vm.setAppHidden(a.packageName, true) }
            }
        }
    }
    if (hidden.isNotEmpty()) {
        Text("HIDDEN", style = T.label, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Group {
            hidden.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.label, style = T.sans(13.sp, FontWeight.Bold, C.TextDim), modifier = Modifier.weight(1f), maxLines = 1)
                    PillButton("Show on home", height = 30.dp) { vm.setAppHidden(a.packageName, false) }
                }
            }
        }
    }
    PillButton("Reset to the automatic order", height = 34.dp, modifier = Modifier.padding(top = 12.dp)) { vm.resetApps() }
}

@Composable
private fun ProfilesSection(vm: MarqueeVm, s: Settings) {
    val store = vm.app.store
    H("Profiles", "Each person gets their own home style. Kid-safe profiles only show family-friendly picks and hide YouTube.")
    s.profiles.forEachIndexed { i, p ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ValueRow(
                (if (i == s.activeProfile) "● " else "") + p.name,
                if (p.style == "AUTO") "Automatic" else HomeStyle.valueOf(p.style).label,
                sub = if (p.kidSafe) "Kid-safe" else "",
                onClick = {
                    val order = listOf("AUTO") + pickable.map { it.name }
                    store.update { st -> st.copy(profiles = st.profiles.toMutableList().also { l -> l[i] = p.copy(style = order[(order.indexOf(p.style) + 1) % order.size]) }) }
                },
                modifier = Modifier.weight(1f),
            )
            PillButton(if (p.kidSafe) "Kid-safe on" else "Kid-safe off") { store.update { st -> st.copy(profiles = st.profiles.toMutableList().also { l -> l[i] = p.copy(kidSafe = !p.kidSafe) }) } }
            PillButton("Use") { store.update { it.copy(activeProfile = i) } }
            if (s.profiles.size > 1) PillButton("Remove") {
                store.update { st -> st.copy(profiles = st.profiles.filterIndexed { j, _ -> j != i }, activeProfile = 0) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        PillButton("Add profile", primary = true) { store.update { it.copy(profiles = it.profiles + Profile("Profile ${it.profiles.size + 1}", "AUTO")) } }
        PillButton("Add kids profile") { store.update { it.copy(profiles = it.profiles + Profile("Kids", HomeStyle.CINEMATIC.name, kidSafe = true)) } }
    }
    Text("Rename profiles from the phone setup page under Accounts & setup.", style = T.small, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun AccountsSection(vm: MarqueeVm, s: Settings) {
    val plex = vm.app.plex
    val yt = vm.app.youtube
    val pinCode by vm.plexCode.collectAsState()
    val plexMsg by vm.plexMessage.collectAsState()
    val code by vm.ytCode.collectAsState()
    val ytMsg by vm.ytMessage.collectAsState()
    val setupUrl = remember { vm.app.ensureSetupServer()?.url() }
    val cancelPlex = remember { FocusRequester() }
    val cancelYt = remember { FocusRequester() }
    // Keep focus on the card when its button is replaced by the code.
    LaunchedEffect(pinCode) { if (pinCode != null) runCatching { cancelPlex.requestFocus() } }
    LaunchedEffect(code) { if (code != null) runCatching { cancelYt.requestFocus() } }

    H("Accounts & setup", "Sign-ins happen on your phone. Keys and tokens stay on this TV.")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone setup", style = T.sans(14.sp, FontWeight.ExtraBold))
            Text("Scan to paste keys, your calendar link, home address and services from your phone.", style = T.body)
            if (setupUrl != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(setupUrl, 110.dp)
                Column { Text("Or open", style = T.small); Text(setupUrl, style = T.sans(10.sp, FontWeight.Bold, C.AmberSoft)) }
            } else Text("Connect the TV to your home network to use phone setup.", style = T.small)
            Text("Phone and TV must be on the same Wi-Fi.", style = T.small)
        }
        Column(Modifier.weight(1f).glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plex", style = T.sans(14.sp, FontWeight.ExtraBold))
            when {
                s.plexReady -> {
                    Text("Connected to ${s.plexServerName}", style = T.bodyStrong)
                    Text(s.plexServerUri, style = T.small)
                    PillButton("Sign out of Plex") { plex.signOut() }
                }
                pinCode != null -> {
                    Text("On your phone or computer, go to plex.tv/link and enter", style = T.body)
                    Text(pinCode!!, style = T.serif(44.sp, C.AmberSoft, 6.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrImage("https://plex.tv/link", 80.dp)
                        Text("Scan to open plex.tv/link", style = T.small)
                    }
                    Text(plexMsg, style = T.small)
                    PillButton("Cancel", modifier = Modifier.focusRequester(cancelPlex)) { vm.cancelPlexLink() }
                }
                else -> {
                    Text("Link your Plex account to get recommendations, artwork and trailers from your library.", style = T.body)
                    PillButton("Link Plex", primary = true) { vm.linkPlex() }
                    if (plexMsg.isNotBlank()) Text(plexMsg, style = T.small)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("YouTube subscriptions", style = T.sans(14.sp, FontWeight.ExtraBold))
        when {
            !yt.configured -> Text("Add your Google OAuth client ID and secret on the phone setup page first. Videos open in your preferred YouTube app.", style = T.body)
            yt.signedIn -> { Text("Signed in. New uploads appear on your home screen.", style = T.bodyStrong); PillButton("Sign out of YouTube") { yt.signOut() } }
            code != null -> {
                Text("Go to ${code!!.verifyUrl} and enter", style = T.body)
                Text(code!!.userCode, style = T.serif(40.sp, C.AmberSoft, 4.sp))
                Text(ytMsg, style = T.small)
                PillButton("Cancel", modifier = Modifier.focusRequester(cancelYt)) { vm.cancelYouTubeLink() }
            }
            else -> PillButton("Sign in with Google", primary = true) { vm.linkYouTube() }
        }
        if (ytMsg.isNotBlank() && code == null) Text(ytMsg, style = T.small)
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("YouTube without signing in", style = T.sans(14.sp, FontWeight.ExtraBold))
        Text("New videos come from YouTube's public channel feeds: no account, no ads. Pick what you like, and paste your own channels on the phone setup page to make it yours.", style = T.small)
        app.marquee.tv.youtube.YouTubeInterests.packs.forEach { p ->
            Toggle(p.key in s.youtubeInterests, p.label, "${p.channels.size} channels", {
                vm.app.store.update { it.copy(youtubeInterests = if (p.key in it.youtubeInterests) it.youtubeInterests - p.key else it.youtubeInterests + p.key) }
                vm.refreshAll()
            })
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HealthPanel(vm)
        PillButton("Refresh recommendations", onClick = { app.marquee.tv.net.Http.clearCache(); vm.refreshAll(); vm.checkHealth() })
    }
}

@Composable
private fun ServicesSection(vm: MarqueeVm, s: Settings) {
    val store = vm.app.store
    H("Your services", "Picks only come from these plans, your Plex library, and genuinely free sources. Rentals, purchases and ad-supported tiers are never shown.")
    ValueRow(
        "Feed balance", s.feedBalance.label,
        sub = "${s.feedBalance.blurb}. Applies to the home rows and Pick Together.",
        onClick = { store.update { it.copy(feedBalance = it.feedBalance.next()) } },
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Providers.all.forEach { p ->
        val installed = p.packages.any { vm.app.apps.isInstalled(it) }
        Toggle(p.id in s.services, p.name, if (installed) "App installed" else "App not installed on this TV", {
            store.update { it.copy(services = if (p.id in it.services) it.services - p.id else it.services + p.id) }
        })
    }
}

@Composable
private fun CalendarSection(vm: MarqueeVm, s: Settings) {
    val events by vm.events.collectAsState()
    val up by vm.upNext.collectAsState()
    val store = vm.app.store
    H("Calendar & alerts", "Marquee reads your calendar's private iCal link and warns you when it's time to leave, even over a movie.")
    Group {
        ValueRow("Calendars", if (s.calendarUrls.isEmpty()) "Add on phone setup page" else "${s.calendarUrls.size} linked · ${events.size} upcoming", onClick = {})
        ValueRow("Home address", s.homeAddress.ifBlank { "Add on phone setup page" }, onClick = {})
        ValueRow("Live traffic", if (vm.app.traffic.configured) "On" else "Needs a Routes API key", onClick = {})
        ValueRow("Extra time before leaving", "${s.travelBufferMinutes} min", onClick = {
            val opts = listOf(0, 5, 10, 15); store.update { it.copy(travelBufferMinutes = opts[(opts.indexOf(it.travelBufferMinutes) + 1) % opts.size]) }
        })
        ValueRow("Alerts over other apps", if (MarqueeAccessibilityService.running) "On" else "Turn on in Permissions", onClick = {})
        up?.let { u ->
            Text("Next: ${u.event.title} at ${app.marquee.tv.system.TimeFmt.full(u.event.start)}" + (u.leaveBy?.let { " · leave by ${app.marquee.tv.system.TimeFmt.full(it)}" } ?: ""), style = T.body, modifier = Modifier.padding(top = 6.dp))
        }
        PillButton("Reload calendar") { app.marquee.tv.net.Http.clearCache(); vm.refreshCalendar() }
    }
}

@Composable
private fun HomeButtonSection(vm: MarqueeVm, s: Settings) {
    val store = vm.app.store
    val alts = remember { vm.app.apps.youtubeAlternatives() }
    val ctx = LocalContext.current
    H("Home button & players", "Google TV doesn't let apps replace the home screen directly, so Marquee uses an accessibility service to step in.")
    Group {
        val plezyInstalled = vm.app.apps.isInstalled(Providers.PLEZY_PACKAGE)
        ValueRow("Play my Plex library in",
            when (s.plexPlayer) { "PLEZY" -> "Plezy"; "PLEX" -> "Plex"; else -> "Automatic (${vm.app.apps.plexPlayerName(s)})" },
            sub = if (plezyInstalled) "Plezy is installed" else "Plezy isn't installed. Get it from the Play Store or plezy.app",
            onClick = { val o = listOf("AUTO", "PLEZY", "PLEX"); store.update { it.copy(plexPlayer = o[(o.indexOf(it.plexPlayer) + 1) % o.size]) } })
        Toggle(s.redirectPlex, "Open Plezy instead of the Plex app", "Applies wherever Plex would open, including from other apps", { store.update { it.copy(redirectPlex = !it.redirectPlex) } })
        ValueRow("Accessibility service", if (MarqueeAccessibilityService.running) "Running" else "Off", sub = "Needed for Home takeover, YouTube redirect and leave-now alerts", onClick = {
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        Toggle(s.takeoverHome, "Open Marquee when I press Home", "Also opens Marquee after the TV starts", { store.update { it.copy(takeoverHome = !it.takeoverHome) } })
        Toggle(s.redirectYouTube, "Open my YouTube app instead of YouTube", "Includes the YouTube button on the remote", { store.update { it.copy(redirectYouTube = !it.redirectYouTube) } })
        val current = vm.app.apps.preferredYouTube(s)
        val label = alts.firstOrNull { it.packageName == current }?.label ?: if (alts.isEmpty()) "None installed" else "Pick automatically"
        ValueRow("Preferred YouTube app", label, sub = "TizenTube and SmartTube are detected automatically", onClick = {
            if (alts.isNotEmpty()) {
                val pkgs = alts.map { it.packageName }
                val i = pkgs.indexOf(s.youtubePackage)
                store.update { it.copy(youtubePackage = pkgs[(i + 1) % pkgs.size]) }
            }
        })
    }
}

@Composable
private fun WeatherSection(vm: MarqueeVm, s: Settings, nav: Nav) {
    val store = vm.app.store
    var results by remember { mutableStateOf<List<app.marquee.tv.weather.Place>>(emptyList()) }
    val scope = rememberCoroutineScope()
    H("Weather", "Free forecasts from Open-Meteo. Set your city on the phone setup page, or pick a nearby major city here.")
    Group {
        ValueRow("City", s.cityName.ifBlank { "Not set" }, onClick = {})
        val weatherNow by vm.weather.collectAsState()
        ValueRow(
            "Home background", if (s.background == "PHOTO") "Photo of my area" else "Live weather sky",
            sub = if (s.background == "PHOTO") (if (s.unsplashKey.isBlank()) "Needs a free Unsplash key on the phone setup page" else "A real photo of where you live, changing daily")
                else "One calm sky behind everything, following your real weather, the sun and the moon",
            onClick = {
                vm.app.store.update { it.copy(background = if (it.background == "PHOTO") "SKY" else "PHOTO") }
                vm.refreshPhoto()
            },
        )
        ValueRow(
            "Preview tonight's sunset", "Play",
            sub = "A 45-second time-lapse of the sky from the real sun position for your location",
            onClick = { nav.go(app.marquee.tv.Screen.SunsetPreview) },
        )
        Toggle(s.ambientScene, "Use the live sky in Ambient", "Instead of a slideshow of Plex artwork", { vm.app.store.update { it.copy(ambientScene = !it.ambientScene) } })
        Toggle(s.useFahrenheit, "Use °F", "", { store.update { it.copy(useFahrenheit = !it.useFahrenheit) }; vm.refreshWeather() })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("New York", "Los Angeles", "Chicago", "London", "Toronto", "Sydney").forEach { c ->
                PillButton(c) { scope.launch { results = withContext(Dispatchers.IO) { runCatching { app.marquee.tv.weather.WeatherRepo.searchPlaces(c) }.getOrDefault(emptyList()) } } }
            }
        }
        results.take(4).forEach { p ->
            ValueRow(p.name, p.region, onClick = { store.update { it.copy(cityName = p.name, stateName = p.state, latitude = p.lat, longitude = p.lon) }; results = emptyList(); vm.refreshWeather() })
        }
    }
}

@Composable
private fun PermissionsSection(vm: MarqueeVm) {
    var usage by remember { mutableStateOf(vm.app.stats.hasPermission()) }
    LaunchedEffect(Unit) { while (true) { usage = vm.app.stats.hasPermission(); delay(3000) } }
    val pkg = LocalContext.current.packageName
    H("Permissions & privacy", "No ads, no tracking. Marquee keeps your data on this TV and only talks to the services you connect.")
    Group {
        ValueRow("Usage access (watch-time stats)", if (usage) "Allowed" else "Not allowed", sub = if (usage) "" else "From a computer: adb shell appops set $pkg GET_USAGE_STATS allow", onClick = {})
        ValueRow("Accessibility service", if (MarqueeAccessibilityService.running) "Running" else "Off",
            sub = if (MarqueeAccessibilityService.running) "" else "adb shell settings put secure enabled_accessibility_services $pkg/.system.MarqueeAccessibilityService", onClick = {})
        Text("Marquee never reads what's on screen. The accessibility service only notices which app opened, so it can step in for the stock home screen and YouTube.", style = T.small, modifier = Modifier.padding(top = 6.dp))
        Text("Phone setup page: ${SetupServer.localIp()?.let { "http://$it:${SetupServer.PORT}" } ?: "offline"} (protected by a private link).", style = T.small)
    }
}


/** Green checks: each key and account is tested live, not just "filled in". */
@Composable
fun HealthPanel(vm: MarqueeVm) {
    val checks by vm.health.collectAsState()
    val settings by vm.settings.collectAsState()
    LaunchedEffect(settings.tmdbKey, settings.omdbKey, settings.watchmodeKey, settings.routesKey, settings.plexServerUri, settings.calendarUrls, settings.youtubeRefreshToken, settings.cityName) { vm.checkHealth() }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connection status", style = T.sans(14.sp, FontWeight.ExtraBold))
        if (checks.isEmpty()) Text("Checking…", style = T.small)
        checks.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (bg, mark) = when (c.health) {
                    app.marquee.tv.system.Health.OK -> Color(0xFF3FAE62) to "✓"
                    app.marquee.tv.system.Health.FAILED -> Color(0xFFD9534F) to "✕"
                    else -> Color(0x33FFFFFF) to "–"
                }
                Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Text(mark, style = T.sans(12.sp, FontWeight.ExtraBold, Color.White))
                }
                Text(c.label, style = T.bodyStrong, modifier = Modifier.width(110.dp))
                Text(c.detail, style = T.sans(11.sp, color = if (c.health == app.marquee.tv.system.Health.FAILED) Color(0xFFFFA19C) else C.TextDim))
            }
        }
    }
}


@Composable
private fun SystemLink(label: String, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth(), onClick = onClick) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .then(if (f) Modifier.border(1.5.dp, C.Amber, RoundedCornerShape(9.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = T.sans(11.sp, FontWeight.SemiBold, if (f) C.Text else C.Cool), modifier = Modifier.weight(1f))
            Text("↗", style = T.sans(11.sp, FontWeight.Bold, C.TextMute))
        }
    }
}
