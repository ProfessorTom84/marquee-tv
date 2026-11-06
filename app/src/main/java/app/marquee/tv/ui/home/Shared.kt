package app.marquee.tv.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import app.marquee.tv.ui.components.glass
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.marquee.tv.calendar.CalEvent
import app.marquee.tv.data.Providers
import app.marquee.tv.data.Settings
import app.marquee.tv.data.Title
import app.marquee.tv.system.MarqueeAccessibilityService
import app.marquee.tv.system.TimeFmt
import app.marquee.tv.system.TvApp
import app.marquee.tv.system.UpNext
import app.marquee.tv.ui.components.Focusable
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import app.marquee.tv.weather.Sky
import app.marquee.tv.weather.Weather
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = LocalDateTime.now() } }
    return now
}

fun clockText(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("h:mm"))
fun dateText(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
fun greeting(t: LocalDateTime, name: String): String {
    val part = when (t.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    return if (name.isBlank() || name == "Me") part else "$part, $name"
}

@Composable
fun SkyIcon(sky: Sky?, day: Boolean, size: Int = 22) {
    androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val c = Color(0xFFFFE2A8)
        when (sky) {
            Sky.CLEAR, null -> if (day) {
                drawCircle(c, w * 0.22f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val s = androidx.compose.ui.geometry.Offset(center.x + (w * 0.32f * Math.cos(a)).toFloat(), center.y + (w * 0.32f * Math.sin(a)).toFloat())
                    val e = androidx.compose.ui.geometry.Offset(center.x + (w * 0.45f * Math.cos(a)).toFloat(), center.y + (w * 0.45f * Math.sin(a)).toFloat())
                    drawLine(c, s, e, strokeWidth = w * 0.07f)
                }
            } else {
                drawCircle(c, w * 0.38f)
                drawCircle(Color(0xFF0B1024), w * 0.32f, androidx.compose.ui.geometry.Offset(center.x + w * 0.2f, center.y - w * 0.14f))
            }
            Sky.SNOW -> for (i in 0 until 3) drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.1f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.9f), w * 0.07f)
            else -> {
                drawCircle(Color(0xFFDDE3EE), w * 0.2f, androidx.compose.ui.geometry.Offset(w * 0.38f, w * 0.48f))
                drawCircle(Color(0xFFDDE3EE), w * 0.26f, androidx.compose.ui.geometry.Offset(w * 0.6f, w * 0.42f))
                drawRect(Color(0xFFDDE3EE), androidx.compose.ui.geometry.Offset(w * 0.2f, w * 0.48f), androidx.compose.ui.geometry.Size(w * 0.65f, w * 0.2f))
                if (sky == Sky.RAIN || sky == Sky.STORM) for (i in 0..2) drawLine(Color(0xFF8EA2E8), androidx.compose.ui.geometry.Offset(w * (0.32f + i * 0.17f), w * 0.76f), androidx.compose.ui.geometry.Offset(w * (0.28f + i * 0.17f), w * 0.95f), w * 0.06f)
            }
        }
    }
}

/** Rule-based "concierge" line combining the calendar, the weather and the title's length. No AI. */
fun conciergeLine(title: Title?, up: UpNext?, events: List<CalEvent>, weather: Weather?, now: ZonedDateTime = ZonedDateTime.now()): String? {
    title ?: return null
    val runtime = title.runtimeMin
    val rt = runtime?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
    val weatherLead = when (weather?.sky) {
        Sky.RAIN, Sky.STORM -> "Rain outside. "
        Sky.SNOW -> "Snowing outside. "
        else -> ""
    }
    if (up != null) {
        val leaveOrStart = up.leaveBy ?: up.event.start
        val freeMin = Duration.between(now, leaveOrStart).toMinutes()
        val what = if (up.leaveBy != null) "you leave for ${up.event.title} at ${TimeFmt.full(up.leaveBy)}" else "${up.event.title} starts at ${TimeFmt.full(up.event.start)}"
        if (freeMin in 1..240 && runtime != null) {
            return if (runtime <= freeMin) "$weatherLead$rt — you'll finish before $what."
            else "$weatherLead${what.replaceFirstChar { it.uppercase() }}. Save this one for after, or start an episode."
        }
    }
    val next = events.firstOrNull { !it.allDay && it.start.isAfter(now) }
    if (next != null && now.hour >= 17 && next.start.toLocalDate() != now.toLocalDate()) {
        return "${weatherLead}Nothing on your calendar until ${TimeFmt.full(next.start)} tomorrow${rt?.let { " — plenty of time for all $it" } ?: ""}."
    }
    return title.reason?.let { "$weatherLead$it." } ?: title.overview.substringBefore(". ").takeIf { it.isNotBlank() }?.let { "$it." }
}

@Composable
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (f) C.Text else Color(0x1FFFFFFF))
                    .border(if (f) 2.dp else 1.dp, if (f) C.Amber else C.Stroke, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = label, tint = if (f) Color(0xFF120D08) else C.Text, modifier = Modifier.size(17.dp)) }
            // The name appears under the icon when it's focused, so the two settings buttons are never confused.
            if (f) Text(
                label, style = T.sans(9.sp, FontWeight.Bold), maxLines = 1,
                modifier = Modifier.offset(y = 40.dp).wrapContentSize(unbounded = true)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC0B0E1A)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
fun ProfileButton(s: Settings, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF3A2A20)).border(if (f) 3.dp else 1.5.dp, C.Amber, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(s.profile.name.take(1).uppercase(), style = T.sans(13.sp, FontWeight.Bold)) }
    }
}

@Composable
fun TopActions(s: Settings, onSearch: () -> Unit, onSettings: () -> Unit, onTvSettings: () -> Unit, onProfile: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(Icons.Filled.Search, "Search", onSearch)
        IconCircle(app.marquee.tv.ui.components.MarqueeIcons.Tune, "Marquee settings", onSettings)
        IconCircle(Icons.Filled.Settings, "Google TV settings", onTvSettings)
        ProfileButton(s, onProfile)
    }
}

/** Apps row: Plex and your services first, the preferred YouTube app in place of YouTube. */
fun orderedApps(all: List<TvApp>, s: Settings, preferredYouTube: String?, plexPlayer: String = Providers.PLEX_PACKAGE): List<TvApp> {
    val priority = listOf(plexPlayer, Providers.PLEX_PACKAGE) + s.services.flatMap { Providers.get(it)?.packages.orEmpty() } + listOfNotNull(preferredYouTube)
    val hidden = buildSet {
        if (preferredYouTube != null && preferredYouTube != Providers.YOUTUBE_PACKAGE) add(Providers.YOUTUBE_PACKAGE)
        if (s.redirectPlex && plexPlayer != Providers.PLEX_PACKAGE) add(Providers.PLEX_PACKAGE)
    }
    val visible = all.filter { it.packageName !in hidden && it.packageName !in s.hiddenApps }
    val first = priority.mapNotNull { p -> visible.firstOrNull { it.packageName == p } }.distinctBy { it.packageName }
    val auto = first + visible.filter { it !in first }
    // Your own arrangement wins; apps you haven't placed (e.g. newly installed) follow in the usual order.
    val placed = s.appOrder.mapNotNull { p -> auto.firstOrNull { it.packageName == p } }
    return placed + auto.filter { it !in placed }
}

@Composable
fun AppTile(app: TvApp, width: Int = 104, moving: Boolean = false, onLongClick: (() -> Unit)? = null, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit) {
    val bmp = remember(app.packageName) { runCatching { (app.banner ?: app.icon)?.toBitmap(320, 180)?.asImageBitmap() }.getOrNull() }
    // Hold OK (about half a second) for the long-press menu; a normal press still opens the app.
    var downAt by remember { mutableStateOf(0L) }
    var longFired by remember { mutableStateOf(false) }
    val longPress = Modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == android.view.KeyEvent.KEYCODE_ENTER
        if (!ok || onLongClick == null) return@onPreviewKeyEvent false
        when (k.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (k.repeatCount == 0) { downAt = k.eventTime; longFired = false; false }
                else if (!longFired && k.eventTime - downAt > 450) { longFired = true; onLongClick(); true } else longFired
            }
            android.view.KeyEvent.ACTION_UP -> if (longFired) { longFired = false; true } else false
            else -> false
        }
    }
    Focusable(longPress, onClick = onClick, onFocus = onFocus) { f ->
        val scale = if (moving) 1.12f else if (f) 1.08f else 1f
        Box(
            Modifier.width(width.dp).height((width * 0.5625f).dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationY = if (moving) -6f * density else 0f }
                .then(if (f || moving) Modifier.shadow(20.dp, RoundedCornerShape(10.dp), ambientColor = C.Amber, spotColor = C.Amber) else Modifier)
                .clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF))
                .border(if (f || moving) 2.dp else 1.dp, if (moving) C.Amber else if (f) Color(0xF2FFF0DC) else C.Stroke, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null && app.banner != null) Image(bmp, app.label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) Image(bmp, null, Modifier.size(26.dp))
                Text(app.label, style = T.sans(10.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            }
            if (moving) Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("◀", style = T.sans(13.sp, FontWeight.ExtraBold, C.Amber)); Text("▶", style = T.sans(13.sp, FontWeight.ExtraBold, C.Amber))
            }
        }
    }
}

@Composable
private fun RowTile(label: String, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(
            Modifier.width(104.dp).height(58.dp).clip(RoundedCornerShape(10.dp)).background(if (f) C.Text else Color(0x1FFFFFFF))
                .border(1.dp, C.Stroke, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = T.sans(11.sp, FontWeight.Bold, if (f) Color(0xFF120D08) else C.Text)) }
    }
}

/**
 * The apps ribbon. Hold OK on an app to move it or hide it: in Move, Left/Right slides it along the row
 * and OK (or Back) puts it down. Your order is remembered; hidden apps stay in All apps.
 */
@Composable
fun AppsRow(apps: List<TvApp>, onOpen: (TvApp) -> Unit, onAll: () -> Unit, vm: app.marquee.tv.ui.MarqueeVm? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shown = apps.take(12)
    var menuFor by remember { mutableStateOf<TvApp?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var appFocused by remember { mutableStateOf(false) }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val packages = shown.map { it.packageName }
    androidx.activity.compose.BackHandler(enabled = moving != null) { moving = null }

    Column {
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            modifier = Modifier.onPreviewKeyEvent { e ->
                val pkg = moving ?: return@onPreviewKeyEvent false
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val d = if (e.key == Key.DirectionLeft) -1 else 1
                        vm?.moveApp(packages, pkg, d)
                        val idx = (packages.indexOf(pkg) + d).coerceIn(0, packages.lastIndex)
                        scope.launch { rowState.animateScrollToItem((idx - 1).coerceAtLeast(0)) }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> { moving = null; e.key == Key.DirectionCenter || e.key == Key.Enter }
                    else -> false
                }
            },
        ) {
            items(shown.size, key = { shown[it].packageName }) { i ->
                val a = shown[i]
                AppTile(a, moving = moving == a.packageName, onLongClick = if (vm != null) ({ menuFor = a }) else null, onFocus = { appFocused = it }) {
                    if (moving == null) onOpen(a)
                }
            }
            item(key = "all") { RowTile("All apps", onAll) }
            item(key = "tvsettings") { RowTile("TV settings") { (ctx.applicationContext as app.marquee.tv.MarqueeApp).apps.openSystemSettings() } }
        }
        val hint = when {
            moving != null -> "Moving ${shown.firstOrNull { it.packageName == moving }?.label ?: ""} · ◀ ▶ to move · OK when done"
            appFocused && vm != null -> "Hold OK to move or hide an app"
            else -> ""
        }
        Text(hint, style = T.sans(9.sp, FontWeight.SemiBold, if (moving != null) C.AmberSoft else C.TextMute), modifier = Modifier.padding(start = 48.dp).height(14.dp))
    }

    menuFor?.let { a ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { menuFor = null }) {
            val first = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            // The menu opens while OK is still held: swallow that press's repeats and its release, so letting go
            // doesn't "click" the first option and close the menu. The next press works normally.
            var armed by remember { mutableStateOf(false) }
            Column(
                Modifier.onPreviewKeyEvent { e ->
                    if (armed) return@onPreviewKeyEvent false
                    val k = e.nativeKeyEvent
                    val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    when {
                        ok && k.action == android.view.KeyEvent.ACTION_UP -> { armed = true; true }
                        ok && k.repeatCount > 0 -> true
                        k.action == android.view.KeyEvent.ACTION_DOWN && !ok -> { armed = true; false }
                        ok -> true
                        else -> false
                    }
                }.width(320.dp).glass(RoundedCornerShape(18.dp), C.GlassWarm).background(Color(0xE60B0E1A), RoundedCornerShape(18.dp)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(a.label, style = T.sans(16.sp, FontWeight.ExtraBold))
                Text("Arrange your apps ribbon", style = T.small)
                app.marquee.tv.ui.components.PillButton("Move", primary = true, height = 38.dp, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                    moving = a.packageName; menuFor = null
                }
                app.marquee.tv.ui.components.PillButton("Hide from home", height = 38.dp, modifier = Modifier.fillMaxWidth()) {
                    vm?.setAppHidden(a.packageName, true); menuFor = null
                }
                app.marquee.tv.ui.components.PillButton("Cancel", height = 38.dp, modifier = Modifier.fillMaxWidth()) { menuFor = null }
                Text("Hidden apps stay in All apps. Bring them back in Settings → Apps on home.", style = T.small)
            }
        }
    }
}

fun openApp(vm: app.marquee.tv.ui.MarqueeVm, a: TvApp) {
    if (a.packageName == Providers.YOUTUBE_PACKAGE) MarqueeAccessibilityService.allowYouTubeUntil = android.os.SystemClock.elapsedRealtime() + 10_000
    if (a.packageName == Providers.PLEX_PACKAGE) MarqueeAccessibilityService.allowPlexUntil = android.os.SystemClock.elapsedRealtime() + 10_000
    vm.app.apps.launch(a.packageName)
}
