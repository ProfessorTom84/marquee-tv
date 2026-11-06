package app.marquee.tv.photos

import app.marquee.tv.data.Settings
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.get
import app.marquee.tv.net.str
import app.marquee.tv.net.urlEncode
import app.marquee.tv.ui.components.Landscapes
import app.marquee.tv.ui.components.Scene
import app.marquee.tv.ui.components.Season
import app.marquee.tv.ui.components.seasonFor
import java.time.LocalDate
import java.time.LocalTime

data class AreaPhoto(val url: String, val photographer: String, val link: String)

/**
 * Real photography of where you live, from Unsplash (needs a free key). Chosen by place, season and
 * time of day, and changes daily. Photographers are credited on screen, as Unsplash requires.
 */
object AreaPhotos {
    suspend fun forToday(s: Settings, elevationM: Double?): AreaPhoto? {
        val key = s.unsplashKey.trim()
        if (key.isBlank() || !s.hasLocation) return null
        val choice = Landscapes.detect(s.latitude, s.longitude, elevationM, s.cityName)
        val season = when (seasonFor(LocalDate.now(), s.latitude)) { Season.AUTUMN -> "autumn"; Season.WINTER -> "winter"; Season.SPRING -> "spring"; Season.SUMMER -> "summer" }
        val hour = LocalTime.now().hour
        val light = when (hour) { in 5..8 -> "sunrise"; in 17..19 -> "sunset"; in 20..23, in 0..4 -> "night"; else -> "" }
        val metro = choice.description.substringAfter(" of ", "").ifBlank { choice.description.substringAfter("Downtown ", "") }
        val place = when (choice.scene) {
            Scene.CITY -> "$metro skyline"
            Scene.SUBURB -> "${s.cityName} ${metro}".trim()
            Scene.DESERT -> "new mexico desert mesa"
            Scene.MOUNTAINS -> "${s.cityName} mountains"
            Scene.COAST -> "${s.cityName} beach"
            Scene.PLAINS -> "prairie farmland"
            Scene.FOREST -> "pacific northwest forest"
            Scene.RURAL -> "${s.cityName} countryside"
        }
        val headers = mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1")
        for (q in listOf("$place $season $light", "$place $season", place, "${choice.scene.label} $season")) {
            val res = runCatching {
                Http.get("https://api.unsplash.com/search/photos?query=${q.trim().urlEncode()}&orientation=landscape&per_page=20&content_filter=high", headers, cacheMinutes = 720)["results"].arr
            }.getOrDefault(emptyList())
            if (res.size < 3) continue
            val pick = res[(LocalDate.now().toEpochDay() % res.size).toInt()]
            val raw = pick["urls"]["raw"].str ?: continue
            // Unsplash asks apps to register a "download" when a photo is shown.
            pick["links"]["download_location"].str?.let { dl -> runCatching { Http.getText(dl, headers, cacheMinutes = 1440) } }
            return AreaPhoto("$raw&w=1920&h=1080&fit=crop&q=80", pick["user"]["name"].str ?: "Unsplash", pick["links"]["html"].str ?: "")
        }
        return null
    }
}
