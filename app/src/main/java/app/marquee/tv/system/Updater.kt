package app.marquee.tv.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import app.marquee.tv.MarqueeApp
import app.marquee.tv.net.Http
import app.marquee.tv.net.arr
import app.marquee.tv.net.get
import app.marquee.tv.net.long
import app.marquee.tv.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Marquee keeps itself up to date from your private GitHub repo's Releases.
 *
 * Every few hours it asks GitHub for the latest release (with your read-only token), downloads the APK,
 * checks it really is Marquee signed with the same key and newer than what's installed, then installs it
 * while the TV is idle (screen off or screensaver). Nothing installs while you're watching.
 */
object Updater {
    private val scope0 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    enum class Phase { NOT_SET_UP, IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val latest: String? = null, val notes: String = "", val message: String = "", val checkedAt: Long = 0)

    val state = MutableStateFlow(State())
        .also { f -> scope0.launch { f.collect { st -> android.util.Log.i("MarqueeUpdate", "${st.phase}: ${st.message}") } } }
    private var ready: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start(app: MarqueeApp) {
        if (started) return
        started = true
        // Install a waiting update as soon as the TV goes idle.
        val idle = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (state.value.phase == Phase.READY && app.store.value.autoUpdate) install(app) }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_DREAMING_STARTED) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(idle, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(idle, f)
        scope.launch {
            delay(90_000)
            while (true) {
                if (app.store.value.autoUpdate) runCatching { check(app, download = true) }
                delay(6 * 60 * 60_000L)
            }
        }
    }

    fun currentVersion(ctx: Context): Pair<String, Long> {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return (p.versionName ?: "0") to p.longVersionCode
    }

    /** Asks GitHub for the newest release; optionally downloads it and installs right away if the TV is idle. */
    suspend fun check(app: MarqueeApp, download: Boolean = true) {
        val s = app.store.value
        if (s.updateRepo.isBlank()) { state.value = State(Phase.NOT_SET_UP, message = "Add the GitHub repo and token on the phone setup page"); return }
        if (state.value.phase == Phase.DOWNLOADING || state.value.phase == Phase.INSTALLING) return
        state.value = state.value.copy(phase = Phase.CHECKING, message = "Checking GitHub…")
        try {
            val headers = buildMap {
                put("Accept", "application/vnd.github+json")
                put("X-GitHub-Api-Version", "2022-11-28")
                if (s.updateToken.isNotBlank()) put("Authorization", "Bearer ${s.updateToken}")
            }
            val body = Http.getText("https://api.github.com/repos/${s.updateRepo}/releases/latest", headers, cacheMinutes = 0)
            val j = Json.parseToJsonElement(body)
            val tag = j["tag_name"].str ?: error("No releases yet")
            val notes = (j["body"].str ?: "").take(600)
            val asset = j["assets"].arr.firstOrNull { (it["name"].str ?: "").endsWith(".apk") } ?: error("The latest release has no APK")
            val latest = tag.removePrefix("v")
            val (cur, _) = currentVersion(app)
            if (!newer(latest, cur)) {
                state.value = State(Phase.UP_TO_DATE, latest, notes, "You're on the latest version ($cur)", System.currentTimeMillis())
                return
            }
            state.value = State(Phase.IDLE, latest, notes, "Version $latest is available", System.currentTimeMillis())
            if (!download) return
            state.value = state.value.copy(phase = Phase.DOWNLOADING, message = "Downloading $latest…")
            val dir = File(app.filesDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, "marquee-$latest.apk")
            val req = okhttp3.Request.Builder().url("https://api.github.com/repos/${s.updateRepo}/releases/assets/${asset["id"].long}")
                .header("Accept", "application/octet-stream").apply { if (s.updateToken.isNotBlank()) header("Authorization", "Bearer ${s.updateToken}") }.build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
                file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            verify(app, file)
            ready = file
            state.value = state.value.copy(phase = Phase.READY, message = "Version $latest is ready. It installs when the TV is idle.")
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive && s.autoUpdate) install(app)
        } catch (e: Exception) {
            state.value = state.value.copy(phase = Phase.ERROR, message = friendly(e), checkedAt = System.currentTimeMillis())
        }
    }

    /** Only ever installs Marquee itself, signed with the same key, and newer than what's here. */
    private fun verify(ctx: Context, file: File) {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("The download isn't a valid app")
        if (info.packageName != ctx.packageName) error("The download isn't Marquee")
        if (info.longVersionCode <= currentVersion(ctx).second) error("The download isn't newer than this version")
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        val theirs = info.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        if (mine == null || theirs == null || mine != theirs) error("The download isn't signed by you, so it wasn't installed")
    }

    fun install(app: MarqueeApp) {
        val file = ready ?: return
        if (state.value.phase == Phase.INSTALLING) return
        state.value = state.value.copy(phase = Phase.INSTALLING, message = "Installing…")
        scope.launch {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    // Marquee installed itself before, so Android lets it update itself without a prompt.
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("marquee.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                state.value = state.value.copy(phase = Phase.ERROR, message = "Couldn't install: ${e.message}")
            }
        }
    }

    internal fun onResult(ctx: Context, status: Int, msg: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            PackageInstaller.STATUS_SUCCESS -> state.value = state.value.copy(phase = Phase.UP_TO_DATE, message = "Updated")
            else -> state.value = state.value.copy(phase = Phase.ERROR, message = "Install failed: ${msg ?: status}")
        }
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d > 0 }
        return false
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("401") || m.contains("403") -> "GitHub refused the token. Check it on the phone setup page."
            m.contains("404") -> "Repo or release not found. Check the repo name and that the token can read it."
            else -> m
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        @Suppress("DEPRECATION")
        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        Updater.onResult(c, i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999), i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}

/**
 * After an update (or a reboot), make sure the Home-button service is still switched on. Android can switch it
 * off when an app is replaced; with the "write secure settings" permission (granted once over ADB) Marquee
 * switches it back on by itself.
 */
object SelfHeal {
    private const val SERVICE = "app.marquee.tv/app.marquee.tv.system.MarqueeAccessibilityService"

    fun ensureAccessibility(ctx: Context) {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val cr = ctx.contentResolver
            val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (cur.split(':').none { it.equals(SERVICE, true) }) {
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, listOf(cur, SERVICE).filter { it.isNotBlank() }.joinToString(":"))
            }
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }
}

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) SelfHeal.ensureAccessibility(c)
    }
}
