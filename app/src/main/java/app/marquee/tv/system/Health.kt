package app.marquee.tv.system

import app.marquee.tv.youtube.YouTubeInterests
import app.marquee.tv.MarqueeApp
import app.marquee.tv.net.Http
import app.marquee.tv.net.HttpException
import app.marquee.tv.net.get
import app.marquee.tv.net.str
import app.marquee.tv.weather.WeatherRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class Health { OK, MISSING, FAILED, CHECKING }

data class Check(val key: String, val label: String, val health: Health, val detail: String)

/** Live checks that each key and account actually works, so Settings and the phone page can show a green check. */
object HealthChecks {
    suspend fun run(app: MarqueeApp): List<Check> = coroutineScope {
        val s = app.store.value
        suspend fun probe(key: String, label: String, configured: Boolean, missing: String, ok: String, block: suspend () -> Unit): Check {
            if (!configured) return Check(key, label, Health.MISSING, missing)
            return runCatching { block(); Check(key, label, Health.OK, ok) }.getOrElse { e ->
                val why = when ((e as? HttpException)?.code) {
                    401, 403 -> "Key was rejected. Check it was copied completely."
                    429 -> "Daily limit reached. It will work again tomorrow."
                    null -> e.message ?: "Couldn't connect"
                    else -> "Error ${(e as HttpException).code}"
                }
                Check(key, label, Health.FAILED, why)
            }
        }
        listOf(
            async {
                probe("plex", "Plex", s.plexReady, "Not linked yet", "Connected to ${s.plexServerName}") {
                    Http.getText("${s.plexServerUri}/identity", mapOf("X-Plex-Token" to s.plexServerToken))
                }
            },
            async {
                probe("tmdb", "TMDB", s.tmdbKey.isNotBlank(), "Add a free key on the phone page", "Working") {
                    val bearer = s.tmdbKey.trim().startsWith("eyJ")
                    val url = "https://api.themoviedb.org/3/configuration" + if (bearer) "" else "?api_key=${s.tmdbKey.trim()}"
                    Http.get(url, if (bearer) mapOf("Authorization" to "Bearer ${s.tmdbKey.trim()}") else emptyMap())
                }
            },
            async {
                probe("omdb", "OMDb", s.omdbKey.isNotBlank(), "Optional: adds IMDb and critics scores", "Working") {
                    val j = Http.get("https://www.omdbapi.com/?i=tt0111161&apikey=${s.omdbKey.trim()}")
                    if (j["Response"].str == "False") throw IllegalStateException(j["Error"].str ?: "Key was rejected")
                }
            },
            async {
                probe("calendar", "Calendar", s.calendarUrls.isNotEmpty(), "Add your iCal link on the phone page", "${s.calendarUrls.size} calendar${if (s.calendarUrls.size > 1) "s" else ""} readable") {
                    s.calendarUrls.forEach { u ->
                        val body = Http.getText(u.replace("webcal://", "https://"))
                        if (!body.contains("BEGIN:VCALENDAR")) throw IllegalStateException("That link isn't a calendar file")
                    }
                }
            },
            async {
                probe("routes", "Live traffic", s.routesKey.isNotBlank() && s.homeAddress.isNotBlank(),
                    if (s.routesKey.isBlank()) "Add a Google Routes key" else "Add your home address", "Working") {
                    app.traffic.drive(s.homeAddress, maxAgeMinutes = 0) ?: throw IllegalStateException("Google didn't return a route. Is the Routes API enabled for this key?")
                }
            },
            async {
                val ytChannels = YouTubeInterests.channelsFor(s)
                probe("youtube", "YouTube", (app.youtube.configured && app.youtube.signedIn) || ytChannels.isNotEmpty(),
                    "Pick YouTube interests in Settings, or add channels on the phone page",
                    if (app.youtube.signedIn) "Signed in" else "${ytChannels.size} channels · public feeds, no sign-in") {
                    if (app.youtube.signedIn) app.youtube.newFromSubscriptions(maxChannels = 1, perChannel = 1)
                    else app.youtube.fromFollowedChannels().ifEmpty { throw IllegalStateException("YouTube's channel feeds didn't answer. Try again in a minute.") }
                }
            },
            async {
                probe("unsplash", "Area photos", s.unsplashKey.isNotBlank(), "Optional: real photos of where you live", "Working") {
                    Http.get("https://api.unsplash.com/photos/random?query=landscape", mapOf("Authorization" to "Client-ID ${s.unsplashKey.trim()}", "Accept-Version" to "v1"))
                }
            },
            async {
                probe("weather", "Weather", s.hasLocation, "Add your city", s.cityName) {
                    WeatherRepo.forecast(s.latitude, s.longitude, s.useFahrenheit)
                }
            },
        ).map { it.await() }
    }
}
