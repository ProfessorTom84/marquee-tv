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
        val v = PerfCheck.verdict(listOf(step(9.0, fps = 25.0), step(9.0, fps = 25.0), step(19.0), step(12.0), step(8.0)), "Yes")
        assertTrue(v, v.contains("sky costs about 7 ms"))
        assertTrue(v, v.contains("card effects cost about 4 ms"))
        assertFalse(v, v.contains("even with both off"))
        assertTrue(v, v.contains("redraws 25 times a second"))
    }

    @Test fun saysWhenTheRestOfTheScreenIsHeavyToo() {
        val v = PerfCheck.verdict(listOf(step(9.0, fps = 0.0), step(9.0, fps = 0.0), step(19.0), step(18.5), step(18.0)), "Yes")
        assertTrue(v, v.contains("ven with both off the graphics chip needs 18 ms"))
        assertFalse(v, v.contains("redraws"))
    }

    @Test fun smoothAndNotOptimised() {
        val smooth = PerfCheck.verdict(listOf(step(5.0, fps = 0.0), step(5.0, fps = 0.0), step(9.0, late = 2), step(8.0, late = 1), step(7.0, late = 1)), "Yes")
        assertTrue(smooth, smooth.startsWith("Scrolling is smooth"))
        val stutter = PerfCheck.verdict(listOf(step(5.0, fps = 0.0), step(5.0, fps = 0.0), step(15.0, slowApp = 80), step(14.0), step(13.0)), "No, queued for tonight")
        assertTrue(stutter, stutter.contains("building rows also causes stutter, partly because Android hasn't optimised"))
    }

    @Test fun asksAgainWhenThereWasntEnoughScrolling() {
        val v = PerfCheck.verdict(listOf(step(5.0), step(5.0), step(19.0, frames = 10), step(12.0), step(8.0)), "Yes")
        assertTrue(v, v.startsWith("Not enough scrolling"))
    }

    /** The second step fills the sky with birds; the verdict says what that added to the hands-off step before it. */
    @Test fun saysWhatASkyFullOfBirdsCosts() {
        fun verdict(idle: StepResult, birds: StepResult) = PerfCheck.verdict(listOf(idle, birds, step(9.0, late = 2), step(8.0, late = 1), step(7.0, late = 1)), "Yes")
        val leafy = verdict(step(6.0, fps = 28.0), step(6.4, fps = 29.0))
        assertTrue(leafy, leafy.contains("with a sky full of birds it redraws 29 times a second, 6 ms each (no more than without them)"))
        val dearer = verdict(step(6.0, fps = 28.0), step(9.2, fps = 29.0))
        assertTrue(dearer, dearer.contains("(3 ms more than without them)"))
        val still = verdict(step(5.0, fps = 0.0), step(7.0, fps = 27.0))
        assertTrue(still, still.contains("with a sky full of birds it redraws 27 times a second, 7 ms each (they are all that is moving)"))
        assertFalse(still, still.contains("the animated sky"))
    }

    @Test fun onlyOneStepFillsTheSkyAndItIsHandsOff() {
        assertTrue(PerfCheck.steps.count { it.wildlife } == 1)
        assertTrue(PerfCheck.steps.single { it.wildlife }.let { !it.scroll && it.lite == 0 })
        assertTrue(PerfCheck.steps.size == 5 && PerfCheck.SECONDS == 42)
    }
}
