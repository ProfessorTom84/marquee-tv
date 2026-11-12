package app.marquee.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Home styles you can choose. AMBIENT is only the idle/clock screen (any button closes it), never a pickable style. */
enum class HomeStyle(val label: String, val blurb: String) {
    DASHBOARD("Dashboard", "Weather, calendar and stats up top, picks below"),
    CINEMATIC("Cinematic", "One title fills the screen, trailers play behind it"),
    AMBIENT("Ambient", "Artwork, a big clock and only what matters now");
}

/**
 * How the home rows and Pick Together lean between your Plex library and your streaming services.
 * [svc], [plex], [yt] and [cont] are the "For you" row's lane weights (percent).
 */
enum class FeedBalance(val label: String, val blurb: String, val svc: Int, val plex: Int, val yt: Int, val cont: Int) {
    MORE_PLEX("More Plex", "Your library leads; services fill in", 35, 35, 15, 15),
    BALANCED("Balanced", "Mostly your services, with the best of your Plex", 50, 20, 15, 15),
    MORE_SERVICES("More services", "Discovery across your services; Plex only fills gaps", 60, 10, 15, 15);

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: BALANCED
    }
}

data class Profile(val name: String, val style: String /* HomeStyle name or "AUTO" */, val kidSafe: Boolean = false)

/** Everything the launcher remembers. API keys and tokens never leave the device. */
data class Settings(
    // Look and behaviour
    val homeStyle: HomeStyle = HomeStyle.DASHBOARD,
    val autoSwitch: Boolean = false,  // switch style by time of day (morning / evening)
    val idleAmbient: Boolean = true,  // go Ambient after idleMinutes without input
    val backAction: String = "GOOGLE", // Back on the home screen: GOOGLE screensaver | AMBIENT | NONE
    val uiSounds: Boolean = true,       // quiet interface sounds
    val updateRepo: String = "ProfessorTom84/marquee-tv", // GitHub repo whose Releases carry Marquee updates
    val updateToken: String = "",       // read-only GitHub token (private repo)
    val autoUpdate: Boolean = true,     // download updates and install them while the TV is idle
    val morningStyle: HomeStyle = HomeStyle.DASHBOARD,
    val eveningStyle: HomeStyle = HomeStyle.CINEMATIC,
    val idleMinutes: Int = 10,
    val previews: String = "PLEX", // OFF | PLEX
    val feedBalance: FeedBalance = FeedBalance.BALANCED, // home rows and Pick Together: Plex vs your services
    val newsSource: app.marquee.tv.news.NewsSource = app.marquee.tv.news.NewsSource.TOP, // the Dashboard's headlines card
    val profiles: List<Profile> = listOf(Profile("Me", "AUTO")),
    val activeProfile: Int = 0,
    // Takeover
    val takeoverHome: Boolean = true,
    val redirectYouTube: Boolean = true,
    val youtubePackage: String = "",
    val scene: String = "AUTO", // AUTO or a Scene name
    val ambientScene: Boolean = false, // Ambient shows your scene instead of Plex artwork
    val plexPlayer: String = "AUTO", // AUTO (Plezy when installed) | PLEZY | PLEX
    val redirectPlex: Boolean = false,
    // Location
    val cityName: String = "",
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val useFahrenheit: Boolean = true,
    val homeAddress: String = "",
    // Services (TMDB provider ids) the household pays for
    val services: Set<Int> = emptySet(),
    val region: String = "US",
    // Keys
    val tmdbKey: String = "",
    val omdbKey: String = "",
    val watchmodeKey: String = "", // optional: open titles directly inside streaming apps
    val routesKey: String = "",
    val youtubeClientId: String = "",
    val youtubeClientSecret: String = "",
    // Accounts
    val plexClientId: String = "",
    val plexToken: String = "",
    val plexServerName: String = "",
    val plexServerUri: String = "",
    val plexServerToken: String = "",
    val plexMachineId: String = "",
    val youtubeRefreshToken: String = "",
    val youtubeChannels: List<String> = emptyList(), // channel ids (UC…) followed without signing in
    val youtubeInterests: Set<String> = setOf("tech", "science", "comedy", "movies"),
    val unsplashKey: String = "",
    val background: String = "SKY", // SKY | PHOTO
    val previewPlace: String = "BACKGROUND", // BACKGROUND | CARD
    val calendarUrls: List<String> = emptyList(),
    val travelBufferMinutes: Int = 5,
    val appOrder: List<String> = emptyList(),  // your order for the apps ribbon (package names)
    val hiddenApps: Set<String> = emptySet(),   // apps hidden from the ribbon (still in All apps)
    val rowOrder: List<String> = emptyList(),  // your order of home row groups (FeedLayout.RowGroup names); empty = Marquee's mix
    val hiddenRows: Set<String> = emptySet(),  // home row groups you've hidden
    val setupToken: String = "",
) {
    val hasLocation get() = !latitude.isNaN() && !longitude.isNaN()
    val plexReady get() = plexServerUri.isNotBlank() && plexServerToken.isNotBlank()
    val profile get() = profiles.getOrElse(activeProfile) { profiles.first() }
}

class SettingsStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("marquee", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    init {
        // Stable ids generated once per install.
        if (value.plexClientId.isBlank() || value.setupToken.isBlank()) {
            update {
                it.copy(
                    plexClientId = it.plexClientId.ifBlank { UUID.randomUUID().toString() },
                    setupToken = it.setupToken.ifBlank { UUID.randomUUID().toString().take(8) },
                )
            }
        }
    }

    /** Every setting as JSON, for copying your setup to another TV. The TV's own setup code is left out. */
    fun exportJson(): String {
        val o = kotlinx.serialization.json.buildJsonObject {
            sp.all.forEach { (k, v) ->
                if (k == "setupToken") return@forEach
                when (v) {
                    is String -> put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive("s")); put("v", kotlinx.serialization.json.JsonPrimitive(v)) })
                    is Boolean -> put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive("b")); put("v", kotlinx.serialization.json.JsonPrimitive(v)) })
                    is Int -> put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive("i")); put("v", kotlinx.serialization.json.JsonPrimitive(v)) })
                    is Long -> put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive("l")); put("v", kotlinx.serialization.json.JsonPrimitive(v)) })
                    is Float -> put(k, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive("f")); put("v", kotlinx.serialization.json.JsonPrimitive(v)) })
                    is Set<*> -> put(k, kotlinx.serialization.json.buildJsonObject {
                        put("t", kotlinx.serialization.json.JsonPrimitive("set"))
                        put("v", kotlinx.serialization.json.JsonArray(v.filterIsInstance<String>().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    })
                }
            }
        }
        return o.toString()
    }

    /** Applies settings exported from another TV (keeps this TV's own setup code). */
    @Synchronized
    fun importJson(json: String) {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val e = sp.edit()
        o.forEach { (k, x) ->
            if (k == "setupToken") return@forEach
            val obj = x as? kotlinx.serialization.json.JsonObject ?: return@forEach
            val v = obj["v"] ?: return@forEach
            val prim = v as? kotlinx.serialization.json.JsonPrimitive
            when ((obj["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "s" -> e.putString(k, prim?.content)
                "b" -> prim?.content?.toBooleanStrictOrNull()?.let { e.putBoolean(k, it) }
                "i" -> prim?.content?.toIntOrNull()?.let { e.putInt(k, it) }
                "l" -> prim?.content?.toLongOrNull()?.let { e.putLong(k, it) }
                "f" -> prim?.content?.toFloatOrNull()?.let { e.putFloat(k, it) }
                "set" -> e.putStringSet(k, (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet() ?: emptySet())
            }
        }
        e.commit()
        _state.value = load()
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): Settings {
        fun s(k: String) = sp.getString(k, "") ?: ""
        // Ambient is no longer a home style you can pick (it had no way out): stored Ambient becomes the default.
        fun style(k: String, d: HomeStyle) = runCatching { HomeStyle.valueOf(s(k)) }.getOrDefault(d).let { if (it == HomeStyle.AMBIENT) d else it }
        val d = Settings()
        return Settings(
            homeStyle = style("homeStyle", d.homeStyle),
            // v2: time-of-day switching is opt-in now (it used to silently override the chosen style).
            autoSwitch = sp.getBoolean("autoSwitch2", d.autoSwitch),
            idleAmbient = sp.getBoolean("idleAmbient", sp.getBoolean("autoSwitch", d.idleAmbient)),
            backAction = sp.getString("backAction", null) ?: d.backAction,
            uiSounds = sp.getBoolean("uiSounds", d.uiSounds),
            updateRepo = s("updateRepo").ifBlank { d.updateRepo },
            updateToken = s("updateToken"),
            autoUpdate = sp.getBoolean("autoUpdate", d.autoUpdate),
            morningStyle = style("morningStyle", d.morningStyle),
            eveningStyle = style("eveningStyle", d.eveningStyle),
            idleMinutes = sp.getInt("idleMinutes", d.idleMinutes),
            previews = s("previews").ifBlank { d.previews },
            feedBalance = FeedBalance.parse(sp.getString("feedBalance", null)),
            newsSource = app.marquee.tv.news.NewsSource.parse(sp.getString("newsSource", null)),
            profiles = decodeProfiles(s("profiles")).ifEmpty { d.profiles },
            activeProfile = sp.getInt("activeProfile", 0),
            takeoverHome = sp.getBoolean("takeoverHome", d.takeoverHome),
            redirectYouTube = sp.getBoolean("redirectYouTube", d.redirectYouTube),
            youtubePackage = s("youtubePackage"),
            scene = s("scene").ifBlank { d.scene },
            ambientScene = sp.getBoolean("ambientScene", d.ambientScene),
            plexPlayer = s("plexPlayer").ifBlank { d.plexPlayer },
            redirectPlex = sp.getBoolean("redirectPlex", d.redirectPlex),
            cityName = s("cityName"),
            latitude = sp.getFloat("latitude", Float.NaN).toDouble(),
            longitude = sp.getFloat("longitude", Float.NaN).toDouble(),
            useFahrenheit = sp.getBoolean("useFahrenheit", d.useFahrenheit),
            homeAddress = s("homeAddress"),
            services = s("services").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
            region = s("region").ifBlank { d.region },
            tmdbKey = s("tmdbKey"),
            omdbKey = s("omdbKey"),
            watchmodeKey = s("watchmodeKey"),
            routesKey = s("routesKey"),
            youtubeClientId = s("youtubeClientId"),
            youtubeClientSecret = s("youtubeClientSecret"),
            plexClientId = s("plexClientId"),
            plexToken = s("plexToken"),
            plexServerName = s("plexServerName"),
            plexServerUri = s("plexServerUri"),
            plexServerToken = s("plexServerToken"),
            plexMachineId = s("plexMachineId"),
            youtubeRefreshToken = s("youtubeRefreshToken"),
            youtubeChannels = s("youtubeChannels").split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            unsplashKey = s("unsplashKey"),
            youtubeInterests = if (sp.contains("youtubeInterests")) s("youtubeInterests").split(',').filter { it.isNotBlank() }.toSet() else d.youtubeInterests,
            background = s("background").let { if (it == "PHOTO") it else "SKY" }, // Spotlight and Illustrated retired
            previewPlace = s("previewPlace").ifBlank { d.previewPlace },
            calendarUrls = s("calendarUrls").split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            travelBufferMinutes = sp.getInt("travelBufferMinutes", d.travelBufferMinutes),
            appOrder = s("appOrder").split(',').filter { it.isNotBlank() },
            hiddenApps = s("hiddenApps").split(',').filter { it.isNotBlank() }.toSet(),
            rowOrder = s("rowOrder").split(',').filter { it.isNotBlank() },
            hiddenRows = s("hiddenRows").split(',').filter { it.isNotBlank() }.toSet(),
            setupToken = s("setupToken"),
        )
    }

    private fun save(v: Settings) {
        sp.edit().apply {
            putString("homeStyle", v.homeStyle.name)
            putBoolean("autoSwitch2", v.autoSwitch)
            putBoolean("idleAmbient", v.idleAmbient)
            putString("backAction", v.backAction)
            putBoolean("uiSounds", v.uiSounds)
            putString("updateRepo", v.updateRepo)
            putString("updateToken", v.updateToken)
            putBoolean("autoUpdate", v.autoUpdate)
            putString("morningStyle", v.morningStyle.name)
            putString("eveningStyle", v.eveningStyle.name)
            putInt("idleMinutes", v.idleMinutes)
            putString("previews", v.previews)
            putString("feedBalance", v.feedBalance.name)
            putString("newsSource", v.newsSource.name)
            putString("profiles", encodeProfiles(v.profiles))
            putInt("activeProfile", v.activeProfile)
            putBoolean("takeoverHome", v.takeoverHome)
            putBoolean("redirectYouTube", v.redirectYouTube)
            putString("youtubePackage", v.youtubePackage)
            putString("scene", v.scene)
            putBoolean("ambientScene", v.ambientScene)
            putString("plexPlayer", v.plexPlayer)
            putBoolean("redirectPlex", v.redirectPlex)
            putString("cityName", v.cityName)
            putFloat("latitude", v.latitude.toFloat())
            putFloat("longitude", v.longitude.toFloat())
            putBoolean("useFahrenheit", v.useFahrenheit)
            putString("homeAddress", v.homeAddress)
            putString("services", v.services.joinToString(","))
            putString("region", v.region)
            putString("tmdbKey", v.tmdbKey)
            putString("omdbKey", v.omdbKey)
            putString("watchmodeKey", v.watchmodeKey)
            putString("routesKey", v.routesKey)
            putString("youtubeClientId", v.youtubeClientId)
            putString("youtubeClientSecret", v.youtubeClientSecret)
            putString("plexClientId", v.plexClientId)
            putString("plexToken", v.plexToken)
            putString("plexServerName", v.plexServerName)
            putString("plexServerUri", v.plexServerUri)
            putString("plexServerToken", v.plexServerToken)
            putString("plexMachineId", v.plexMachineId)
            putString("youtubeRefreshToken", v.youtubeRefreshToken)
            putString("youtubeChannels", v.youtubeChannels.joinToString("\n"))
            putString("unsplashKey", v.unsplashKey)
            putString("youtubeInterests", v.youtubeInterests.joinToString(","))
            putString("background", v.background)
            putString("previewPlace", v.previewPlace)
            putString("calendarUrls", v.calendarUrls.joinToString("\n"))
            putInt("travelBufferMinutes", v.travelBufferMinutes)
            remove("watchlist") // retired feature
            putString("appOrder", v.appOrder.joinToString(","))
            putString("hiddenApps", v.hiddenApps.joinToString(","))
            putString("rowOrder", v.rowOrder.joinToString(","))
            putString("hiddenRows", v.hiddenRows.joinToString(","))
            putString("setupToken", v.setupToken)
        }.apply()
    }

    private fun encodeProfiles(list: List<Profile>) =
        list.joinToString("|") { "${it.name.replace("|", " ").replace(";", " ")};${it.style};${it.kidSafe}" }

    private fun decodeProfiles(raw: String): List<Profile> =
        raw.split('|').mapNotNull { part ->
            val f = part.split(';')
            if (f.size < 2 || f[0].isBlank()) null else Profile(f[0], f[1], f.getOrNull(2) == "true")
        }
}
