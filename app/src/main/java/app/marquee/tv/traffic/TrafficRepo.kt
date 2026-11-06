package app.marquee.tv.traffic

import app.marquee.tv.data.SettingsStore
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.get
import app.marquee.tv.net.int
import app.marquee.tv.net.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

data class Drive(val minutes: Int, val typicalMinutes: Int, val km: Double, val fetchedAt: Long) {
    val delayMinutes get() = (minutes - typicalMinutes).coerceAtLeast(0)
    val trafficLabel get() = when {
        delayMinutes >= 10 -> "traffic heavy"
        delayMinutes >= 4 -> "traffic moderate"
        else -> "traffic light"
    }
}

/** Google Routes API, traffic-aware driving time from home to an event's location. */
class TrafficRepo(private val store: SettingsStore) {
    private val cache = ConcurrentHashMap<String, Drive>()
    val configured get() = store.value.routesKey.isNotBlank() && store.value.homeAddress.isNotBlank()

    suspend fun drive(destination: String, maxAgeMinutes: Int = 10): Drive? {
        if (!configured || destination.isBlank()) return null
        cache[destination]?.let { if (System.currentTimeMillis() - it.fetchedAt < maxAgeMinutes * 60_000L) return it }
        val body = buildJsonObject {
            putJsonObject("origin") { put("address", store.value.homeAddress) }
            putJsonObject("destination") { put("address", destination) }
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
        }.toString()
        return runCatching {
            val j = Http.postJson(
                "https://routes.googleapis.com/directions/v2:computeRoutes", body,
                mapOf(
                    "X-Goog-Api-Key" to store.value.routesKey,
                    "X-Goog-FieldMask" to "routes.duration,routes.staticDuration,routes.distanceMeters",
                ),
            )
            val r = j["routes"].arr.firstOrNull() ?: return null
            fun secs(v: String?) = v?.removeSuffix("s")?.toDoubleOrNull()?.toInt() ?: 0
            Drive(
                minutes = (secs(r["duration"].str) + 59) / 60,
                typicalMinutes = (secs(r["staticDuration"].str) + 59) / 60,
                km = (r["distanceMeters"].int ?: 0) / 1000.0,
                fetchedAt = System.currentTimeMillis(),
            ).also { cache[destination] = it }
        }.getOrNull()
    }

    fun mapsUrl(destination: String) =
        "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + java.net.URLEncoder.encode(destination, "UTF-8")
}
