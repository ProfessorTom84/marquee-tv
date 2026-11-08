package app.marquee.tv.system

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The built-in performance check (Settings → Performance check): records how long every frame takes while you
 * scroll the home screen for [SECONDS] seconds, then explains where the time went, the same numbers
 * `adb shell dumpsys gfxinfo` gives but without a computer.
 *
 * - App code (input, animation, layout, recording the drawing): long frames here are rows being built.
 * - Drawing (the render thread handing work to the graphics chip) and the graphics chip itself.
 * - Whether Android has optimised Marquee since its last update.
 */
object PerfCheck {
    const val SECONDS = 20

    sealed interface Phase {
        data object Idle : Phase
        data class Running(val endsAt: Long) : Phase
        data class Done(val result: Result) : Phase
    }

    data class Result(
        val at: Long, val version: String, val frames: Int, val latePct: Int,
        val p50: Double, val p90: Double, val p99: Double,
        val slowApp: Int, val slowDrawing: Int, val gpuP50: Double, val gpuP90: Double,
        val optimised: String, val verdict: String,
    ) {
        /** Label/value pairs, for the TV and the phone page. */
        fun lines(): List<Pair<String, String>> = listOf(
            "Verdict" to verdict,
            "Frames recorded" to "$frames in $SECONDS s",
            "Late frames" to "$latePct% (missed the 16.7 ms deadline)",
            "Frame time" to "typical ${ms(p50)} · slowest 10% ${ms(p90)} · worst 1% ${ms(p99)}",
            "Slow because of app code" to "$slowApp frames (building rows, layout)",
            "Slow because of drawing" to "$slowDrawing frames",
            "Graphics chip" to "typical ${ms(gpuP50)} · slowest 10% ${ms(gpuP90)}",
            "Optimised by Android" to optimised,
            "Marquee version" to version,
        )

        fun text() = lines().joinToString("\n") { (k, v) -> "$k: $v" } +
            "\nChecked: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
    }

    private fun ms(v: Double) = "%.0f ms".format(v)

    val phase = MutableStateFlow<Phase>(Phase.Idle)

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var window: Window? = null

    // Per-frame samples, in nanoseconds (written on the metrics thread, read after it stops).
    private val total = ArrayList<Long>(2000)
    private val app = ArrayList<Long>(2000)
    private val drawing = ArrayList<Long>(2000)
    private val gpu = ArrayList<Long>(2000)
    private var late = 0

    fun start(activity: Activity) {
        if (phase.value is Phase.Running) return
        stopListening()
        synchronized(this) { total.clear(); app.clear(); drawing.clear(); gpu.clear(); late = 0 }
        val t = HandlerThread("perf-check").apply { start() }
        thread = t
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val tot = m.getMetric(FrameMetrics.TOTAL_DURATION)
            val a = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
            val d = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val g = m.getMetric(FrameMetrics.GPU_DURATION)
            val deadline = m.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: 16_666_667L
            synchronized(this) {
                total += tot; app += a; drawing += d; if (g > 0) gpu += g
                if (tot > deadline) late++
            }
        }
        listener = l
        window = activity.window
        activity.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
        phase.value = Phase.Running(System.currentTimeMillis() + SECONDS * 1000L)
        main.postDelayed({ finish(activity.applicationContext) }, SECONDS * 1000L)
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        stopListening()
        phase.value = Phase.Idle
    }

    private fun stopListening() {
        listener?.let { l -> runCatching { window?.removeOnFrameMetricsAvailableListener(l) } }
        listener = null; window = null
        thread?.quitSafely(); thread = null
    }

    private fun finish(ctx: Context) {
        stopListening()
        Thread {
            val r = synchronized(this) { summarise(ctx) }
            save(ctx, r)
            phase.value = Phase.Done(r)
        }.start()
    }

    private fun pct(xs: List<Long>, p: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[((s.size - 1) * p).toInt()] / 1e6
    }

    private fun summarise(ctx: Context): Result {
        val budget = 16_666_667L
        val slowApp = app.count { it > budget }
        // The render thread overlaps the next frame's app work, so it only causes trouble well past ~12 ms.
        val slowDrawing = drawing.count { it > 12_000_000L }
        val optimised = compilationStatus(ctx)
        val n = total.size
        val latePct = if (n == 0) 0 else late * 100 / n
        val p90 = pct(total, 0.9)
        val verdict = when {
            n < 100 -> "Not enough scrolling to judge. Start again and keep scrolling up and down for the whole $SECONDS seconds."
            latePct <= 5 && p90 <= 20 -> "Smooth."
            slowApp >= slowDrawing -> "Stutters while building rows (app code)." +
                if (optimised.startsWith("No")) " Android hasn't optimised Marquee since its last update yet; it does that overnight, so check again tomorrow." else ""
            else -> "Drawing is the bottleneck: the screen has more to draw each frame than the TV's graphics can keep up with."
        }
        return Result(
            System.currentTimeMillis(), Updater.currentVersion(ctx).first, n, latePct,
            pct(total, 0.5), p90, pct(total, 0.99), slowApp, slowDrawing, pct(gpu, 0.5), pct(gpu, 0.9), optimised, verdict,
        )
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
