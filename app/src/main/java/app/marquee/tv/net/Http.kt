package app.marquee.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Small JSON-over-HTTP helper with a TTL cache kept in memory and on disk. The disk copy means a restart or an
 * update reuses every lookup that hasn't expired (TMDB, Plex…) instead of re-fetching a few hundred of them.
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(val at: Long, val body: String)
    private val cache = ConcurrentHashMap<String, Entry>()

    // ---- Disk copy (the app's private cache folder; Android may also clear it when space runs low) ----
    @Volatile private var diskDir: java.io.File? = null
    private const val DISK_MAX_BYTES = 48L * 1024 * 1024
    private const val DISK_MAX_AGE_MS = 31L * 24 * 60 * 60_000

    /** Called once at startup. Trims the folder in the background. */
    fun init(dir: java.io.File) {
        diskDir = dir.apply { mkdirs() }
        Thread { runCatching { prune(dir) } }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun prune(dir: java.io.File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.toMutableList() ?: return
        files.filter { now - it.lastModified() > DISK_MAX_AGE_MS || it.name.endsWith(".tmp") }.forEach { it.delete(); files.remove(it) }
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX_BYTES * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    private fun diskFile(key: String): java.io.File? = diskDir?.let { d ->
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        java.io.File(d, h)
    }

    private fun readDisk(key: String, maxAgeMs: Long): Entry? = runCatching {
        val f = diskFile(key)?.takeIf { it.exists() } ?: return null
        val at = f.lastModified()
        if (System.currentTimeMillis() - at >= maxAgeMs) return null
        Entry(at, f.readText())
    }.getOrNull()

    private fun writeDisk(key: String, body: String) = runCatching {
        val f = diskFile(key) ?: return@runCatching
        val tmp = java.io.File(f.path + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(f)) tmp.delete()
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), cacheMinutes: Int = 0): String =
        withContext(Dispatchers.IO) {
            val key = url + headers.hashCode()
            val ttl = cacheMinutes * 60_000L
            if (cacheMinutes > 0) {
                cache[key]?.let { if (System.currentTimeMillis() - it.at < ttl) return@withContext it.body }
                readDisk(key, ttl)?.let { cache[key] = it; return@withContext it.body }
            }
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} for ${url.substringBefore('?')}")
                if (cacheMinutes > 0) { cache[key] = Entry(System.currentTimeMillis(), body); writeDisk(key, body) }
                body
            }
        }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), cacheMinutes: Int = 0): JsonElement =
        json.parseToJsonElement(getText(url, headers + ("Accept" to "application/json"), cacheMinutes))

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val req = Request.Builder().url(url).post(body).header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful && text.isBlank()) throw HttpException(r.code, "HTTP ${r.code}")
                json.parseToJsonElement(text.ifBlank { "{}" })
            }
        }

    suspend fun postJson(url: String, payload: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}: ${text.take(200)}")
                json.parseToJsonElement(text.ifBlank { "{}" })
            }
        }

    fun clearCache() {
        cache.clear()
        val files = diskDir?.listFiles() ?: return
        Thread { files.forEach { it.delete() } }.start() // called from buttons: keep file work off the main thread
    }

    /** Tests: forget the in-memory copy only, as a restart would. */
    internal fun forgetMemory() = cache.clear()
}

// ---- Tiny JSON accessors so API code stays readable ----
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.intOrNull ?: (this as? JsonPrimitive)?.doubleOrNull?.toInt()
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
