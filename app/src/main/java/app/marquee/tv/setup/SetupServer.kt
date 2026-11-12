package app.marquee.tv.setup

import android.graphics.Bitmap
import android.graphics.Color
import app.marquee.tv.data.Providers
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.net.Http
import app.marquee.tv.weather.WeatherRepo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A tiny web page served by the TV on your home network, so keys, addresses and calendar links
 * can be typed or pasted on a phone instead of with the remote. Protected by a per-install token in the URL.
 */
class SetupServer(
    private val store: SettingsStore,
    private val youtubeApps: () -> List<Pair<String, String>>,
    private val onSaved: () -> Unit,
    private val health: () -> List<app.marquee.tv.system.Check> = { emptyList() },
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8765
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()

        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }

    fun url(): String? = localIp()?.let { "http://$it:$PORT/s/${store.value.setupToken}" }

    override fun serve(session: IHTTPSession): Response {
        val token = store.value.setupToken
        // Pick Together voting pages (open to anyone on your network holding the round's code).
        if (session.uri.startsWith("/t/")) app.marquee.tv.together.Together.serve(session)?.let { return it }
        if (!session.uri.startsWith("/s/$token")) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        // Diagnostics: read-only GETs to Plex's own services with this TV's Plex sign-in (for troubleshooting trailers).
        if (session.uri.endsWith("/trailerprobe")) {
            val t = app.marquee.tv.data.Title(id = "probe:" + System.nanoTime(), source = app.marquee.tv.data.Source.STREAMING,
                title = session.parms["title"] ?: "", year = session.parms["year"] ?: "", mediaType = session.parms["type"] ?: "movie")
            val url = runCatching { runBlocking { app.marquee.tv.MarqueeApp.instance.plex.officialTrailer(t) } }.getOrElse { "ERROR ${it.message}" }
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, (url ?: "none").replace(Regex("X-Plex-Token=[^&]+"), "X-Plex-Token=…"))
        }
        if (session.uri.endsWith("/plexprobe")) {
            val host = session.parms["host"] ?: "discover"
            val path = session.parms["path"] ?: "/"
            val s = store.value
            val base = when (host) {
                "discover" -> "https://discover.provider.plex.tv"
                "metadata" -> "https://metadata.provider.plex.tv"
                "plextv" -> "https://plex.tv"
                "server" -> s.plexServerUri
                else -> return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad host")
            }
            val tok = if (host == "server") s.plexServerToken else s.plexToken
            val body = runCatching {
                okhttp3.Request.Builder().url(base + path).header("X-Plex-Token", tok).header("Accept", "application/json")
                    .header("X-Plex-Client-Identifier", s.plexClientId).header("X-Plex-Product", "Marquee").build()
                    .let { app.marquee.tv.net.Http.client.newCall(it).execute().use { r -> "HTTP ${r.code}\n" + (r.body?.string() ?: "") } }
            }.getOrElse { "ERROR ${it.message}" }
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
        }
        // Version and update status, for checking a TV remotely.
        if (session.uri.endsWith("/status")) {
            val ctx = app.marquee.tv.MarqueeApp.instance
            val (ver, code) = app.marquee.tv.system.Updater.currentVersion(ctx)
            val u = app.marquee.tv.system.Updater.state.value
            val body = kotlinx.serialization.json.buildJsonObject {
                put("version", kotlinx.serialization.json.JsonPrimitive(ver)); put("versionCode", kotlinx.serialization.json.JsonPrimitive(code))
                put("update", kotlinx.serialization.json.JsonPrimitive(u.phase.name)); put("latest", kotlinx.serialization.json.JsonPrimitive(u.latest ?: ""))
                put("message", kotlinx.serialization.json.JsonPrimitive(u.message)); put("repo", kotlinx.serialization.json.JsonPrimitive(store.value.updateRepo))
                put("hasToken", kotlinx.serialization.json.JsonPrimitive(store.value.updateToken.isNotBlank()))
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", body.toString())
        }
        // Copy this TV's setup to another Marquee TV (both behind each TV's own setup code).
        if (session.uri.endsWith("/export")) return newFixedLengthResponse(Response.Status.OK, "application/json", store.exportJson())
        if (session.uri.endsWith("/import") && session.method == Method.POST) {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            val body = files["postData"] ?: session.parms["json"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "no data")
            return runCatching { store.importJson(body); onSaved(); newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "imported") }
                .getOrElse { newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad data: ${it.message}") }
        }
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            val p = session.parms
            val saved = save(p)
            onSaved()
            return html(page(if (saved) "Saved. Your TV will refresh in a moment." else "Saved, but the city wasn't found."))
        }
        return html(page(null))
    }

    private fun html(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun save(p: Map<String, String>): Boolean {
        var cityOk = true
        val city = p["city"]?.trim().orEmpty()
        val place = if (city.isNotBlank() && city != store.value.cityName) {
            runCatching { runBlocking { WeatherRepo.searchPlaces(city).firstOrNull() } }.getOrNull().also { if (it == null) cityOk = false }
        } else null
        val services = Providers.all.filter { p["svc_${it.id}"] == "on" }.map { it.id }.toSet()
        val channels = p["ytChannels"]?.let { txt -> if (txt.isBlank()) emptyList() else runCatching { runBlocking { app.marquee.tv.youtube.YouTubeRepo.resolveChannels(txt) } }.getOrNull() }
        store.update { s ->
            s.copy(
                tmdbKey = p["tmdbKey"]?.trim() ?: s.tmdbKey,
                omdbKey = p["omdbKey"]?.trim() ?: s.omdbKey,
                watchmodeKey = p["watchmodeKey"]?.trim() ?: s.watchmodeKey,
                routesKey = p["routesKey"]?.trim() ?: s.routesKey,
                youtubeClientId = p["ytId"]?.trim() ?: s.youtubeClientId,
                youtubeClientSecret = p["ytSecret"]?.trim() ?: s.youtubeClientSecret,
                homeAddress = p["home"]?.trim() ?: s.homeAddress,
                calendarUrls = p["ics"]?.lines()?.map { it.trim() }?.filter { it.startsWith("http") || it.startsWith("webcal") } ?: s.calendarUrls,
                services = services,
                feedBalance = p["balance"]?.let { app.marquee.tv.data.FeedBalance.parse(it) } ?: s.feedBalance,
                newsSource = p["news"]?.let { app.marquee.tv.news.NewsSource.parse(it) } ?: s.newsSource,
                region = p["region"]?.trim()?.uppercase()?.take(2)?.ifBlank { null } ?: s.region,
                youtubePackage = p["ytApp"] ?: s.youtubePackage,
                youtubeChannels = channels ?: s.youtubeChannels,
                updateRepo = p["updRepo"]?.trim() ?: s.updateRepo,
                updateToken = p["updToken"]?.trim()?.takeIf { it.isNotBlank() } ?: s.updateToken,
                youtubeInterests = if (p["yti_form"] == "1") app.marquee.tv.youtube.YouTubeInterests.packs.filter { pk -> p["yti_${pk.key}"] == "on" }.map { it.key }.toSet() else s.youtubeInterests,
                unsplashKey = p["unsplashKey"]?.trim() ?: s.unsplashKey,
                useFahrenheit = p["units"] != "c",
                cityName = place?.name ?: s.cityName,
                latitude = place?.lat ?: s.latitude,
                longitude = place?.lon ?: s.longitude,
                profiles = s.profiles.mapIndexed { i, pr -> p["profile_$i"]?.trim()?.takeIf { it.isNotBlank() }?.let { pr.copy(name = it.take(20)) } ?: pr },
            )
        }
        Http.clearCache()
        return cityOk
    }

    private fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(message: String?): String {
        val s = store.value
        val ytiBoxes = app.marquee.tv.youtube.YouTubeInterests.packs.joinToString("") { pk ->
            """<label class="chk"><input type="checkbox" name="yti_${pk.key}" ${if (pk.key in s.youtubeInterests) "checked" else ""}> ${esc(pk.label)}</label>"""
        }
        val svc = Providers.all.joinToString("") {
            """<label class="chk"><input type="checkbox" name="svc_${it.id}" ${if (it.id in s.services) "checked" else ""}> ${esc(it.name)}</label>"""
        }
        val apps = youtubeApps()
        val ytOptions = (listOf("" to "Pick automatically") + apps).joinToString("") { (pkg, label) ->
            """<option value="${esc(pkg)}" ${if (pkg == s.youtubePackage) "selected" else ""}>${esc(label)}</option>"""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Marquee setup</title><style>
:root{color-scheme:dark}body{margin:0;background:#0B1024;color:#F4F1EA;font:16px/1.5 system-ui,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px 64px}h1{font-size:28px;margin:0 0 4px}h2{font-size:18px;margin:28px 0 8px;color:#F5B54A}
p.hint{color:#B7BDCF;font-size:14px;margin:4px 0 10px}label{display:block;font-weight:600;margin:12px 0 4px}
input[type=text],input[type=password],textarea,select{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #39406A;background:#141A38;color:#F4F1EA;font:inherit}
textarea{min-height:90px}.chk{display:inline-flex;gap:8px;align-items:center;font-weight:500;margin:6px 16px 6px 0}
button{margin-top:28px;width:100%;padding:16px;border:0;border-radius:12px;background:#F5B54A;color:#1A1206;font:700 18px system-ui}
.msg{background:#1E3A2A;border:1px solid #3F7A55;padding:12px;border-radius:10px;margin:12px 0}a{color:#F5B54A}
</style></head><body><main>
<h1>Marquee setup</h1><p class="hint">Changes save to your TV only. Keys never leave your home network except to call the service they belong to.</p>
${message?.let { "<div class=\"msg\">${esc(it)}</div>" } ?: ""}
<h2>Status</h2>
${runCatching { health() }.getOrDefault(emptyList()).joinToString("") { c ->
    val (mark, color) = when (c.health) { app.marquee.tv.system.Health.OK -> "✓" to "#3FAE62"; app.marquee.tv.system.Health.FAILED -> "✕" to "#D9534F"; else -> "–" to "#555B78" }
    "<div style=\"display:flex;gap:10px;align-items:center;margin:6px 0\"><span style=\"display:inline-flex;width:22px;height:22px;border-radius:11px;background:$color;color:#fff;align-items:center;justify-content:center;font-weight:700\">$mark</span><b style=\"min-width:96px\">${esc(c.label)}</b><span style=\"color:#B7BDCF\">${esc(c.detail)}</span></div>"
}}
${app.marquee.tv.system.PerfCheck.lastText(app.marquee.tv.MarqueeApp.instance)?.let { "<h2>Last performance check</h2><p class=\"hint\">Run it on the TV: Settings → Performance check.</p><pre style=\"white-space:pre-wrap;background:#141A38;border:1px solid #39406A;border-radius:10px;padding:12px;font-size:13px\">${esc(it)}</pre>" } ?: ""}
<form method="post">
<h2>Your services</h2><p class="hint">Recommendations only include what these plans already cover. Rentals and purchases are never shown.</p>
$svc
<label for="balance">Feed balance</label><select id="balance" name="balance">${app.marquee.tv.data.FeedBalance.entries.joinToString("") { b ->
    "<option value=\"${b.name}\" ${if (b == s.feedBalance) "selected" else ""}>${esc(b.label)}: ${esc(b.blurb)}</option>"
}}</select>
<p class="hint">How much your home rows and Pick Together lean on your Plex versus your services.</p>
<label for="region">Country code</label><input type="text" id="region" name="region" value="${esc(s.region)}">

<h2>Profiles</h2>
${s.profiles.mapIndexed { i, pr -> "<label for=\"profile_$i\">Profile ${i + 1} name</label><input type=\"text\" id=\"profile_$i\" name=\"profile_$i\" value=\"${esc(pr.name)}\">" }.joinToString("")}

<h2>News headlines</h2><p class="hint">Shown on the Dashboard. From free news feeds; nothing to sign up for.</p>
<select id="news" name="news">${app.marquee.tv.news.NewsSource.entries.joinToString("") { n ->
    "<option value=\"${n.name}\" ${if (n == s.newsSource) "selected" else ""}>${esc(n.label)}${if (n == app.marquee.tv.news.NewsSource.LOCAL) " (your city)" else ""}</option>"
}}</select>

<h2>Weather</h2>
<label for="city">City</label><input type="text" id="city" name="city" value="${esc(s.cityName)}" placeholder="e.g. Brooklyn">
<label class="chk"><input type="radio" name="units" value="f" ${if (s.useFahrenheit) "checked" else ""}> °F</label>
<label class="chk"><input type="radio" name="units" value="c" ${if (!s.useFahrenheit) "checked" else ""}> °C</label>

<h2>Movie and TV data</h2>
<label for="tmdbKey">TMDB API key or read access token</label><input type="password" id="tmdbKey" name="tmdbKey" value="${esc(s.tmdbKey)}">
<p class="hint">Free at <a href="https://www.themoviedb.org/settings/api">themoviedb.org/settings/api</a>. Powers trending, where-to-watch and artwork.</p>
<label for="omdbKey">OMDb API key (optional, for IMDb and Rotten Tomatoes scores)</label><input type="password" id="omdbKey" name="omdbKey" value="${esc(s.omdbKey)}">
<label for="watchmodeKey">Watchmode API key (optional: "Play on Netflix" opens the title itself, not just the app)</label><input type="password" id="watchmodeKey" name="watchmodeKey" value="${esc(s.watchmodeKey)}">
<p class="hint">Free at <a href="https://api.watchmode.com/">api.watchmode.com</a>. Marquee only looks a title up when you press Play, and remembers it for a week.</p>
<p class="hint"><a href="https://www.omdbapi.com/apikey.aspx">omdbapi.com</a></p>

<label for="unsplashKey">Unsplash access key (optional: real photos of where you live as the background)</label><input type="password" id="unsplashKey" name="unsplashKey" value="${esc(s.unsplashKey)}">
<p class="hint">Free at <a href="https://unsplash.com/developers">unsplash.com/developers</a>. Then pick "Photo of my area" under Settings → Weather & background on the TV.</p>

<h2>Calendar and leave-now alerts</h2>
<label for="ics">Calendar iCal addresses, one per line</label><textarea id="ics" name="ics">${esc(s.calendarUrls.joinToString("\n"))}</textarea>
<p class="hint">Google Calendar on a computer: Settings → your calendar → Integrate calendar → copy "Secret address in iCal format".</p>
<label for="home">Home address (where trips start)</label><input type="text" id="home" name="home" value="${esc(s.homeAddress)}">
<label for="routesKey">Google Routes API key (for live traffic)</label><input type="password" id="routesKey" name="routesKey" value="${esc(s.routesKey)}">
<p class="hint">Google Cloud console → enable "Routes API" → Credentials → API key.</p>

<h2>Updates</h2>
<p class="hint">Marquee updates itself from the Releases of your private GitHub repo, installing while the TV is idle.</p>
<label for="updRepo">GitHub repo (owner/name)</label><input type="text" id="updRepo" name="updRepo" value="${esc(s.updateRepo)}" placeholder="yourname/marquee-tv">
<label for="updToken">Read-only GitHub token ${if (s.updateToken.isNotBlank()) "(saved; leave blank to keep)" else ""}</label><input type="password" id="updToken" name="updToken" value="" placeholder="github_pat_…">
<p class="hint">Create it at github.com → Settings → Developer settings → Fine-grained tokens: only this repo, Contents: Read-only.</p>

<h2>YouTube</h2>
<label for="ytApp">Open YouTube videos and trailers in</label><select id="ytApp" name="ytApp">$ytOptions</select>
<p class="hint">Without signing in, Marquee follows public channel feeds. Pick what you're into:</p><input type="hidden" name="yti_form" value="1">
$ytiBoxes
<label for="ytChannels">Channels you follow (no sign-in needed)</label><textarea id="ytChannels" name="ytChannels" placeholder="@mkbhd&#10;@veritasium&#10;https://www.youtube.com/@NileRed">${esc(s.youtubeChannels.joinToString("\n"))}</textarea>
<p class="hint">One per line: @handles, channel links, or paste your subscriptions.csv from Google Takeout. ${s.youtubeChannels.size} followed. New uploads mix into your home screen and open in your YouTube app.</p>
<label for="ytId">Google OAuth client ID (type "TVs and Limited Input devices")</label><input type="text" id="ytId" name="ytId" value="${esc(s.youtubeClientId)}">
<label for="ytSecret">Client secret</label><input type="password" id="ytSecret" name="ytSecret" value="${esc(s.youtubeClientSecret)}">
<p class="hint">Needed for "New from your subscriptions". Enable "YouTube Data API v3" in the same Google Cloud project, then sign in from the TV.</p>
<button type="submit">Save to TV</button>
</form></main></body></html>"""
    }
}
