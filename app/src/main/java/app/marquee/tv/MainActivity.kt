package app.marquee.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marquee.tv.data.Title
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.detail.DetailScreen
import app.marquee.tv.ui.home.HomeScreen
import app.marquee.tv.ui.screens.AllAppsScreen
import app.marquee.tv.ui.screens.SearchScreen
import app.marquee.tv.ui.screens.TonightScreen
import app.marquee.tv.ui.settings.SettingsScreen
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.MarqueeTheme

sealed interface Screen {
    data object Home : Screen
    data class Detail(val title: Title) : Screen
    data object PickTogether : Screen
    data object QuickPicks : Screen
    data object Search : Screen
    data object Apps : Screen
    data class Settings(val section: String = "style") : Screen
    data object SunsetPreview : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_AMBIENT = "marquee.ambient" }
    private val vm: MarqueeVm by viewModels()
    private var nav: Nav? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as MarqueeApp).ensureSetupServer()
        // Compose paints every pixel itself; skipping the window background saves a full-screen fill per frame.
        window.setBackgroundDrawable(null)
        setContent {
            MarqueeTheme {
                val n = remember { Nav() }.also { nav = it }
                Root(vm, n)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already in Marquee returns to the top of the home screen.
        nav?.home()
        vm.onInput()
        if (intent.getBooleanExtra(EXTRA_AMBIENT, false)) vm.ambientActive.value = true
        else vm.homeTaps.tryEmit(Unit)
    }

    private var resumedBefore = false

    override fun onResume() {
        super.onResume()
        vm.onInput()
        if (resumedBefore) vm.onReturn() else resumedBefore = true
        vm.refreshStats()
        vm.refreshApps()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val wasAmbient = vm.ambientActive.value
        vm.onInput()
        if (event.action == KeyEvent.ACTION_DOWN) {
            vm.onKey()
            app.marquee.tv.ui.components.SoundFx.keyPressed()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> app.marquee.tv.ui.components.Browsing.onNavKey()
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && !wasAmbient) app.marquee.tv.ui.components.SoundFx.back()
        }
        // Any button wakes Ambient without also acting on whatever is underneath.
        if (wasAmbient) return true
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun Root(vm: MarqueeVm, nav: Nav) {
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    // Back at the top of the home screen starts the screensaver, like Google TV. (Screens that are scrolled
    // down register their own Back first, which returns them to the top.)
    BackHandler(enabled = nav.stack.size == 1) { vm.backOnHome() }
    Box(Modifier.fillMaxSize().background(C.Ink)) {
        androidx.compose.runtime.CompositionLocalProvider(app.marquee.tv.ui.components.LocalCardPreviews provides null) {
        // Going deeper eases the new screen in from slightly larger; going back eases in from slightly smaller.
        val depth = nav.stack.size
        var prevDepth by remember { androidx.compose.runtime.mutableIntStateOf(depth) }
        val forward = depth >= prevDepth
        androidx.compose.runtime.SideEffect { prevDepth = depth }
        LaunchedEffect(nav.top) { (nav.top as? Screen.Detail)?.let { app.marquee.tv.ui.components.CardZoom.opened(it.title.id) } }
        AnimatedContent(nav.top, transitionSpec = {
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            if (forward) (fadeIn(tween(300, 40)) + androidx.compose.animation.scaleIn(tween(380, easing = ease), initialScale = 1.035f)) togetherWith
                (fadeOut(tween(200)) + androidx.compose.animation.scaleOut(tween(300, easing = ease), targetScale = 0.975f))
            else (fadeIn(tween(280, 40)) + androidx.compose.animation.scaleIn(tween(360, easing = ease), initialScale = 0.975f)) togetherWith
                (fadeOut(tween(200)) + androidx.compose.animation.scaleOut(tween(300, easing = ease), targetScale = 1.03f))
        }, label = "nav") { s ->
            when (s) {
                Screen.Home -> HomeScreen(vm, nav)
                is Screen.Detail -> DetailScreen(vm, nav, s.title)
                Screen.PickTogether -> app.marquee.tv.ui.screens.PickTogetherScreen(vm, nav)
                Screen.QuickPicks -> TonightScreen(vm, nav)
                Screen.Search -> SearchScreen(vm, nav)
                Screen.Apps -> AllAppsScreen(vm, nav)
                is Screen.Settings -> SettingsScreen(vm, nav, s.section)
                Screen.SunsetPreview -> app.marquee.tv.ui.screens.SunsetPreview(vm, nav)
            }
        }
        }
        app.marquee.tv.ui.components.CardZoomOverlay()
        PerfCheckOverlay(nav)
    }
}

/** While the performance check runs: a small countdown in the corner; when it's done, the results page opens. */
@Composable
private fun PerfCheckOverlay(nav: Nav) {
    val phase by app.marquee.tv.system.PerfCheck.phase.collectAsState()
    when (val p = phase) {
        is app.marquee.tv.system.PerfCheck.Phase.Running -> {
            var left by remember { androidx.compose.runtime.mutableIntStateOf(app.marquee.tv.system.PerfCheck.SECONDS) }
            LaunchedEffect(p.endsAt) {
                while (true) { left = ((p.endsAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0); kotlinx.coroutines.delay(500) }
            }
            Box(Modifier.fillMaxSize()) {
                androidx.tv.material3.Text(
                    "Performance check · $left s · keep scrolling up and down",
                    style = app.marquee.tv.ui.theme.T.sans(11.sp, androidx.compose.ui.text.font.FontWeight.Bold, C.Ink),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 10.dp)
                        .background(C.Amber, androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        is app.marquee.tv.system.PerfCheck.Phase.Done -> LaunchedEffect(p) {
            if ((nav.top as? Screen.Settings)?.section != "perf") nav.go(Screen.Settings("perf"))
        }
        else -> Unit
    }
}
