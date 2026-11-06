package app.marquee.tv.youtube

import app.marquee.tv.data.SettingsStore
import app.marquee.tv.data.Source
import app.marquee.tv.data.Title
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.get
import app.marquee.tv.net.int
import app.marquee.tv.net.str
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

data class DeviceCode(val deviceCode: String, val userCode: String, val verifyUrl: String, val interval: Int)

/**
 * YouTube Data API with Google's TV sign-in (device flow). Only the read-only YouTube scope is requested.
 * Watch history is not available from this API, so the row shows new uploads from subscriptions.
 */
class YouTubeRepo(private val store: SettingsStore) {
    private val s get() = store.value
    private var accessToken: String? = null
    private var accessExpiry: Long = 0
    val configured get() = s.youtubeClientId.isNotBlank() && s.youtubeClientSecret.isNotBlank()
    val signedIn get() = s.youtubeRefreshToken.isNotBlank()

    suspend fun startSignIn(): DeviceCode {
        val j = Http.postForm(
            "https://oauth2.googleapis.com/device/code",
            mapOf("client_id" to s.youtubeClientId, "scope" to "https://www.googleapis.com/auth/youtube.readonly"),
        )
        j["error"].str?.let { error("Google sign-in error: $it") }
        return DeviceCode(j["device_code"].str ?: "", j["user_code"].str ?: "", j["verification_url"].str ?: "google.com/device", j["interval"].int ?: 5)
    }

    /** true when signed in, false while still waiting; throws if the code expired or was denied. */
    suspend fun pollSignIn(code: DeviceCode): Boolean {
        val j = Http.postForm(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "client_id" to s.youtubeClientId, "client_secret" to s.youtubeClientSecret,
                "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        when (j["error"].str) {
            null -> {}
            "authorization_pending", "slow_down" -> return false
            else -> error("Google sign-in: ${j["error"].str}")
        }
        val refresh = j["refresh_token"].str ?: return false
        accessToken = j["access_token"].str
        accessExpiry = System.currentTimeMillis() + (j["expires_in"].int ?: 3600) * 1000L - 60_000
        store.update { it.copy(youtubeRefreshToken = refresh) }
        return true
    }

    fun signOut() { accessToken = null; store.update { it.copy(youtubeRefreshToken = "") } }

    private suspend fun token(): String {
        accessToken?.let { if (System.currentTimeMillis() < accessExpiry) return it }
        val j = Http.postForm(
            "https://oauth2.googleapis.com/token",
            mapOf("client_id" to s.youtubeClientId, "client_secret" to s.youtubeClientSecret,
                "refresh_token" to s.youtubeRefreshToken, "grant_type" to "refresh_token"),
        )
        val t = j["access_token"].str ?: error("YouTube sign-in expired. Sign in again in Settings.")
        accessToken = t
        accessExpiry = System.currentTimeMillis() + (j["expires_in"].int ?: 3600) * 1000L - 60_000
        return t
    }

    private suspend fun yt(path: String, cache: Int = 30) =
        Http.get("https://www.googleapis.com/youtube/v3/$path", mapOf("Authorization" to "Bearer ${token()}"), cache)

    /**
     * Latest uploads from channels you follow, read from YouTube's public RSS feeds. No account or key needed:
     * just list the channels on the phone setup page.
     */
    suspend fun fromFollowedChannels(perChannel: Int = 3): List<Title> = kotlinx.coroutines.coroutineScope {
        YouTubeInterests.channelsFor(s).take(45).map { id ->
            async { runCatching { parseFeed(Http.getText("https://www.youtube.com/feeds/videos.xml?channel_id=$id", cacheMinutes = 30)).take(perChannel) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().sortedByDescending { it.published }
            // Newest video from each channel first, then the rest, so one busy channel can't fill the row.
            .let { all -> val firsts = all.distinctBy { it.channel }; firsts + (all - firsts.toSet()) }
            .map { it.copy(published = ago(it.published)) }.take(24)
    }

    private fun parseFeed(xml: String): List<Title> {
        val channel = Regex("<author>\\s*<name>([^<]*)</name>").find(xml)?.groupValues?.get(1)?.let(::unxml) ?: ""
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { e ->
            val body = e.groupValues[1]
            val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("<title>([^<]*)</title>").find(body)?.groupValues?.get(1)?.let(::unxml) ?: ""
            val published = Regex("<published>([^<]+)</published>").find(body)?.groupValues?.get(1) ?: ""
            if (body.contains("/shorts/")) return@mapNotNull null
            Title(
                id = "yt:$id", source = Source.YOUTUBE, title = title, mediaType = "video",
                posterUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                backdropUrl = "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                youtubeId = id, channel = channel, published = published,
                overview = Regex("<media:description>([^<]*)</media:description>").find(body)?.groupValues?.get(1)?.let(::unxml)?.take(300) ?: "",
            )
        }.toList()
    }

    private fun unxml(v: String) = v.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    companion object {
        /** Turns @handles, channel links, channel ids or a pasted Google Takeout subscriptions file into channel ids. */
        suspend fun resolveChannels(input: String): List<String> {
            val ids = Regex("UC[\\w-]{22}").findAll(input).map { it.value }.toMutableSet()
            val handles = Regex("@([A-Za-z0-9._-]{3,30})").findAll(input).map { it.groupValues[1] }.toSet()
            for (h in handles) {
                runCatching {
                    val page = Http.getText("https://www.youtube.com/@$h", mapOf("Accept-Language" to "en-US"), cacheMinutes = 10_080)
                    // The canonical link is the channel the page belongs to (other ids on the page can be sister channels).
                    (Regex("<link rel=\"canonical\" href=\"https://www.youtube.com/channel/(UC[\\w-]{22})\"").find(page)
                        ?: Regex("\"externalId\":\"(UC[\\w-]{22})\"").find(page))?.groupValues?.get(1)?.let { ids += it }
                }
            }
            return ids.toList()
        }
    }

    /** Latest uploads across the channels you subscribe to (signed in), or channels you follow plus your interest picks. */
    suspend fun newFromSubscriptions(maxChannels: Int = 25, perChannel: Int = 2): List<Title> {
        if (!signedIn || !configured) return fromFollowedChannels()
        val subs = yt("subscriptions?mine=true&part=snippet&maxResults=50&order=relevance", 120)["items"].arr
        val channelIds = subs.mapNotNull { it["snippet"]["resourceId"]["channelId"].str }.take(maxChannels)
        if (channelIds.isEmpty()) return emptyList()
        val uploads = yt("channels?part=contentDetails&id=${channelIds.joinToString(",")}", 720)["items"].arr
            .mapNotNull { it["contentDetails"]["relatedPlaylists"]["uploads"].str }
        val videos = uploads.flatMap { pl ->
            runCatching { yt("playlistItems?part=snippet,contentDetails&maxResults=$perChannel&playlistId=$pl", 30)["items"].arr }.getOrDefault(emptyList())
        }.mapNotNull { v ->
            val sn = v["snippet"]
            val id = v["contentDetails"]["videoId"].str ?: return@mapNotNull null
            val published = v["contentDetails"]["videoPublishedAt"].str ?: sn["publishedAt"].str ?: ""
            Triple(id, sn, published)
        }.sortedByDescending { it.third }.take(20)
        if (videos.isEmpty()) return emptyList()
        val durations = runCatching {
            yt("videos?part=contentDetails&id=${videos.joinToString(",") { it.first }}", 60)["items"].arr
                .associate { (it["id"].str ?: "") to (it["contentDetails"]["duration"].str ?: "") }
        }.getOrDefault(emptyMap())
        return videos.map { (id, sn, published) ->
            val thumbs = sn["thumbnails"]
            val thumb = thumbs["maxres"]["url"].str ?: thumbs["standard"]["url"].str ?: thumbs["high"]["url"].str ?: thumbs["medium"]["url"].str
            Title(
                id = "yt:$id",
                source = Source.YOUTUBE,
                title = sn["title"].str ?: "",
                mediaType = "video",
                posterUrl = thumb,
                backdropUrl = thumb,
                youtubeId = id,
                channel = sn["videoOwnerChannelTitle"].str ?: sn["channelTitle"].str ?: "",
                published = ago(published),
                duration = formatDuration(durations[id]),
                overview = sn["description"].str?.take(300) ?: "",
            )
        }
    }

    private fun ago(iso: String): String = runCatching {
        val mins = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
        when {
            mins < 60 -> "${mins.coerceAtLeast(1)} min ago"
            mins < 60 * 24 -> "${mins / 60} hours ago".replace("1 hours", "1 hour")
            mins < 60 * 48 -> "Yesterday"
            else -> "${mins / (60 * 24)} days ago"
        }
    }.getOrDefault("")

    private fun formatDuration(iso: String?): String = runCatching {
        val d = Duration.parse(iso ?: return "")
        val h = d.toHours(); val m = d.toMinutesPart(); val s = d.toSecondsPart()
        if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }.getOrDefault("")
}


/**
 * YouTube without an account: hand-picked channels by interest, read from YouTube's public RSS feeds.
 * Channel ids were checked against each channel's own feed. Studio channels only appear for services you pay for.
 */
object YouTubeInterests {
    data class Pack(val key: String, val label: String, val channels: List<String>)

    val packs = listOf(
        Pack("tech", "Tech", listOf("UCBJycsmduvYEL83R_U4JriQ", "UCXuqSBlHAE6Xw-yeJA0Tunw")),
        Pack("science", "Science & nature", listOf("UCHnyfMqiRRG1u-2MsSQLbXA", "UCsXVk37bltHxD1rDPwtNM8Q", "UCY1kMZp36IQSyNx_9h4mpCg",
            "UC6107grRI4m0o2-emgoDnAA", "UCLA_DiR1FfKNvjuUpBHmylQ", "UCpVm7bg6pXKo1Pr6k5kxG9A", "UCAuUUnT6oDeKwE6v1NGQxug")),
        Pack("comedy", "Late night & comedy", listOf("UCVTyTA7-g9nopHeHbeuvpRA", "UC8-Th83bH_thdKZDJCrn88g", "UCa6vGFO9ty8v5KZJXQxdhaw", "UCRijo3ddMTht_IHyNSNXpNQ")),
        Pack("food", "Food", listOf("UCJHA_jMfCvEnv-3kRjTCQXw", "UChBEbMKI1eCcejTtmI32UEw")),
        Pack("sports", "Sports", listOf("UCDVYQ4Zhbm3S2dlz7P1GBDg", "UCiWLfSweyRNmLpgEHekhoAg")),
        Pack("movies", "Movie & TV news", listOf("UC2iUwfYi_1FCGGqhOUNx-iA")),
        Pack("kids", "Kids", listOf("UCrNnk0wFBnCS1awGjq_ijGQ", "UCVzLLZkDuFGAE2BGdBuBNBg")),
    )

    /** Official channels of streaming services (trailers and first looks), keyed by TMDB provider id. */
    private val serviceChannels = mapOf(
        8 to "UCWOA1ZGywLbqmigxE4Qlvuw", 9 to "UCQJWtTnAHhEG5w4uN0udnUQ", 337 to "UCIrgJInjLS2BhlHOMDW7v0g",
        350 to "UC1Myj674wRVXB9I4c6Hm5zA", 15 to "UCE5mQnNl8Q4H2qcv4ikaXeA", 1899 to "UCVTQuK2CaWaTgSsoNkn5AiQ",
    )

    fun channelsFor(s: app.marquee.tv.data.Settings): List<String> {
        // Kid-safe profiles only ever get the kids pack, never your own channel list.
        if (s.profile.kidSafe) return packs.first { it.key == "kids" }.channels
        val interests = s.youtubeInterests
        val fromPacks = packs.filter { it.key in interests }.flatMap { it.channels }
        val fromServices = if ("movies" in interests) s.services.mapNotNull { serviceChannels[it] } else emptyList()
        return (s.youtubeChannels + fromPacks + fromServices).distinct()
    }
}
