package app.marquee.tv

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.cash.paparazzi.Paparazzi
import app.marquee.tv.ui.components.Creature
import app.marquee.tv.ui.components.Season
import app.marquee.tv.ui.components.SeasonalLife
import app.marquee.tv.ui.components.Wildlife
import app.marquee.tv.weather.Sky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What drawing one frame of each creature costs the app's own thread, on Android's real drawing classes (the ones
 * the TV runs, here on a computer): the time to work out where everything is and hand over the drawing
 * instructions, and the memory thrown away doing it. The falling leaves, which the sky draws all autumn anyway, are
 * measured alongside as the yardstick. The times depend on the machine, so they are only reported
 * (build/wildlife-cost.txt); what is checked is that drawing leaves no garbage behind, frame after frame, because
 * tidying that up is what makes a TV stutter.
 *
 * What the graphics chip then makes of those instructions can only be measured on the TV itself: Settings →
 * Performance check has a step for it.
 */
class WildlifeCostTest {
    // Only for its Android drawing classes: no picture is taken. Set up as the screenshot tests are.
    @get:Rule val paparazzi = Paparazzi(environment = app.cash.paparazzi.detectEnvironment().copy(compileSdkVersion = 34))

    private val width = 1920
    private val height = 1080
    private val tone = Color(0xFF4A7FBF)

    // The test machine's own count of the memory a thread has been handed. Android has no such class, hence by name.
    private val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val counter = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun allocated() = counter.invoke(threads, Thread.currentThread().id) as Long

    private class Cost(val name: String, val micros: Double, val bytes: Double)

    /**
     * An average frame of [draw] between [from] and [to] seconds, recorded the way the app records one: into a
     * list of drawing instructions for the graphics chip. Several frames go into each list so that the list's own
     * setting-up isn't counted.
     */
    private inline fun measure(name: String, from: Float, to: Float, draw: DrawScope.(Float) -> Unit): Cost {
        val node = android.graphics.RenderNode("wildlife").apply { setPosition(0, 0, width, height) }
        val scope = CanvasDrawScope(); val size = Size(width.toFloat(), height.toFloat()); val density = Density(2f)
        val lists = 24; val each = 25
        var nanos = 0L; var bytes = 0L
        for (list in 0 until lists) {
            val canvas = Canvas(node.beginRecording())
            scope.draw(density, LayoutDirection.Ltr, canvas, size) {
                val b = allocated(); val n = System.nanoTime()
                for (i in 0 until each) draw(from + (to - from) * (list * each + i) / (lists * each))
                // The first lists only let the machine settle into its stride.
                if (list >= lists / 2) { nanos += System.nanoTime() - n; bytes += allocated() - b }
            }
            node.endRecording()
        }
        val frames = (lists - lists / 2) * each
        return Cost(name, nanos / 1000.0 / frames, bytes.toDouble() / frames)
    }

    @Test fun drawingASightingLeavesNoGarbage() {
        val leaves = SeasonalLife.pick(Season.AUTUMN, Sky.CLEAR, 0f)
        // Each creature at its busiest: the most members a sighting can have, through the middle of its crossing.
        val busiest = Creature.entries.map { c -> (0 until 200).map { Wildlife.of(c, it, Season.AUTUMN) }.maxBy { it.count } }
        val parade = Wildlife.parade(Season.AUTUMN)
        var costs = emptyList<Cost>()
        repeat(2) { // twice, keeping the second: by then nothing is being done for the first time
            costs = listOf(measure("falling leaves", 40f, 70f) { t -> leaves.draw(this, t, 0.25f, false, 0f) }) +
                busiest.map { v -> measure(v.creature.name.lowercase().replace('_', ' '), v.duration * 0.3f, v.duration * 0.7f) { t -> v.draw(this, t, tone) } } +
                measure("the performance check's sky full of birds", 0f, 6f) { t -> for (k in parade.indices) parade[k].first.draw(this, parade[k].second + t, tone) }
        }
        val report = costs.joinToString("\n") { "%-44s %7.1f µs  %5.0f bytes".format(it.name, it.micros, it.bytes) }
        println("Each frame, on the app's own thread:\n$report")
        java.io.File("build").mkdirs(); java.io.File("build/wildlife-cost.txt").writeText(report + "\n")

        assertEquals(Creature.entries.size + 2, costs.size)
        for (c in costs.drop(1)) assertTrue("${c.name}: ${c.bytes.toInt()} bytes thrown away every frame", c.bytes < 64)
    }
}
