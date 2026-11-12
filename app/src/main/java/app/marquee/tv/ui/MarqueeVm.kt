package app.marquee.tv.ui

import app.marquee.tv.together.Together
import app.marquee.tv.system.MarqueeAccessibilityService
import app.marquee.tv.data.Availability
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Source
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marquee.tv.MarqueeApp
import app.marquee.tv.calendar.CalEvent
import app.marquee.tv.data.HomeStyle
import app.marquee.tv.data.Title
import app.marquee.tv.recs.DetailData
import app.marquee.tv.recs.HomeData
import app.marquee.tv.net.Http
import app.marquee.tv.photos.AreaPhoto
import app.marquee.tv.photos.AreaPhotos
import app.marquee.tv.ui.components.CardPreview
import app.marquee.tv.system.HealthChecks
import app.marquee.tv.system.Check
import app.marquee.tv.recs.Recommender
import app.marquee.tv.recs.FeedLayout
import app.marquee.tv.news.Headline
import app.marquee.tv.news.NewsSource
import app.marquee.tv.system.TvApp
import app.marquee.tv.system.UpNext
import app.marquee.tv.weather.Sky
import app.marquee.tv.weather.Weather
import app.marquee.tv.weather.WeatherRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

class MarqueeVm : ViewModel() {
    val app = MarqueeApp.instance
    val settings = app.store.state

    val home = MutableStateFlow<HomeData?>(null)
    val loading = MutableStateFlow(true)
    val weather = MutableStateFlow<Weather?>(null)
    val headlines = MutableStateFlow<List<Headline>>(emptyList())
    val events = MutableStateFlow<List<CalEvent>>(emptyList())
    val upNext = MutableStateFlow<UpNext?>(null)
    val apps = MutableStateFlow<List<TvApp>>(emptyList())
    val areaPhoto = MutableStateFlow<AreaPhoto?>(null)
    val ambientActive = MutableStateFlow(false)
    val lastInput = MutableStateFlow(System.currentTimeMillis())

    private var homeJob: Job? = null

    // v3: the merged "From your Plex" row, per-service rows and the feed balance.
    private val cacheFile = java.io.File(app.filesDir, "home-v3.json")

    /** Drops anything not on your Plex, your chosen services or YouTube, and any duplicate keys. */
    private fun sanitize(h: HomeData, services: Set<Int>): HomeData {
        fun ok(a: Availability) = a.kind == AvailKind.PLEX || a.providerId in services
        fun allowed(t: Title) = t.source == Source.YOUTUBE || t.availability.any(::ok)
        val rows = h.rows.filter { it.key != "elsewhere" }
            .map { r -> r.copy(items = r.items.filter(::allowed).map { it.copy(availability = it.availability.filter(::ok)) }.distinctBy { it.id }) }
            .filter { it.items.size >= 3 }.distinctBy { it.key }
        return h.copy(featured = h.featured?.takeIf(::allowed), rows = rows)
    }

    private var lastServices = settings.value.services
    private var lastProfile = settings.value.activeProfile
    private var lastBalance = settings.value.feedBalance
    private var lastRowOrder = settings.value.rowOrder
    private var lastNews = settings.value.newsSource
    private var lastCity = settings.value.cityName
    private var lastHiddenRows = settings.value.hiddenRows

    // (Declared before init: init reads them.)
    // ---- Freshness: every time you come back to the TV's home screen, it should feel new ----
    private var homeBase: HomeData? = null
    private var lastHomeLoad = 0L
    private var visits = 0
    @Volatile private var lastKeyAt = 0L

    init {
        // Show the last home screen instantly, then refresh in the background.
        listOf("home.json", "home-v2.json").forEach { runCatching { java.io.File(app.filesDir, it).delete() } }
        runCatching {
            homeBase = sanitize(Http.json.decodeFromString(HomeData.serializer(), cacheFile.readText()), settings.value.services)
            home.value = freshen(homeBase!!, visits)
        }
        refreshAll()
        viewModelScope.launch { app.refreshTick.drop(1).collect { refreshAll(); checkHealth() } }
        viewModelScope.launch {
            settings.drop(1).collect { s ->
                // Removing a service takes effect at once; the full refresh follows.
                if (s.services != lastServices) homeBase?.let { homeBase = sanitize(it, s.services); home.value = freshen(homeBase!!, visits) }
                // A new feed balance re-arranges the rows at once; the refresh then re-mixes "For you" and the rows.
                if (s.feedBalance != lastBalance) homeBase?.let { home.value = freshen(it, visits) }
                // Reordering or hiding rows only re-arranges what's already loaded.
                if (s.rowOrder != lastRowOrder || s.hiddenRows != lastHiddenRows) {
                    lastRowOrder = s.rowOrder; lastHiddenRows = s.hiddenRows
                    homeBase?.let { home.value = freshen(it, visits) }
                }
                val profileChanged = s.activeProfile != lastProfile // read before refreshHome() records it
                if (s.services != lastServices || profileChanged || s.feedBalance != lastBalance) refreshHome()
                if (s.newsSource != lastNews || profileChanged || s.cityName != lastCity) { lastNews = s.newsSource; lastCity = s.cityName; refreshNews() }
            }
        }
        // Light refresh loop: clock-driven data every minute, heavy data every 20 minutes.
        viewModelScope.launch {
            var n = 0
            while (true) {
                delay(60_000)
                n++
                refreshUpNext()
                if (n % 15 == 0) refreshWeather()
                if (n % 20 == 0) refreshNews()
                if (n % 180 == 0) refreshPhoto()
                if (n % 20 == 0) refreshHome()
                checkIdle()
            }
        }
    }


    /** Called on each D-pad press, so a background refresh never rearranges rows under you. */
    fun onKey() { lastKeyAt = System.currentTimeMillis() }

    /**
     * You've come back to Marquee (Home, or finishing a show). Rotate what leads each row right away, and if
     * the picks are more than 15 minutes old, fetch new ones; they swap in as long as you haven't started browsing.
     */
    fun onReturn() {
        visits++
        (homeBase ?: home.value)?.let { base -> homeBase = base; home.value = freshen(base, visits) }
        if (System.currentTimeMillis() - lastHomeLoad > 15 * 60_000L) refreshHome()
    }

    /**
     * Same picks, new arrangement: which two or three services get a row, which "Because you watched…" and
     * mood rows show, and where "For you" and "From your Plex" start all move on each visit (and each hour),
     * so the feed changes through the day. Plex never gets more than two rows.
     */
    private fun freshen(h: HomeData, visit: Int): HomeData {
        val turn = visit + (System.currentTimeMillis() / 3_600_000L).toInt()
        val s = settings.value
        return h.copy(rows = FeedLayout.arrange(
            h.rows, s.feedBalance, turn, visit,
            order = FeedLayout.RowGroup.parse(s.rowOrder), hidden = FeedLayout.RowGroup.parse(s.hiddenRows).toSet(),
        ))
    }

    fun refreshAll() {
        refreshHome(); refreshWeather(); refreshNews(); refreshCalendar(); refreshApps(); refreshPhoto()
    }

    fun refreshHome() {
        lastServices = settings.value.services; lastProfile = settings.value.activeProfile; lastBalance = settings.value.feedBalance
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            loading.value = home.value == null
            val data = withContext(Dispatchers.IO) { runCatching { app.recommender.home(weather.value?.sky) }.getOrNull() }
            if (data != null && (data.rows.isNotEmpty() || home.value == null)) {
                // Don't reshuffle rows under someone who is browsing (pressing keys); apply when they pause.
                while (home.value != null && System.currentTimeMillis() - lastKeyAt < 45_000 && !ambientActive.value) delay(3_000)
                homeBase = data
                lastHomeLoad = System.currentTimeMillis()
                home.value = freshen(data, visits)
                withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(Http.json.encodeToString(HomeData.serializer(), data)) } }
            }
            loading.value = false
        }
    }

    fun refreshWeather() = viewModelScope.launch {
        val s = settings.value
        if (!s.hasLocation) return@launch
        val before = weather.value?.sky
        weather.value = withContext(Dispatchers.IO) { runCatching { WeatherRepo.forecast(s.latitude, s.longitude, s.useFahrenheit) }.getOrNull() } ?: weather.value
        // Rain or snow starting (or the first forecast arriving) can bring in a weather ribbon.
        val after = weather.value?.sky
        val wet = setOf(Sky.RAIN, Sky.STORM, Sky.SNOW)
        if ((before in wet) != (after in wet)) refreshHome()
    }

    fun refreshPhoto() = viewModelScope.launch {
        areaPhoto.value = withContext(Dispatchers.IO) { runCatching { AreaPhotos.forToday(settings.value, weather.value?.elevationM) }.getOrNull() } ?: areaPhoto.value
    }

    /** The Dashboard's headlines. Never for kids' profiles. */
    fun refreshNews() = viewModelScope.launch {
        val s = settings.value
        headlines.value = if (s.profile.kidSafe || s.newsSource == NewsSource.OFF) emptyList()
            else withContext(Dispatchers.IO) { runCatching { app.news.headlines() }.getOrNull() } ?: headlines.value
    }

    fun refreshCalendar() = viewModelScope.launch {
        events.value = withContext(Dispatchers.IO) { runCatching { app.alerts.upcoming() }.getOrDefault(emptyList()) }
        refreshUpNext()
    }

    private fun refreshUpNext() = viewModelScope.launch {
        upNext.value = withContext(Dispatchers.IO) { runCatching { app.alerts.upNext(events.value) }.getOrNull() }
    }

    fun refreshApps() = viewModelScope.launch { apps.value = withContext(Dispatchers.IO) { app.apps.installed() } }

    suspend fun detail(t: Title): DetailData = withContext(Dispatchers.IO) { app.recommender.detail(t) }
    suspend fun pick(f: Recommender.Filters): List<Title> = withContext(Dispatchers.IO) { app.recommender.pick(f) }
    suspend fun search(q: String): List<Title> = withContext(Dispatchers.IO) { app.recommender.search(q) }

    fun onInput() {
        lastInput.value = System.currentTimeMillis()
        if (ambientActive.value) ambientActive.value = false
    }

    private fun checkIdle() {
        val s = settings.value
        // Only when Marquee Ambient is your screensaver: with Google's chosen, idle time belongs to Google's
        // screensaver (Google Photos…), which Marquee's own Ambient used to cover up after 10 minutes.
        if (s.backAction == "AMBIENT" && s.idleAmbient && System.currentTimeMillis() - lastInput.value > s.idleMinutes * 60_000L) ambientActive.value = true
    }

    /** Fires when Home is pressed while Marquee is already up: screens jump back to the top. */
    val homeTaps = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Pick Together: builds the shared deck in the view model, so it survives the screen changing to "shuffling". */
    fun startTogetherRound() = viewModelScope.launch { Together.buildDeck { f -> withContext(Dispatchers.IO) { app.recommender.togetherCandidates(f) } } }

    // ---- Arranging the apps ribbon ----
    /** Moves an app one place left (-1) or right (+1) in the ribbon as currently shown, and remembers the whole order. */
    fun moveApp(current: List<String>, pkg: String, delta: Int) {
        val i = current.indexOf(pkg); val j = i + delta
        if (i < 0 || j !in current.indices) return
        val next = current.toMutableList().apply { removeAt(i); add(j, pkg) }
        app.store.update { it.copy(appOrder = next) }
    }
    fun setAppHidden(pkg: String, hidden: Boolean) = app.store.update { it.copy(hiddenApps = if (hidden) it.hiddenApps + pkg else it.hiddenApps - pkg) }
    fun resetApps() = app.store.update { it.copy(appOrder = emptyList(), hiddenApps = emptySet()) }

    // ---- Arranging the home rows (by group) ----
    /** Moves a row group one place earlier (-1) or later (+1), starting from the order currently shown. */
    fun moveRowGroup(current: List<FeedLayout.RowGroup>, g: FeedLayout.RowGroup, delta: Int) {
        val i = current.indexOf(g); val j = i + delta
        if (i < 0 || j !in current.indices) return
        val next = current.toMutableList().apply { removeAt(i); add(j, g) }
        app.store.update { it.copy(rowOrder = next.map { x -> x.name }) }
    }
    fun setRowGroupHidden(g: FeedLayout.RowGroup, hidden: Boolean) =
        app.store.update { it.copy(hiddenRows = if (hidden) it.hiddenRows + g.name else it.hiddenRows - g.name) }
    fun resetRows() = app.store.update { it.copy(rowOrder = emptyList(), hiddenRows = emptySet()) }

    /** Back on the top of the home screen: the screensaver, like Google TV. */
    fun backOnHome() {
        when (settings.value.backAction) {
            "NONE" -> Unit
            "AMBIENT" -> ambientActive.value = true
            else -> if (MarqueeAccessibilityService.instance?.startGoogleScreensaver() != true) ambientActive.value = true
        }
    }

    /** Why the current style is showing, for Settings. */
    fun styleReason(): String {
        val s = settings.value
        val p = s.profile
        return when {
            p.style != "AUTO" -> "Set by the ${p.name} profile"
            s.autoSwitch -> when (LocalTime.now().hour) { in 5..11 -> "Morning schedule"; in 17..23, in 0..3 -> "Evening schedule"; else -> "Your choice" }
            else -> "Your choice"
        }
    }

    /** Which home style to show right now. */
    fun styleNow(): HomeStyle {
        val s = settings.value
        if (ambientActive.value) return HomeStyle.AMBIENT
        val p = s.profile
        // Ambient only ever appears as the idle screen (any button closes it), never as a chosen style.
        fun pick(x: HomeStyle) = if (x == HomeStyle.AMBIENT) HomeStyle.DASHBOARD else x
        if (p.style != "AUTO") return pick(runCatching { HomeStyle.valueOf(p.style) }.getOrDefault(s.homeStyle))
        if (!s.autoSwitch) return pick(s.homeStyle)
        val h = LocalTime.now().hour
        return when (h) {
            in 5..11 -> pick(s.morningStyle)
            in 17..23, in 0..3 -> pick(s.eveningStyle)
            else -> pick(s.homeStyle)
        }
    }

    // ---- Account linking lives here (not in the screen) so it survives focus moves and navigation ----
    val plexCode = MutableStateFlow<String?>(null)
    val plexMessage = MutableStateFlow("")
    private var plexJob: Job? = null

    fun linkPlex() {
        plexJob?.cancel()
        plexJob = viewModelScope.launch {
            plexMessage.value = "Getting a code from Plex…"
            val pin = withContext(Dispatchers.IO) { runCatching { app.plex.createPin() } }.getOrElse {
                plexMessage.value = "Couldn't reach Plex (${it.message}). Check the TV's internet and try again."; return@launch
            }
            plexCode.value = pin.code
            plexMessage.value = "Waiting for you to enter the code…"
            repeat(150) {
                delay(2000)
                val token = withContext(Dispatchers.IO) { runCatching { app.plex.checkPin(pin) }.getOrNull() }
                if (token != null) {
                    plexMessage.value = "Linked. Finding your server…"
                    val srv = withContext(Dispatchers.IO) { runCatching { app.plex.connect(token) }.getOrNull() }
                    plexCode.value = null
                    plexMessage.value = if (srv == null) "Signed in, but no Plex server answered. Make sure your server is on, then try again." else ""
                    refreshHome()
                    return@launch
                }
            }
            plexCode.value = null
            plexMessage.value = "The code expired. Try again."
        }
    }

    fun cancelPlexLink() { plexJob?.cancel(); plexCode.value = null; plexMessage.value = "" }

    val ytCode = MutableStateFlow<app.marquee.tv.youtube.DeviceCode?>(null)
    val ytMessage = MutableStateFlow("")
    private var ytJob: Job? = null

    fun linkYouTube() {
        ytJob?.cancel()
        ytJob = viewModelScope.launch {
            ytMessage.value = "Getting a code from Google…"
            val c = withContext(Dispatchers.IO) { runCatching { app.youtube.startSignIn() } }.getOrElse { ytMessage.value = it.message ?: "Error"; return@launch }
            ytCode.value = c; ytMessage.value = "Waiting…"
            repeat(120) {
                delay(c.interval * 1000L)
                val done = withContext(Dispatchers.IO) { runCatching { app.youtube.pollSignIn(c) } }.getOrElse { ytMessage.value = it.message ?: "Error"; ytCode.value = null; return@launch }
                if (done) { ytCode.value = null; ytMessage.value = ""; refreshHome(); return@launch }
            }
            ytCode.value = null; ytMessage.value = "The code expired. Try again."
        }
    }

    fun cancelYouTubeLink() { ytJob?.cancel(); ytCode.value = null; ytMessage.value = "" }

    // ---- Card previews: ad-free trailers from Plex, otherwise a slideshow of stills ----
    suspend fun cardPreview(t: Title): CardPreview? = withContext(Dispatchers.IO) {
        val s = settings.value
        if (s.previews == "OFF" || t.youtubeId != null) return@withContext null
        val video = (if (s.plexReady && t.plexKey != null) runCatching { app.plex.trailerUrl(t.plexKey) }.getOrNull() else null)
            ?: runCatching { app.plex.officialTrailer(t) }.getOrNull()
        val stills = if (app.tmdb.ready && t.tmdbId != null) app.tmdb.backdrops(t.mediaType, t.tmdbId).filter { it != t.backdropUrl } else emptyList()
        if (video == null && stills.size < 2) null else CardPreview(video, stills)
    }

    // ---- Key and account health, for the green checks ----
    val health = MutableStateFlow<List<Check>>(emptyList())
    fun checkHealth() = viewModelScope.launch {
        health.value = withContext(Dispatchers.IO) { runCatching { HealthChecks.run(app) }.getOrDefault(emptyList()) }
    }
}
