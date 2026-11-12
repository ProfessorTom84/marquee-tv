package app.marquee.tv

import android.app.Application
import app.marquee.tv.calendar.CalendarRepo
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.plex.PlexRepo
import app.marquee.tv.recs.Recommender
import app.marquee.tv.setup.SetupServer
import app.marquee.tv.stats.WatchStats
import app.marquee.tv.system.Apps
import app.marquee.tv.tmdb.TmdbRepo
import app.marquee.tv.traffic.TrafficRepo
import app.marquee.tv.youtube.YouTubeRepo
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.flow.MutableStateFlow

class MarqueeApp : Application(), ImageLoaderFactory {
    lateinit var store: SettingsStore; private set
    lateinit var plex: PlexRepo; private set
    lateinit var tmdb: TmdbRepo; private set
    lateinit var youtube: YouTubeRepo; private set
    lateinit var calendar: CalendarRepo; private set
    lateinit var traffic: TrafficRepo; private set
    lateinit var stats: WatchStats; private set
    lateinit var apps: Apps; private set
    lateinit var recommender: Recommender; private set
    lateinit var news: app.marquee.tv.news.NewsRepo; private set
    lateinit var alerts: app.marquee.tv.system.AlertEngine; private set
    var setupServer: SetupServer? = null; private set

    /** Bumped whenever settings change from the phone page so screens reload. */
    val refreshTick = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        app.marquee.tv.net.Http.init(cacheDir.resolve("http"))
        store = SettingsStore(this)
        app.marquee.tv.ui.components.SoundFx.init(this)
        app.marquee.tv.ui.components.SoundFx.enabled = store.value.uiSounds
        plex = PlexRepo(store)
        tmdb = TmdbRepo(store)
        youtube = YouTubeRepo(store)
        calendar = CalendarRepo(store)
        traffic = TrafficRepo(store)
        stats = WatchStats(this)
        apps = Apps(this).also { it.titleLinks = app.marquee.tv.links.TitleLinks(store) }
        recommender = Recommender(store, plex, tmdb, youtube)
        news = app.marquee.tv.news.NewsRepo(store)
        alerts = app.marquee.tv.system.AlertEngine(store, calendar, traffic)
        app.marquee.tv.system.SelfHeal.ensureAccessibility(this)
        app.marquee.tv.system.Updater.start(this)
        // The phone setup page is always reachable (also lets another Marquee TV copy this one's setup).
        ensureSetupServer()
        // First run: assume the streaming apps installed on this TV are the ones you pay for.
        if (store.value.services.isEmpty()) {
            val found = apps.installedProviderIds()
            if (found.isNotEmpty()) store.update { it.copy(services = found) }
        }
    }

    fun ensureSetupServer(): SetupServer? {
        setupServer?.let { return it }
        return runCatching {
            SetupServer(
                store,
                youtubeApps = { apps.youtubeAlternatives().map { it.packageName to it.label } + (apps.installed().filter { it.packageName == "com.google.android.youtube.tv" }.map { it.packageName to it.label }) },
                onSaved = { refreshTick.value++ },
                health = { kotlinx.coroutines.runBlocking { app.marquee.tv.system.HealthChecks.run(this@MarqueeApp) } },
            ).also {
                it.start(5000, true); setupServer = it
                // Visible only over ADB: lets a setup script find this TV's phone page.
                android.util.Log.i("MarqueeSetup", "Setup page: ${it.url()}")
            }
        }.getOrNull()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(350)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: MarqueeApp; private set
    }
}
