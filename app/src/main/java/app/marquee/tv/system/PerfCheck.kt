package app.marquee.tv.system

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The built-in performance check (Settings → Performance check). It records how long every frame takes on the
 * home screen, in steps, so it can say where the time goes without a computer:
 *
 *  1. Hands off (nothing pressed): how often the screen redraws on its own (the animated sky) and what that costs.
 *  2. Scrolling, as normal.
 *  3. Scrolling with the animated sky switched off (a plain dark background).
 *  4. Scrolling with the sky off and plain cards (artwork and title only: no shade, badges or logos).
 *
 * Differences between steps 2–4 are what the sky and the card effects cost the TV's graphics chip. It also notes
 * the device, its temperature state, memory, and what the home screen was showing.
 */
object PerfCheck {
    data class Step(val name: String, val seconds: Int, val lite: Int, val scroll: Boolean)

    val steps = listOf(
        Step("Hands off", 6, 0, scroll = false),
        Step("Scrolling, as normal", 10, 0, scroll = true),
        Step("Scrolling, sky switched off", 10, 1, scroll = true),
        Step("Scrolling, sky off and plain cards", 10, 2, scroll = true),
    )
    val SECONDS get() = steps.sumOf { it.seconds }
    private const val SETTLE_MS = 600L // after each switch, before frames count

    /** 0 = normal, 1 = no sky, 2 = no sky and plain cards. Compose reads this, so screens switch as steps change. */
    var liteLevel by mutableIntStateOf(0)
        private set

    sealed interface Phase {
        data object Idle : Phase
        data class Running(val step: Int, val stepEndsAt: Long) : Phase
        data class Done(val result: Result) : Phase
    }

    data class StepResult(
        val name: String, val frames: Int, val fps: Double, val latePct: Int,
        val gpuP50: Double, val gpuP90: Double, val appP50: Double, val drawP50: Double,
        val slowApp: Int, val slowDrawing: Int, val worst: Double,
    )

    data class Result(
        val at: Long, val version: String, val optimised: String, val verdict: String,
        val steps: List<StepResult>, val context: List<Pair<String, String>>,
    ) {
        fun lines(): List<Pair<String, String>> = listOf("Verdict" to verdict) +
            steps.mapIndexed { i, s ->
                "${i + 1}. ${s.name}" to ("%.0f fps · %d%% late · worst %s · graphics chip %s typical, %s slowest 10%% · " +
                    "app code %s typical (%d slow) · drawing %s typical (%d slow)")
                    .format(s.fps, s.latePct, ms(s.worst), ms(s.gpuP50), ms(s.gpuP90), ms(s.appP50), s.slowApp, ms(s.drawP50), s.slowDrawing)
            } +
            listOf("Optimised by Android" to optimised, "Marquee version" to version) + context

        fun text() = lines().joinToString("\n") { (k, v) -> "$k: $v" } +
            "\nChecked: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
    }

    private fun ms(v: Double) = "%.1f ms".format(v)

    val phase = MutableStateFlow<Phase>(Phase.Idle)

    private val main by lazy { Handler(Looper.getMainLooper()) } // lazy: unit tests load this object without Android
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var window: Window? = null

    private class Samples {
        val total = ArrayList<Long>(1200); val app = ArrayList<Long>(1200); val drawing = ArrayList<Long>(1200); val gpu = ArrayList<Long>(1200)
        var late = 0
    }
    private var samples = List(steps.size) { Samples() }
    @Volatile private var recording = -1 // which step frames are counted for (-1 = settling)
    private var describe: () -> List<Pair<String, String>> = { emptyList() }
    private var thermalAtStart = "?"

    /** [describeHome] adds what the home screen was showing (style, background, weather, rows). */
    fun start(activity: Activity, describeHome: () -> List<Pair<String, String>> = { emptyList() }) {
        if (phase.value is Phase.Running) return
        stopListening()
        describe = describeHome
        thermalAtStart = thermal(activity)
        synchronized(this) { samples = List(steps.size) { Samples() } }
        val t = HandlerThread("perf-check").apply { start() }
        thread = t
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val step = recording
            if (step < 0 || m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val tot = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val a = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
            val d = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val g = m.getMetric(FrameMetrics.GPU_DURATION)
            val deadline = m.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: 16_666_667L
            synchronized(this) {
                val s = samples[step]
                s.total += tot; s.app += a; s.drawing += d; if (g > 0) s.gpu += g
                if (tot > deadline) s.late++
            }
        }
        listener = l
        window = activity.window
        activity.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
        val ctx = activity.applicationContext
        var at = 0L
        steps.forEachIndexed { i, step ->
            val begin = at
            main.postDelayed({
                liteLevel = step.lite
                recording = -1
                phase.value = Phase.Running(i, System.currentTimeMillis() + step.seconds * 1000L)
                main.postDelayed({ recording = i }, SETTLE_MS)
            }, begin)
            at += step.seconds * 1000L
        }
        main.postDelayed({ finish(ctx) }, at)
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        stopListening()
        liteLevel = 0
        phase.value = Phase.Idle
    }

    private fun stopListening() {
        recording = -1
        listener?.let { l -> runCatching { window?.removeOnFrameMetricsAvailableListener(l) } }
        listener = null; window = null
        thread?.quitSafely(); thread = null
    }

    private fun finish(ctx: Context) {
        stopListening()
        liteLevel = 0
        val home = runCatching { describe() }.getOrDefault(emptyList()) // read on the main thread
        Thread {
            val r = synchronized(this) { summarise(ctx, home) }
            save(ctx, r)
            phase.value = Phase.Done(r)
        }.start()
    }

    private fun pct(xs: List<Long>, p: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[((s.size - 1) * p).toInt()] / 1e6
    }

    private fun summarise(ctx: Context, home: List<Pair<String, String>>): Result {
        val budget = 16_666_667L
        val stepResults = samples.mapIndexed { i, s ->
            val n = s.total.size
            StepResult(
                steps[i].name, n, n / (steps[i].seconds - SETTLE_MS / 1000.0), if (n == 0) 0 else s.late * 100 / n,
                pct(s.gpu, 0.5), pct(s.gpu, 0.9), pct(s.app, 0.5), pct(s.drawing, 0.5),
                s.app.count { it > budget },
                s.drawing.count { it > 12_000_000L }, // the render thread overlaps the next frame's work, so ~12 ms is its limit
                (s.total.maxOrNull() ?: 0L) / 1e6,
            )
        }
        val optimised = compilationStatus(ctx)
        return Result(
            System.currentTimeMillis(), Updater.currentVersion(ctx).first, optimised, verdict(stepResults, optimised), stepResults,
            home + device(ctx),
        )
    }

    /** Plain-English reading of the steps (hands off, normal, no sky, plain cards). Public for tests. */
    fun verdict(s: List<StepResult>, optimised: String): String {
        if (s.size < 4 || s.drop(1).any { it.frames < 50 }) return "Not enough scrolling to judge. Start again and keep scrolling up and down until the check says it's done."
        val (idle, normal, noSky, plain) = s
        val out = mutableListOf<String>()
        if (normal.latePct <= 5 && normal.gpuP50 <= 14) out += "Scrolling is smooth"
        else {
            val sky = normal.gpuP50 - noSky.gpuP50
            val cards = noSky.gpuP50 - plain.gpuP50
            if (sky >= 2) out += "the animated sky costs about %.0f ms of graphics per frame".format(sky)
            if (cards >= 2) out += "the card effects cost about %.0f ms".format(cards)
            if (plain.gpuP50 > 14) out += "even with both off the graphics chip needs %.0f ms (16.7 ms is the limit), so the rest of the screen is heavy too".format(plain.gpuP50)
            if (out.isEmpty()) out += "the graphics chip is busy, but not because of the sky or the card effects"
            if (normal.slowApp > normal.frames / 10) out += "building rows also causes stutter" +
                if (optimised.startsWith("No")) ", partly because Android hasn't optimised Marquee since its last update yet" else ""
        }
        if (idle.fps >= 5) out += "while nothing is pressed the screen still redraws %.0f times a second (the animated sky), %.0f ms of graphics each time".format(idle.fps, idle.gpuP50)
        return out.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }

    // ---------------- Context: the device and the app's state ----------------

    private fun thermal(ctx: Context): String = runCatching {
        when ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm (light throttling)"
            PowerManager.THERMAL_STATUS_MODERATE -> "Hot (moderate throttling)"
            PowerManager.THERMAL_STATUS_SEVERE -> "Very hot (severe throttling)"
            else -> "Critical"
        }
    }.getOrDefault("Unknown")

    private fun device(ctx: Context): List<Pair<String, String>> = buildList {
        add("Device" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        runCatching {
            val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = d.mode
            val dm = ctx.resources.displayMetrics
            add("Screen" to "${mode.physicalWidth}×${mode.physicalHeight} at %.0f Hz · app draws at ${dm.widthPixels}×${dm.heightPixels}, density %.2f".format(mode.refreshRate, dm.density))
        }
        add("Temperature" to "at start ${thermalAtStart}, at end ${thermal(ctx)}")
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            add("Device memory" to "%d MB total, %d MB free%s · ${Runtime.getRuntime().availableProcessors()} processor cores"
                .format(mi.totalMem shr 20, mi.availMem shr 20, if (mi.lowMemory) " (low!)" else ""))
        }
        runCatching {
            val m = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            fun mb(k: String) = (m.getMemoryStat(k)?.toLongOrNull() ?: 0L) / 1024
            add("Marquee's memory" to "${mb("summary.total-pss")} MB in all · graphics ${mb("summary.graphics")} MB · Java ${mb("summary.java-heap")} MB · native ${mb("summary.native-heap")} MB")
        }
    }

    /** Whether ART has compiled Marquee with its profile (the difference between choppy and smooth after an update). */
    private fun compilationStatus(ctx: Context): String = runCatching {
        val st = androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync().get(2, java.util.concurrent.TimeUnit.SECONDS)
        when {
            st.isCompiledWithProfile -> "Yes"
            st.hasProfileEnqueuedForCompilation() -> "No, queued for tonight"
            else -> "No"
        }
    }.getOrDefault("Unknown")

    // ---- The last result, kept for the Settings page and the phone setup page ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE)

    private fun save(ctx: Context, r: Result) = prefs(ctx).edit().putString("last", r.text()).apply()

    fun lastText(ctx: Context): String? = prefs(ctx).getString("last", null)
}
