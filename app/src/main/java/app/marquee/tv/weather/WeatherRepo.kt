package app.marquee.tv.weather

import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.dbl
import app.marquee.tv.net.get
import app.marquee.tv.net.int
import app.marquee.tv.net.str
import app.marquee.tv.net.urlEncode
import java.time.LocalDateTime

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

data class HourForecast(val label: String, val temp: Int, val sky: Sky)

data class Weather(
    val temp: Int,
    val high: Int,
    val low: Int,
    val sky: Sky,
    val isDay: Boolean,
    val summary: String,
    val hourly: List<HourForecast>,
    val precipNextHours: Int, // max precipitation probability over the next 6 hours
    val windKph: Double = 0.0,
    val sunrise: java.time.LocalTime? = null,
    val sunset: java.time.LocalTime? = null,
    val elevationM: Double? = null,
)

data class Place(val name: String, val region: String, val lat: Double, val lon: Double, val state: String = "")

/** Open-Meteo: free, no key, no tracking. */
object WeatherRepo {
    suspend fun forecast(lat: Double, lon: Double, fahrenheit: Boolean): Weather {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=2&timezone=auto&temperature_unit=$unit"
        val j = Http.get(url, cacheMinutes = 15)
        val cur = j["current"]
        val code = cur["weather_code"].int ?: 0
        val isDay = (cur["is_day"].int ?: 1) == 1
        val times = j["hourly"]["time"].arr.mapNotNull { it.str }
        val temps = j["hourly"]["temperature_2m"].arr.map { it.dbl ?: 0.0 }
        val codes = j["hourly"]["weather_code"].arr.map { it.int ?: 0 }
        val pops = j["hourly"]["precipitation_probability"].arr.map { it.int ?: 0 }
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val start = times.indexOfFirst { runCatching { !LocalDateTime.parse(it).isBefore(now) }.getOrDefault(false) }.coerceAtLeast(0)
        val hourly = (start + 1..minOf(start + 5, times.lastIndex)).map { i ->
            val t = LocalDateTime.parse(times[i])
            val h = t.hour
            val label = when { h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM" }
            HourForecast(label, temps[i].toInt(), skyFor(codes[i]))
        }
        val sky = skyFor(code)
        return Weather(
            temp = (cur["temperature_2m"].dbl ?: 0.0).toInt(),
            high = (j["daily"]["temperature_2m_max"][0].dbl ?: 0.0).toInt(),
            low = (j["daily"]["temperature_2m_min"][0].dbl ?: 0.0).toInt(),
            sky = sky,
            isDay = isDay,
            summary = describe(code),
            hourly = hourly,
            precipNextHours = pops.drop(start).take(6).maxOrNull() ?: 0,
            windKph = cur["wind_speed_10m"].dbl ?: 0.0,
            elevationM = j["elevation"].dbl,
            sunrise = j["daily"]["sunrise"][0].str?.let { runCatching { LocalDateTime.parse(it).toLocalTime() }.getOrNull() },
            sunset = j["daily"]["sunset"][0].str?.let { runCatching { LocalDateTime.parse(it).toLocalTime() }.getOrNull() },
        )
    }

    suspend fun searchPlaces(query: String): List<Place> {
        val j = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=${query.urlEncode()}&count=8&language=en&format=json")
        return j["results"].arr.map {
            Place(
                name = it["name"].str ?: "",
                region = listOfNotNull(it["admin1"].str, it["country_code"].str).joinToString(", "),
                lat = it["latitude"].dbl ?: 0.0,
                lon = it["longitude"].dbl ?: 0.0,
                state = it["admin1"].str ?: "",
            )
        }
    }

    fun skyFor(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Storms with hail"
        else -> "Cloudy"
    }
}
