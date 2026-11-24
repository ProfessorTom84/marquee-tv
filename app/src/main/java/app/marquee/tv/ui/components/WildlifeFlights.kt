package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * How the birds fly. Each kind moves the way that kind does: small birds bound along (a burst of wingbeats climbing,
 * then wings shut, falling); crows row steadily and now and then glide a moment; geese hold a V that breathes; a
 * hawk wheels on set wings, banked into its circle; an owl takes a few deep, slow beats and then glides. None of
 * them holds a straight line or a steady pace, and each bird in a group has its own.
 */

private fun beats(kind: BirdKind, n: Int): List<WingPose> = List(n) { BirdPainter.beat(kind, it / n.toFloat()) }

/** One bird kind seen one way: its poses on a sheet, at [scale] pixels to a body length. */
private fun birdSheet(kind: BirdKind, aspect: Aspect, scale: Float, poses: List<WingPose>): Sheet {
    val painter = BirdPainter(kind, aspect, scale); val cell = (kind.reach * scale * 2f).toInt() + 6
    return Sheet(poses.size, cell, cell) { i -> with(painter) { paint(poses[i]) } }
}

/** One to a few small birds of one kind, the kind the season brings: a cardinal against the snow, goldfinches in summer, a jay in autumn. */
internal class Songbirds(r: Random, season: Season) : Visit(Creature.SONGBIRDS) {
    private val kind: BirdKind
    private val n: Int
    private val len: FloatArray

    init {
        val roll = r.nextFloat()
        // The kind, pixels to a body length at 1080p, and how many keep company.
        val (k, size, most) = when (season) {
            Season.WINTER -> if (roll < 0.4f) CARDINAL else if (roll < 0.75f) CHICKADEE else if (roll < 0.9f) JAY else SPARROW
            Season.SPRING -> if (roll < 0.4f) ROBIN else if (roll < 0.65f) BLUEBIRD else if (roll < 0.8f) CARDINAL else if (roll < 0.9f) CHICKADEE else GOLDFINCH
            Season.SUMMER -> if (roll < 0.3f) GOLDFINCH else if (roll < 0.55f) ROBIN else if (roll < 0.75f) CARDINAL else if (roll < 0.9f) BLUEBIRD else JAY
            Season.AUTUMN -> if (roll < 0.3f) JAY else if (roll < 0.5f) CARDINAL else if (roll < 0.7f) ROBIN else if (roll < 0.9f) CHICKADEE else SPARROW
        }
        kind = k
        n = 1 + r.nextInt(most)
        len = FloatArray(n) { size * r.between(0.94f, 1.08f) }
    }

    private val delay = FloatArray(n) { if (it == 0) 0f else it * r.between(0.45f, 1.2f) }
    private val cross = FloatArray(n).also { val base = r.between(5.6f, 7.6f); for (i in 0 until n) it[i] = base * r.between(0.95f, 1.06f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-60f, 60f) }
    private val period = FloatArray(n) { r.between(0.62f, 0.78f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val bob = FloatArray(n) { len[it] * r.between(0.3f, 0.44f) }
    private val wander = FloatArray(n * 2) { r.between(0f, TAU) }
    private val pace = FloatArray(n) { r.between(0.006f, 0.013f) * r.side() }
    private val aspect = Aspect(r.between(0.1f, 0.35f) * r.side(), 0.3f)
    // It may be coming nearer as it crosses, or going away.
    private val near0 = r.between(0.8f, 1.1f); private val near1 = r.between(0.8f, 1.1f)
    private val longest = len.max() * max(near0, near1)
    override val reach = kind.reach * longest + 46f
    private val course = course(r, 0.08f, 0.34f, reach + 60f, reach + 60f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    /** How far across it is: never quite steady. */
    private fun along(i: Int, t: Float): Float { val v = ((t - delay[i]) / cross[i]).coerceIn(0f, 1f); return v + pace[i] * sin(TAU * 2f * v) }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = along(i, t)
        val p = frac(t / period[i] + phase[i])
        // Climbing through the burst of beats, falling while the wings are shut; and wandering a little, as a bird does.
        val rise = -bob[i] * cos(TAU * (p - 0.6f))
        val stray = 15f * sin(0.9f * t + wander[i * 2]) + 7f * sin(2.2f * t + wander[i * 2 + 1])
        return Offset(course.x(u, w, sc), course.y(u, h) + (lift[i] + rise + stray) * sc)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(kind, aspect, longest * h / 1080f, beats(kind, BEATS) + BirdPainter.TUCKED)
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
            val pitch = atan2(next.y - at.y, abs(next.x - at.x)) * DEG * 0.6f
            val p = frac(t / period[i] + phase[i])
            val k = len[i] * mix(near0, near1, along(i, t)) / longest
            withTransform({ translate(at.x, at.y); rotate(pitch * course.dir, Offset.Zero); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (p < BURST) pose(frac(p / BURST * 3f), BEATS) else BEATS, alpha, filter)
            }
        }
    }

    private companion object {
        const val BEATS = 8        // poses through one wingbeat
        const val BURST = 0.56f    // the share of each bound spent beating
        val CARDINAL = Triple(Birds.CARDINAL, 60f, 2); val JAY = Triple(Birds.BLUE_JAY, 68f, 2); val ROBIN = Triple(Birds.ROBIN, 64f, 2)
        val GOLDFINCH = Triple(Birds.GOLDFINCH, 42f, 4); val CHICKADEE = Triple(Birds.CHICKADEE, 42f, 4); val BLUEBIRD = Triple(Birds.BLUEBIRD, 50f, 2)
        val SPARROW = Triple(Birds.SPARROW, 46f, 4)
    }
}

/** A loose flock of sparrows further off, each bounding to its own rhythm, the whole flock swelling and turning as it goes. */
internal class Flock(r: Random) : Visit(Creature.FLOCK) {
    private val n = 10 + r.nextInt(15)
    private val spreadX = r.between(120f, 180f); private val spreadY = r.between(42f, 72f)
    private val ox = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadX }
    private val oy = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadY }
    private val len = FloatArray(n) { r.between(15f, LARGEST) }      // the nearer ones are larger
    private val period = FloatArray(n) { r.between(0.62f, 0.8f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val bound = FloatArray(n) { r.between(7f, 12f) }
    private val sway = r.between(0f, TAU)
    private val aspect = Aspect(r.between(0.5f, 0.9f) * r.side(), 0.36f)
    override val reach = Birds.SPARROW.reach * LARGEST + 14f
    private val course = course(r, 0.07f, 0.36f, spreadX * 1.4f + reach, spreadX * 1.4f + reach, bow = 0.07f)
    override val duration = r.between(10f, 14f)
    override val count get() = n
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        val swell = 1f + 0.16f * sin(t * 0.9f + sway); val turn = 0.22f * sin(t * 0.5f + sway * 1.3f)
        val c = cos(turn); val s = sin(turn)
        val p = frac(t / period[i] + phase[i])
        return Offset(
            course.x(u, w, sc) + (ox[i] * c - oy[i] * s) * swell * sc,
            course.y(u, h) + ((ox[i] * s + oy[i] * c) * swell - bound[i] * cos(TAU * (p - 0.6f))) * sc,
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
            val p = frac(t / period[i] + phase[i]); val k = len[i] / LARGEST
            withTransform({ translate(at.x, at.y); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (p < 0.6f) pose(frac(p / 0.6f * 3f), BEATS) else BEATS, alpha, filter)
            }
        }
    }

    private companion object { const val BEATS = 8; const val LARGEST = 24f } // poses through one wingbeat; pixels to the largest one's body
}

/** A few crows rowing across, one behind another and none in step: steady deep beats, and now and then a moment's glide. */
internal class Crows(r: Random) : Visit(Creature.CROWS) {
    private val n = 3 + r.nextInt(4)
    private val len = FloatArray(n) { r.between(62f, LARGEST) * r.between(0.85f, 1f) }
    private val delay = FloatArray(n) { if (it == 0) 0f else it * r.between(0.9f, 2.4f) }
    private val cross = FloatArray(n).also { val base = r.between(10f, 13f); for (i in 0 until n) it[i] = base * r.between(0.95f, 1.06f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-70f, 70f) }
    private val period = FloatArray(n) { r.between(0.46f, 0.54f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val wander = FloatArray(n * 2) { r.between(0f, TAU) }
    private val rest = FloatArray(n * 3) { when (it % 3) { 0 -> r.between(3.2f, 5.5f); 1 -> r.between(0.7f, 1.2f); else -> r.nextFloat() } } // every so many seconds, a glide this long; and where in that it starts
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

/** Geese in a V: one arm longer than the other, each bird holding its place loosely, a slow beat passing down the line. */
internal class Geese(r: Random, season: Season) : Visit(Creature.GEESE) {
    private val n = 9 + r.nextInt(9)
    private val len = r.between(30f, 38f)
    private val gap = len * 2.1f
    private val back = FloatArray(n); private val side = FloatArray(n); private val beatAt = FloatArray(n); private val drift = FloatArray(n)
    private val scaleOf = FloatArray(n) { r.between(0.92f, 1.04f) }
    private val period = r.between(0.62f, 0.72f)
    // Facing the equator, as the sky does: in autumn they are leaving (heading away), in spring coming back.
    private val aspect = Aspect(if (season == Season.SPRING) -0.8f else 0.8f, 0.45f)
    private val course: Course
    override val duration = r.between(30f, 42f)
    override val count get() = n
    override val reach = Birds.GOOSE.reach * len * 1.04f + gap * 0.3f
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    init {
        val longArm = ((n - 1) * r.between(0.5f, 0.7f)).toInt().coerceAtLeast(1)
        val up = r.side()               // whether the long arm is the upper one
        val skew = r.between(-0.14f, 0.14f) // seen from one side, one arm trails further back than the other
        var longest = 0f
        for (i in 1 until n) {
            val onLong = i <= longArm
            val k = (if (onLong) i else i - longArm).toFloat(); val arm = if (onLong) up else -up
            back[i] = k * gap * (0.82f + arm * skew) + r.between(-0.12f, 0.12f) * gap
            side[i] = -arm * k * gap * 0.28f // from the ground the V is squashed nearly flat
            longest = max(longest, back[i])
        }
        for (i in 0 until n) { beatAt[i] = i * 0.13f + r.between(0f, 0.2f); drift[i] = r.between(0f, TAU) }
        course = course(r, 0.08f, 0.30f, lead = reach + gap, trail = longest + reach + gap, bow = 0.03f)
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        // Each bird's place in the V: behind the leader and above or below it, shifting a little as it holds station.
        return Offset(
            course.x(u, w, sc) - course.dir * (back[i] + sin(t * 0.45f + drift[i]) * gap * 0.22f) * sc,
            course.y(u, h) + (side[i] + sin(t * 0.37f + drift[i] * 1.7f) * gap * 0.12f - 2.5f * cos(TAU * (frac(t / period + beatAt[i]) - 0.3f))) * sc,
        )
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        sheet = birdSheet(Birds.GOOSE, aspect, len * 1.04f * h / 1080f, beats(Birds.GOOSE, BEATS))
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.3f)
        for (i in 0 until n) {
            val p = place(i, t, w, h); val k = scaleOf[i] / 1.04f
            withTransform({ translate(p.x, p.y); scale(k * course.dir, k, Offset.Zero) }) { pages.stamp(this, pose(frac(t / period + beatAt[i]), BEATS), alpha, filter) }
        }
    }

    private companion object { const val BEATS = 12 } // poses through one wingbeat
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
