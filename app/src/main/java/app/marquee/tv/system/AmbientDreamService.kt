package app.marquee.tv.system

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.marquee.tv.MarqueeApp
import app.marquee.tv.ui.home.AmbientContent
import app.marquee.tv.ui.theme.MarqueeTheme
import app.marquee.tv.weather.Weather
import app.marquee.tv.weather.WeatherRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marquee's Ambient view as the system screensaver (Settings → System → Ambient mode / Screen saver). */
class AmbientDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var art by mutableStateOf<List<String>>(emptyList())
    private var weather by mutableStateOf<Weather?>(null)
    private var up by mutableStateOf<UpNext?>(null)

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AmbientDreamService)
            setViewTreeSavedStateRegistryOwner(this@AmbientDreamService)
            setViewTreeViewModelStoreOwner(this@AmbientDreamService)
            setContent { MarqueeTheme { AmbientContent(art, weather, up, interactive = false) } }
        }
        setContentView(view)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        load()
    }

    private fun load() = scope.launch {
        val app = application as MarqueeApp
        val s = app.store.value
        withContext(Dispatchers.IO) {
            art = runCatching { (app.plex.recentlyAdded() + app.plex.unwatchedTopRated(40)).mapNotNull { it.backdropUrl }.distinct().shuffled().take(30) }.getOrDefault(emptyList())
            if (s.hasLocation) weather = runCatching { WeatherRepo.forecast(s.latitude, s.longitude, s.useFahrenheit) }.getOrNull()
            up = runCatching { app.alerts.upNext() }.getOrNull()
        }
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    override fun onDreamingStopped() { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); super.onDreamingStopped() }
    override fun onDetachedFromWindow() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDetachedFromWindow()
    }
    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel(); store.clear()
        super.onDestroy()
    }
}
