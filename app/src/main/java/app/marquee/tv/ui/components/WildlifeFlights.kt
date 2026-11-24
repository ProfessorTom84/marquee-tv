package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/*
 * How the birds fly. Each kind moves the way that kind does, at something like its real pace.
 *
 * Small birds bound: a few quick wingbeats at the bottom of each bound, then wings shut and thrown over the top of
 * it like a ball, so the path is a run of long arches with short scoops between them, no two alike (see [Bounds]).
 * How deep the bounds are is the kind's own: a goldfinch dips deeply, a robin barely, a jay beats all the way.
 * Crows row steadily and now and then glide a moment. Geese fly the way they point, in a V seen from below in
 * true perspective. A hawk wheels on set wings, banked into its circle. An owl takes a few deep, slow beats and
 * then glides.
 */

private fun beats(kind: BirdKind, n: Int): List<WingPose> = List(n) { BirdPainter.beat(kind, it / n.toFloat()) }

/** One bird kind seen one way: its poses on a sheet, at [scale] pixels to a body length. */
private fun birdSheet(kind: BirdKind, aspect: Aspect, scale: Float, poses: List<WingPose>): Sheet {
    val painter = BirdPainter(kind, aspect, scale); val cell = (kind.reach * scale * 2f).toInt() + 6
    return Sheet(poses.size, cell, cell) { i -> with(painter) { paint(poses[i]) } }
}

/**
 * A small bird's bounding flight, bound after bound for [seconds]. In each it beats its wings for [flap] of the
 * time, scooping upward, and is then thrown with them shut: up, over the top, and down into the next. Everything
 * here is in seconds: [height] and [surge] are multiplied by a speed to place the bird. A bound lasts between
 * [shortest] and [longest], and a longer one carries higher, as it would.
 */
internal class Bounds(r: Random, seconds: Float, private val flap: Float, shortest: Float, longest: Float) {
    private val count: Int
    private val start: FloatArray      // when each bound begins; the last entry is when the last one ends
    private val base: FloatArray       // the height gained by the start of each bound
    private val gain: FloatArray       // and what this one adds or loses: flight is never quite level

    init {
        val starts = ArrayList<Float>()
        var t = -r.nextFloat() * longest   // it arrives part-way through a bound
        while (t < seconds + longest) { starts += t; t += r.between(shortest, longest) }
        starts += t
        count = starts.size - 1
        start = FloatArray(count + 1) { starts[it] }
        gain = FloatArray(count) { r.between(-0.3f, 0.3f) * (start[it + 1] - start[it]) / 4f }
        base = FloatArray(count + 1)
        for (k in 0 until count) base[k + 1] = base[k] + gain[k]
    }

    private fun bound(t: Float): Int { var k = 0; while (k < count - 1 && t >= start[k + 1]) k++; return k }

    /** Its height above its line at [t]. It is falling at one unit a second as each bound begins, and rising at one as its wings shut. */
    fun height(t: Float): Float {
        val k = bound(t); val len = start[k + 1] - start[k]; val s = (t - start[k]).coerceIn(0f, len)
        val beat = len * flap; val shut = len - beat
        return if (s < beat) base[k] + gain[k] * ramp(0f, beat, s) - s + s * s / beat     // scooping up under power
        else { val q = s - beat; base[k + 1] + q - q * q / shut }                           // thrown: over the top and down
    }

    /** Where its wings are at [t]: 0 to 1 through a beat while it is beating them, -1 while they are shut, -2 as they close or open. */
    fun wings(t: Float): Float {
        val k = bound(t); val len = start[k + 1] - start[k]; val s = (t - start[k]).coerceIn(0f, len); val beat = len * flap
        if (s < beat) { val n = max(2, (beat / BEAT + 0.5f).toInt()); return frac(s / beat * n) }
        return if (s < beat + 0.04f || s > len - 0.04f) -2f else -1f
    }

    /** How far ahead of a steady pace it is at [t]: quickest while it beats, slowest over the top. */
    fun surge(t: Float): Float {
        val k = bound(t); val len = start[k + 1] - start[k]; val s = (t - start[k]).coerceIn(0f, len)
        return 0.09f * len / TAU * (sin(TAU * (s / len - flap / 2f)) + sin(PI_F * flap))
    }

    private companion object { const val BEAT = 0.1f } // seconds for one wingbeat: too quick to follow, as a small bird's is
}

/**
 * How one kind of small bird flies: its size (pixels to a body length at 1080p), how many keep company, how deep
 * its bounds are (their height against the ground covered in one), how much of each it spends beating its wings
 * (1: all of it, a steady flier), how long a bound lasts, and about how many seconds it takes to cross.
 */
private class Style(val kind: BirdKind, val size: Float, val most: Int, val bounce: Float, val flap: Float, val shortest: Float, val longest: Float, val seconds: Float)

/** One to a few small birds of one kind, the kind the season brings: a cardinal against the snow, goldfinches in summer, a jay in autumn. */
internal class Songbirds(r: Random, season: Season) : Visit(Creature.SONGBIRDS) {
    private val style: Style
    private val n: Int
    private val len: FloatArray

    init {
        val roll = r.nextFloat()
        style = when (season) {
            Season.WINTER -> if (roll < 0.4f) CARDINAL else if (roll < 0.75f) CHICKADEE else if (roll < 0.9f) JAY else SPARROW
            Season.SPRING -> if (roll < 0.4f) ROBIN else if (roll < 0.65f) BLUEBIRD else if (roll < 0.8f) CARDINAL else if (roll < 0.9f) CHICKADEE else GOLDFINCH
            Season.SUMMER -> if (roll < 0.3f) GOLDFINCH else if (roll < 0.55f) ROBIN else if (roll < 0.75f) CARDINAL else if (roll < 0.9f) BLUEBIRD else JAY
            Season.AUTUMN -> if (roll < 0.3f) JAY else if (roll < 0.5f) CARDINAL else if (roll < 0.7f) ROBIN else if (roll < 0.9f) CHICKADEE else SPARROW
        }
        n = 1 + r.nextInt(style.most)
        len = FloatArray(n) { style.size * r.between(0.94f, 1.08f) }
    }

    private val delay = FloatArray(n) { if (it == 0) 0f else it * r.between(0.25f, 0.7f) }
    private val cross = FloatArray(n).also { val base = style.seconds * r.between(0.9f, 1.12f); for (i in 0 until n) it[i] = base * r.between(0.96f, 1.05f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-55f, 55f) }
    private val slant = FloatArray(n) { r.between(-45f, 45f) }       // none of them holds quite the same line
    private val steady = style.flap >= 1f
    private val bounds = if (steady) null else Array(n) { Bounds(r, cross[it], style.flap, style.shortest, style.longest) }
    private val row = FloatArray(n) { r.between(0.17f, 0.2f) }       // a steady flier's wingbeat, in seconds
    private val phase = FloatArray(n) { r.nextFloat() }
    private val wander = FloatArray(n * 2) { r.between(0f, TAU) }
    private val aspect = Aspect(r.between(0.1f, 0.3f) * r.side(), 0.3f)
    // It may be coming nearer as it crosses, or going away.
    private val near0 = r.between(0.74f, 1.12f); private val near1 = r.between(0.74f, 1.12f)
    private val longest = len.max() * max(near0, near1)
    override val reach = style.kind.reach * longest + 50f
    private val margin = reach + 60f
    private val course = course(r, 0.08f, 0.32f, margin, margin, bow = 0.07f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    private fun clock(i: Int, t: Float) = (t - delay[i]).coerceIn(0f, cross[i])

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val s = clock(i, t); val u = s / cross[i]
        val speed = (w + 2f * margin * sc) / cross[i]                 // pixels a second across this screen
        var ahead = 0f; val up: Float
        val b = bounds?.get(i)
        if (b != null) { ahead = b.surge(s); up = b.height(s) * 4f * style.bounce * speed }
        else up = 0.06f * len[i] * sc * cos(TAU * (frac(s / row[i] + phase[i]) - 0.3f))   // a steady flier lifts a little on each downstroke
        val stray = (10f * sin(0.7f * s + wander[i * 2]) + 5f * sin(1.7f * s + wander[i * 2 + 1])) * sc
        return Offset(course.x(u + ahead / cross[i], w, sc), course.y(u, h) + (lift[i] + slant[i] * (u - 0.5f)) * sc + stray - up)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(style.kind, aspect, longest * h / 1080f, beats(style.kind, BEATS) + BirdPainter.TUCKED)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.05f)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val next = place(i, t + 0.04f, w, h)
            // The body tips up as it climbs and down as it falls, a little less than its path does.
            val pitch = atan2(next.y - at.y, abs(next.x - at.x)) * DEG * 0.75f
            val s = clock(i, t)
            val b = bounds?.get(i)
            val wings = if (b != null) b.wings(s) else frac(s / row[i] + phase[i])
            val k = len[i] * mix(near0, near1, s / cross[i]) / longest
            withTransform({ translate(at.x, at.y); rotate(pitch * course.dir, Offset.Zero); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (wings >= 0f) pose(wings, BEATS) else if (wings < -1.5f) FOLDING else BEATS, alpha, filter)
            }
        }
    }

    private companion object {
        const val BEATS = 8        // poses through one wingbeat; the one after them is wings shut
        const val FOLDING = 6      // the pose, late in the upstroke, that serves for wings closing or opening
        val CARDINAL = Style(Birds.CARDINAL, 60f, 2, 0.055f, 0.5f, 0.5f, 0.68f, 4.6f)
        val JAY = Style(Birds.BLUE_JAY, 68f, 2, 0f, 1f, 0f, 0f, 5.4f)
        val ROBIN = Style(Birds.ROBIN, 64f, 2, 0.034f, 0.62f, 0.55f, 0.75f, 4.8f)
        val GOLDFINCH = Style(Birds.GOLDFINCH, 42f, 4, 0.1f, 0.36f, 0.5f, 0.68f, 4f)
        val CHICKADEE = Style(Birds.CHICKADEE, 42f, 4, 0.075f, 0.42f, 0.42f, 0.58f, 4.2f)
        val BLUEBIRD = Style(Birds.BLUEBIRD, 50f, 2, 0.05f, 0.5f, 0.55f, 0.7f, 4.6f)
        val SPARROW = Style(Birds.SPARROW, 46f, 4, 0.06f, 0.46f, 0.42f, 0.58f, 4.2f)
    }
}

/** A loose flock of sparrows further off, each bounding to its own rhythm, the whole flock swelling and turning as it goes. */
internal class Flock(r: Random) : Visit(Creature.FLOCK) {
    private val n = 10 + r.nextInt(15)
    private val spreadX = r.between(120f, 180f); private val spreadY = r.between(42f, 72f)
    private val ox = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadX }
    private val oy = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadY }
    private val len = FloatArray(n) { r.between(15f, LARGEST) }      // the nearer ones are larger
    override val duration = r.between(7.5f, 10.5f)
    private val bounds = Array(n) { Bounds(r, duration, 0.46f, 0.42f, 0.6f) }
    private val sway = r.between(0f, TAU)
    private val aspect = Aspect(r.between(0.15f, 0.35f) * r.side(), 0.36f)
    override val reach = Birds.SPARROW.reach * LARGEST + 14f
    private val margin = spreadX * 1.4f + reach
    private val course = course(r, 0.07f, 0.36f, margin, margin, bow = 0.07f)
    override val count get() = n
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        val speed = (w + 2f * margin * sc) / duration
        val swell = 1f + 0.16f * sin(t * 0.9f + sway); val turn = 0.22f * sin(t * 0.5f + sway * 1.3f)
        val c = cos(turn); val s = sin(turn)
        // A bird further off (a smaller one) bounds as deep, but it looks less.
        val up = bounds[i].height(t) * 4f * BOUNCE * speed * len[i] / LARGEST
        return Offset(
            course.x(u, w, sc) + (ox[i] * c - oy[i] * s) * swell * sc + bounds[i].surge(t) * speed,
            course.y(u, h) + (ox[i] * s + oy[i] * c) * swell * sc - up,
        )
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(Birds.SPARROW, aspect, LARGEST * h / 1080f, beats(Birds.SPARROW, BEATS) + BirdPainter.TUCKED)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.4f)
        for (i in 0 until n) {
            val at = place(i, t, w, h)
            val wings = bounds[i].wings(t); val k = len[i] / LARGEST
            withTransform({ translate(at.x, at.y); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (wings >= 0f) pose(wings, BEATS) else if (wings < -1.5f) 6 else BEATS, alpha, filter)
            }
        }
    }

    private companion object { const val BEATS = 8; const val LARGEST = 24f; const val BOUNCE = 0.06f } // poses through one wingbeat; pixels to the largest one's body; how deep they bound
}

/** A few crows rowing across, one behind another and none in step: steady deep beats, and now and then a moment's glide. */
internal class Crows(r: Random) : Visit(Creature.CROWS) {
    private val n = 3 + r.nextInt(4)
    private val len = FloatArray(n) { r.between(62f, LARGEST) * r.between(0.85f, 1f) }
    private val delay = FloatArray(n) { if (it == 0) 0f else it * r.between(0.8f, 2.1f) }
    private val cross = FloatArray(n).also { val base = r.between(8f, 10.5f); for (i in 0 until n) it[i] = base * r.between(0.95f, 1.06f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-70f, 70f) }
    private val period = FloatArray(n) { r.between(0.36f, 0.43f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val wander = FloatArray(n * 2) { r.between(0f, TAU) }
    private val rest = FloatArray(n * 3) { when (it % 3) { 0 -> r.between(3.2f, 5.5f); 1 -> r.between(0.6f, 1f); else -> r.nextFloat() } } // every so many seconds, a glide this long; and where in that it starts
    private val aspect = Aspect(r.between(0.12f, 0.32f) * r.side(), 0.3f)
    override val reach = Birds.CROW.reach * LARGEST + 34f
    private val course = course(r, 0.08f, 0.34f, reach + 60f, reach + 60f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    /** How much it is gliding at [t]: 0 beating, 1 wings held out. */
    private fun gliding(i: Int, t: Float): Float {
        val every = rest[i * 3]; val c = frac(t / every + rest[i * 3 + 2]) * every
        return ramp(0f, 0.12f, c) * (1f - ramp(rest[i * 3 + 1] - 0.12f, rest[i * 3 + 1], c))
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = ((t - delay[i]) / cross[i]).coerceIn(0f, 1f)
        val q = frac(t / period[i] + phase[i])
        // A small lift on each downstroke; none while it glides, when it sinks a little instead.
        val g = gliding(i, t)
        val rise = -5f * cos(TAU * (q - 0.3f)) * (1f - g) + 4f * g
        val stray = 22f * sin(0.55f * t + wander[i * 2]) + 9f * sin(1.3f * t + wander[i * 2 + 1])
        return Offset(course.x(u, w, sc), course.y(u, h) + (lift[i] + rise + stray) * sc)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(Birds.CROW, aspect, LARGEST * h / 1080f, beats(Birds.CROW, BEATS) + BirdPainter.glide(Birds.CROW))
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.1f)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val next = place(i, t + 0.06f, w, h)
            val pitch = atan2(next.y - at.y, abs(next.x - at.x)) * DEG * 0.5f
            val k = len[i] / LARGEST
            withTransform({ translate(at.x, at.y); rotate(pitch * course.dir, Offset.Zero); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (gliding(i, t) > 0.5f) BEATS else pose(frac(t / period[i] + phase[i]), BEATS), alpha, filter)
            }
        }
    }

    private companion object { const val BEATS = 12; const val LARGEST = 80f } // poses through one wingbeat; pixels to the largest one's body
}

/**
 * Geese in a V, seen from below and a little to one side, flying the way they point. The V is laid out on the
 * level and then seen in perspective: the arm on our side is nearer, so its birds are a little larger and sit
 * higher in the sky than the far arm's. If they are heading slightly away they sink toward the horizon and
 * dwindle as they cross; slightly toward us, they climb and grow.
 */
internal class Geese(r: Random, season: Season) : Visit(Creature.GEESE) {
    private val n = 9 + r.nextInt(9)
    private val len = r.between(32f, 40f)
    private val gap = len * 2f
    // In autumn they are leaving, heading a little away from us; in spring they are coming back.
    private val heading = r.between(0.07f, 0.17f) * (if (season == Season.SPRING) -1f else if (season == Season.AUTUMN) 1f else r.side())
    private val aspect = Aspect(heading, ELEVATION)
    private val behind = FloatArray(n); private val below = FloatArray(n); private val scaleOf = FloatArray(n)
    private val beatAt = FloatArray(n); private val drift = FloatArray(n)
    private val period = r.between(0.38f, 0.44f)
    private val dir = r.side()
    override val duration = r.between(17f, 24f)
    override val count get() = n
    override val reach = Birds.GOOSE.reach * len * LARGEST + gap * 0.3f
    private val lead: Float; private val trail: Float
    private val y0: Float; private val y1: Float

    private var sheet: Sheet? = null
    private var bakedFor = 0f

    init {
        val longArm = ((n - 1) * r.between(0.5f, 0.7f)).toInt().coerceAtLeast(1)
        val near = r.side()                         // whether the long arm is the one on our side
        val open = r.between(0.5f, 0.62f)           // half the angle of the V
        // On the screen: the way they fly is along (fx, fy); toward us is along (wx, wy), which looking up is higher in the sky.
        val fx = cos(heading); val fy = sin(heading) * sin(ELEVATION)
        val wx = sin(heading); val wy = -cos(heading) * sin(ELEVATION)
        var longest = 0f
        scaleOf[0] = 1f
        for (i in 1 until n) {
            val onLong = i <= longArm
            val k = (if (onLong) i else i - longArm).toFloat(); val arm = if (onLong) near else -near
            val back = k * gap * cos(open) + r.between(-0.12f, 0.12f) * gap
            val toUs = arm * k * gap * sin(open)
            behind[i] = back * fx - toUs * wx
            below[i] = -back * fy + toUs * wy
            scaleOf[i] = 1f + toUs / DISTANCE
            longest = max(longest, behind[i])
        }
        for (i in 0 until n) { beatAt[i] = i * 0.13f + r.between(0f, 0.2f); drift[i] = r.between(0f, TAU) }
        lead = reach + gap; trail = longest * 1.1f + reach + gap * 1.5f
        // Heading away, the whole flight slopes down the sky as it crosses; heading toward us, up.
        val slope = tan(heading) * sin(ELEVATION) * (1920f + lead + trail) / 1080f
        val top = 0.08f + max(0f, -slope); val bottom = 0.32f - max(0f, slope)
        y0 = r.between(top, max(top, bottom)); y1 = y0 + slope
    }

    /** How large they look [u] of the way across: nearer or further as they go. */
    private fun looming(u: Float) = 1f - (u - 0.5f) * tan(heading) * 0.9f

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration; val k = looming(u)
        val along = -lead * sc + u * (w + (lead + trail) * sc)
        // Each bird's place in the V, shifting a little as it holds station; and a small lift on each downstroke.
        val back = (behind[i] + sin(t * 0.45f + drift[i]) * gap * 0.18f) * k * sc
        val down = (below[i] + sin(t * 0.37f + drift[i] * 1.7f) * gap * 0.1f - 2.5f * cos(TAU * (frac(t / period + beatAt[i]) - 0.3f))) * k * sc
        val x = along - back
        return Offset(if (dir > 0f) x else w - x, h * mix(y0, y1, u) + down)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(Birds.GOOSE, aspect, len * LARGEST * h / 1080f, beats(Birds.GOOSE, BEATS))
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.3f)
        val loom = looming(t / duration)
        for (i in 0 until n) {
            val p = place(i, t, w, h); val k = (scaleOf[i] * loom / LARGEST).coerceAtMost(1f)
            withTransform({ translate(p.x, p.y); scale(k * dir, k, Offset.Zero) }) { pages.stamp(this, pose(frac(t / period + beatAt[i]), BEATS), alpha, filter) }
        }
    }

    private companion object {
        const val BEATS = 12            // poses through one wingbeat
        const val ELEVATION = 0.5f      // how far up we are looking at them
        const val DISTANCE = 2300f      // how far off the leader is, in the same pixels as the V is laid out in
        const val LARGEST = 1.32f       // the most a bird is ever enlarged: on the near arm, at its nearest
    }
}

/**
 * A red-tailed hawk circling on a thermal, drifting slowly across the sky on set wings. It is seen from below, so
 * as it wheels it shows its pale underside from every side: banked into the turn, tail fanned.
 */
internal class Hawk(r: Random) : Visit(Creature.HAWK) {
    private val len = r.between(54f, 66f)
    private val radius = r.between(140f, 195f)
    private val turn = r.between(11f, 14f) * r.side() // seconds for one circle; the sign is which way it wheels
    private val dir = r.side()
    private val y0 = r.between(0.16f, 0.27f); private val y1 = r.between(0.16f, 0.27f)
    private val start = r.between(0f, TAU)
    override val duration = r.between(56f, 70f)       // it drifts across more slowly than it circles, so it really does go round
    override val count get() = 1
    override val reach = Birds.HAWK.reach * len * NEAREST + 4f
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    private fun angle(t: Float) = start + TAU * t / turn

    /** The way it is heading at [t], as an [Aspect] counts it. */
    private fun heading(t: Float): Float { val a = angle(t); val s = if (turn > 0f) 1f else -1f; return atan2(-cos(a) * s, -sin(a) * s) }

    /** Which way its body points on the screen when it is heading [psi], in degrees. */
    private fun onScreen(psi: Float) = atan2(sin(psi) * sin(ELEVATION), cos(psi)) * DEG

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration; val m = (radius + reach) * sc
        val cx = if (dir > 0f) -m + u * (w + 2f * m) else w + m - u * (w + 2f * m)
        // Looking up at it, the near side of its circle is the higher one on the screen.
        return Offset(cx + radius * sc * cos(angle(t)), h * mix(y0, y1, u) - radius * sc * sin(ELEVATION) * sin(angle(t)))
    }

    // One picture for each of its headings round the circle, at the size of the near side.
    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val scale = len * NEAREST * h / 1080f; val cell = (Birds.HAWK.reach * scale * 2f).toInt() + 6
        val bank = if (turn > 0f) BANK else -BANK
        val pose = BirdPainter.glide(Birds.HAWK, fan = 1.25f)
        sheet = Sheet(HEADINGS, cell, cell) { j -> with(BirdPainter(Birds.HAWK, Aspect(TAU * j / HEADINGS, ELEVATION, bank), scale)) { paint(pose) } }
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val p = place(0, t, w, h)
        val psi = heading(t)
        val j = (((psi / TAU) * HEADINGS + 0.5f).toInt() % HEADINGS + HEADINGS) % HEADINGS
        // Between two pictures, the nearer one is turned the rest of the way.
        var rest = onScreen(psi) - onScreen(TAU * j / HEADINGS)
        if (rest > 180f) rest -= 360f else if (rest < -180f) rest += 360f
        val near = (1f + (NEAREST - 1f) * sin(angle(t))) / NEAREST
        withTransform({ translate(p.x, p.y); rotate(rest, Offset.Zero); scale(near, near, Offset.Zero) }) {
            pages.stamp(this, j, alpha, light.at(0.2f))
        }
    }

    private companion object { const val NEAREST = 1.12f; const val HEADINGS = 24; const val ELEVATION = 0.75f; const val BANK = 0.4f }
}

/**
 * A barn owl on a low hunting pass, coming toward us at a slant: a few deep, slow beats, then a long glide on broad
 * round wings, dipping across the middle of the sky and lifting away. Pale enough to catch what light there is.
 */
internal class Owl(r: Random) : Visit(Creature.OWL) {
    private val len = r.between(72f, 84f)
    private val aspect = Aspect(r.between(-0.95f, -0.65f), 0.36f)
    private val phase = r.nextFloat()
    private val near0 = r.between(0.72f, 0.82f)          // it starts further off and passes close
    override val reach = Birds.BARN_OWL.reach * len + 16f
    private val course = Course(r.side(), r.between(0.08f, 0.14f), r.between(0.09f, 0.16f), r.between(0.09f, 0.15f), reach + 12f, reach + 12f)
    override val duration = r.between(7.2f, 8.8f)
    override val count get() = 1
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    /** Its flight repeats every 3.1 seconds: two beats, then the glide. */
    private fun cycle(t: Float) = frac(t / 3.1f + phase)
    private fun gliding(t: Float): Float { val p = cycle(t); return ramp(0.5f, 0.56f, p) * (1f - ramp(0.94f, 1f, p)) }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        val lift = -8f * cos(TAU * (frac(cycle(t) / 0.52f * 2f) - 0.3f)) * (1f - gliding(t)) // each downstroke lifts it a little
        return Offset(course.x(u, w, sc), course.y(u, h) + lift * sc)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(Birds.BARN_OWL, aspect, len * h / 1080f, beats(Birds.BARN_OWL, BEATS) + BirdPainter.glide(Birds.BARN_OWL))
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val p = place(0, t, w, h); val c = cycle(t)
        val k = mix(near0, 1f, ramp(0f, 0.75f, t / duration))
        withTransform({ translate(p.x, p.y); scale(k * course.dir, k, Offset.Zero) }) {
            pages.stamp(this, if (gliding(t) > 0.5f) BEATS else pose(frac(c / 0.52f * 2f), BEATS), alpha, light.at(0f))
        }
    }

    private companion object { const val BEATS = 12 } // poses through one wingbeat
}
