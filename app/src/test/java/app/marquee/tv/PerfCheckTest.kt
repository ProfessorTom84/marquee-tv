package app.marquee.tv

import app.marquee.tv.system.PerfCheck
import app.marquee.tv.system.PerfCheck.StepResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfCheckTest {
    private fun step(gpu: Double, late: Int = 30, frames: Int = 300, fps: Double = 30.0, slowApp: Int = 5) =
        StepResult("s", frames, fps, late, gpu, gpu + 4, 4.0, 10.0, slowApp, 100, 80.0)

    @Test fun namesTheSkyAndTheCardsWhenTheyCostTheMost() {
        val v = PerfCheck.verdict(listOf(step(9.0, fps = 25.0), step(19.0), step(12.0), step(8.0)), "Yes")
        assertTrue(v, v.contains("sky costs about 7 ms"))
        assertTrue(v, v.contains("card effects cost about 4 ms"))
        assertFalse(v, v.contains("even with both off"))
        assertTrue(v, v.contains("redraws 25 times a second"))
    }

    @Test fun saysWhenTheRestOfTheScreenIsHeavyToo() {
        val v = PerfCheck.verdict(listOf(step(9.0, fps = 0.0), step(19.0), step(18.5), step(18.0)), "Yes")
        assertTrue(v, v.contains("ven with both off the graphics chip needs 18 ms"))
        assertFalse(v, v.contains("redraws"))
    }

    @Test fun smoothAndNotOptimised() {
        val smooth = PerfCheck.verdict(listOf(step(5.0, fps = 0.0), step(9.0, late = 2), step(8.0, late = 1), step(7.0, late = 1)), "Yes")
        assertTrue(smooth, smooth.startsWith("Scrolling is smooth"))
        val stutter = PerfCheck.verdict(listOf(step(5.0, fps = 0.0), step(15.0, slowApp = 80), step(14.0), step(13.0)), "No, queued for tonight")
        assertTrue(stutter, stutter.contains("building rows also causes stutter, partly because Android hasn't optimised"))
    }

    @Test fun asksAgainWhenThereWasntEnoughScrolling() {
        val v = PerfCheck.verdict(listOf(step(5.0), step(19.0, frames = 10), step(12.0), step(8.0)), "Yes")
        assertTrue(v, v.startsWith("Not enough scrolling"))
    }
}
