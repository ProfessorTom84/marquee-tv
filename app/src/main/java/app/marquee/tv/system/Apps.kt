package app.marquee.tv.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import app.marquee.tv.data.AvailKind
import app.marquee.tv.data.Availability
import app.marquee.tv.data.Providers
import app.marquee.tv.data.Settings
import app.marquee.tv.data.Title

data class TvApp(val label: String, val packageName: String, val banner: Drawable?, val icon: Drawable?)

class Apps(private val context: Context) {
    private val pm = context.packageManager

    fun installed(): List<TvApp> {
        val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(tv, 0) + pm.queryIntentActivities(phone, 0)
        return infos.distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                TvApp(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull(),
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                )
            }.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Ad-free YouTube clients we know about. TizenTube Cobalt is matched by name because its package id varies by build. */
    fun youtubeAlternatives(): List<TvApp> {
        val known = setOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
        return installed().filter { a ->
            a.packageName in known || a.label.contains("TizenTube", true) || a.label.contains("SmartTube", true) ||
                a.packageName.contains("tizentube", true)
        }
    }

    fun preferredYouTube(s: Settings): String? =
        s.youtubePackage.takeIf { it.isNotBlank() && isInstalled(it) } ?: youtubeAlternatives().firstOrNull()?.packageName

    fun launch(pkg: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) { toast("That app isn't installed"); return false }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun providerPackage(providerId: Int): String? =
        Providers.get(providerId)?.packages?.firstOrNull { isInstalled(it) }

    fun installedProviderIds(): Set<Int> = Providers.all.filter { p -> p.packages.any { isInstalled(it) } }.map { it.id }.toSet()

    /** Opens a YouTube video in the preferred (ad-free) client, falling back to whatever handles the link. */
    fun openYouTube(videoId: String, s: Settings) {
        val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
        val pkg = preferredYouTube(s)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) {
            val ok = runCatching { context.startActivity(Intent(intent).setPackage(pkg)); true }.getOrDefault(false)
            if (ok) return
            launch(pkg)
            return
        }
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { toast("No YouTube app installed") }
    }

    /** Which app plays your Plex library: Plezy when chosen (or installed, on Automatic), otherwise Plex. */
    fun plexPlayerPackage(s: Settings): String = when (s.plexPlayer) {
        "PLEX" -> Providers.PLEX_PACKAGE
        "PLEZY" -> Providers.PLEZY_PACKAGE
        else -> if (isInstalled(Providers.PLEZY_PACKAGE)) Providers.PLEZY_PACKAGE else Providers.PLEX_PACKAGE
    }

    fun plexPlayerName(s: Settings) = if (plexPlayerPackage(s) == Providers.PLEZY_PACKAGE) "Plezy" else "Plex"

    /**
     * Plezy links: plezy://play?type=movie|show&tmdb=…|imdb=… (finds the copy on any of your servers),
     * or plezy://play?server=<machine id>&id=<rating key>. Shows resume at the next episode.
     */
    private fun openPlezy(title: Title, s: Settings): Boolean {
        val params = mutableListOf<String>()
        val kind = if (title.mediaType == "tv") "show" else "movie"
        when {
            title.tmdbId != null -> params += listOf("type=$kind", "tmdb=${title.tmdbId}")
            title.imdbId != null -> params += listOf("type=$kind", "imdb=${title.imdbId}")
            title.plexKey != null && s.plexMachineId.isNotBlank() -> params += listOf("server=${s.plexMachineId}", "id=${title.plexKey}")
            else -> return false
        }
        val uri = Uri.parse("plezy://play?" + params.joinToString("&"))
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(Providers.PLEZY_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
    }

    /** Plays a Plex item in your chosen player; opens the app if the installed version doesn't support the link. */
    fun openPlex(title: Title, s: Settings) {
        if (plexPlayerPackage(s) == Providers.PLEZY_PACKAGE) {
            if (!openPlezy(title, s)) launch(Providers.PLEZY_PACKAGE)
            return
        }
        val key = title.plexKey
        if (key != null && s.plexMachineId.isNotBlank()) {
            val uri = Uri.parse("plex://server://${s.plexMachineId}/com.plexapp.plugins.library/library/metadata/$key")
            val ok = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(Providers.PLEX_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }.getOrDefault(false)
            if (ok) return
        }
        launch(Providers.PLEX_PACKAGE)
    }

    fun open(title: Title, a: Availability? = title.bestAvailability, s: Settings) {
        when {
            title.youtubeId != null -> openYouTube(title.youtubeId, s)
            a == null -> toast("Not available on your services yet")
            a.kind == AvailKind.PLEX -> openPlex(title, s)
            a.providerId != null -> {
                val pkg = providerPackage(a.providerId)
                if (pkg == null) toast("${a.name} isn't installed on this TV") else {
                    launch(pkg)
                    toast("Opening ${a.name}. Search for \"${title.title}\"")
                }
            }
        }
    }

    /** Opens Google TV's own settings (or a specific page of them). */
    fun openSystemSettings(action: String = android.provider.Settings.ACTION_SETTINGS) {
        val ok = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        if (!ok) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    companion object {
        val stockLaunchers = setOf(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
        )
        @Suppress("unused")
        fun pmFlags() = PackageManager.GET_META_DATA
    }
}
