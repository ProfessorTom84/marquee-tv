package app.marquee.tv.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.marquee.tv.MainActivity
import app.marquee.tv.MarqueeApp
import app.marquee.tv.data.Providers
import app.marquee.tv.setup.SetupServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three jobs, no screen reading:
 *  1. When the stock Google TV home screen comes up, bring Marquee forward instead.
 *  2. When the stock YouTube app opens, open the preferred (ad-free) YouTube app instead.
 *  3. Show time-to-leave alerts on top of whatever is playing.
 */
class MarqueeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: View? = null
    private var lastHomeLaunch = 0L
    private val app get() = application as MarqueeApp

    // ---- Back on the home screen starts the real Google TV screensaver ----
    // Android only lets system apps start the screensaver directly, so Marquee hands over to the Google TV
    // home screen for a moment, presses Back there (which starts its screensaver), and takes over again
    // when the screensaver ends. If the screensaver doesn't start, Marquee shows its own Ambient mode.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var handoffUntil = 0L
    private var stage = 0           // 0 idle, 1 waiting for the Google home screen, 2+ Back sent
    private var ourDream = false
    private val dreamReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_DREAMING_STARTED -> if (stage > 0) { ourDream = true; stage = 0; handler.removeCallbacksAndMessages(null) }
                Intent.ACTION_DREAMING_STOPPED -> if (ourDream) {
                    ourDream = false; handoffUntil = 0L
                    lastHomeLaunch = SystemClock.elapsedRealtime()
                    startActivity(Intent(this@MarqueeAccessibilityService, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
        }
    }

    fun startGoogleScreensaver(): Boolean {
        val launcher = Apps.stockLaunchers.firstOrNull { app.apps.isInstalled(it) } ?: return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(launcher)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        handler.removeCallbacksAndMessages(null)
        handoffUntil = SystemClock.elapsedRealtime() + 7000
        stage = 1
        if (runCatching { startActivity(home) }.isFailure) { stage = 0; handoffUntil = 0; return false }
        // Safety net: whatever happens, never leave you on the Google home screen.
        handler.postDelayed({ if (stage > 0 && !ourDream) fallbackToAmbient() }, 6500)
        return true
    }

    private fun pressBackThere(attempt: Int) {
        if (stage == 0 || ourDream) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        stage = 2 + attempt
        // The Google home screen may first move focus up to its top row; a second Back then starts the screensaver.
        if (attempt < 2) handler.postDelayed({ pressBackThere(attempt + 1) }, 1300)
    }

    private fun fallbackToAmbient() {
        stage = 0; handoffUntil = 0L
        lastHomeLaunch = SystemClock.elapsedRealtime()
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AMBIENT, true).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        runCatching {
            val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED) }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dreamReceiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(dreamReceiver, f)
        }
        scope.launch {
            while (isActive) {
                runCatching { checkAlerts() }
                delay(60_000)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val s = app.store.value
        if (pkg in Apps.stockLaunchers && SystemClock.elapsedRealtime() < handoffUntil) {
            // We handed over on purpose: press Back there once it's up, and don't take over.
            if (stage == 1) { stage = 2; handler.postDelayed({ pressBackThere(0) }, 700) }
            return
        }
        when {
            s.takeoverHome && pkg in Apps.stockLaunchers -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHomeLaunch < 800) return
                lastHomeLaunch = now
                startActivity(Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
            s.redirectPlex && pkg == Providers.PLEX_PACKAGE -> {
                if (SystemClock.elapsedRealtime() < allowPlexUntil) return
                val player = app.apps.plexPlayerPackage(s)
                if (player != Providers.PLEX_PACKAGE && app.apps.isInstalled(player)) app.apps.launch(player)
            }
            s.redirectYouTube && pkg == Providers.YOUTUBE_PACKAGE -> {
                if (SystemClock.elapsedRealtime() < allowYouTubeUntil) return
                val alt = app.apps.preferredYouTube(s) ?: return
                if (alt != Providers.YOUTUBE_PACKAGE) app.apps.launch(alt)
            }
        }
    }

    private var homeDownAt = 0L

    /**
     * Catches the Home button itself, before Google TV acts on it, so Marquee opens instantly with no
     * flash of the stock home screen. Holding Home opens Google TV's settings instead.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !app.store.value.takeoverHome) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) homeDownAt = event.eventTime
            KeyEvent.ACTION_UP -> {
                if (event.eventTime - homeDownAt > 700) app.apps.openSystemSettings()
                else {
                    lastHomeLaunch = SystemClock.elapsedRealtime()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
        }
        return true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        runCatching { unregisterReceiver(dreamReceiver) }
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun checkAlerts() {
        val engine = app.alerts
        val u = withContext(Dispatchers.IO) { engine.upNext() } ?: return
        val kind = engine.alertFor(u) ?: return
        if (overlay == null) showOverlay(u, kind)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay(u: UpNext, kind: AlertKind) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val engine = app.alerts
        val ev = u.event
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(232, 14, 16, 30))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
        }
        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val amber = Color.rgb(245, 181, 74)
        val leaveIn = u.minutesUntilLeave()
        card.addView(text(
            if (kind == AlertKind.LEAVE) (if ((leaveIn ?: 0) <= 0) "TIME TO LEAVE" else "LEAVE IN $leaveIn MINUTES") else "STARTING ${TimeFmt.until(u.minutesUntilStart()).uppercase()}",
            12f, amber, true))
        card.addView(text("${ev.title} · ${TimeFmt.full(ev.start)}", 20f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, dp(10)) })
        u.drive?.let { d ->
            card.addView(text("${d.minutes} min drive now · ${if (d.delayMinutes > 0) "+${d.delayMinutes} min traffic" else d.trafficLabel} · arrive about ${TimeFmt.full(java.time.ZonedDateTime.now().plusMinutes(d.minutes.toLong()))}",
                14f, Color.rgb(231, 233, 241)))
        }
        if (ev.location.isNotBlank()) card.addView(text(ev.location, 13f, Color.rgb(183, 189, 207)).apply { setPadding(0, dp(4), 0, 0) })

        if (ev.needsTravel) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
            row.addView(ImageView(this).apply { setImageBitmap(SetupServer.qr(app.traffic.mapsUrl(ev.location), 240)); layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)) })
            row.addView(text("Scan for directions on your phone", 13f, Color.rgb(183, 189, 207)).apply { setPadding(dp(14), 0, 0, 0) })
            card.addView(row)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 14f
            setTextColor(if (primary) Color.rgb(16, 19, 31) else Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (primary) Color.rgb(244, 241, 234) else Color.argb(40, 255, 255, 255)) }
            setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.06f else 1f; v.scaleY = v.scaleX; (v.background as GradientDrawable).setStroke(if (f) dp(3) else 0, amber) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(10) }
            setPadding(dp(18), 0, dp(18), 0)
        }
        val pause = button("Pause & dismiss", true) { pausePlayback(); engine.dismiss(u); removeOverlay() }
        buttons.addView(pause)
        buttons.addView(button("Remind in 5 min", false) { engine.snooze(u, 5); removeOverlay() })
        buttons.addView(button("Dismiss", false) { engine.dismiss(u); removeOverlay() })
        card.addView(buttons)
        card.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { engine.snooze(u, 5); removeOverlay(); true } else false }

        val lp = WindowManager.LayoutParams(
            dp(520), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(40); y = dp(40) }
        runCatching { wm.addView(card, lp); overlay = card; pause.requestFocus() }
        scope.launch { delay(90_000); if (overlay === card) { engine.snooze(u, 5); removeOverlay() } }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
    }

    private fun pausePlayback() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    companion object {
        @Volatile var running = false; private set
        /** Set by Marquee when the user deliberately opens stock YouTube from the app drawer. */
        @Volatile var allowYouTubeUntil = 0L
        @Volatile var allowPlexUntil = 0L
        @Volatile var instance: MarqueeAccessibilityService? = null; private set
    }
}
