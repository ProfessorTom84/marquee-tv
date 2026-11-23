package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.marquee.tv.weather.Sky
import java.time.LocalDate
import java.time.Month
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Passing wildlife. Every minute or two something alive crosses the sky and is gone, chosen for the hour, the
 * season and the weather:
 *
 *  - By day: a songbird or two in bounding flight, a loose flock, crows rowing past, geese in a V (mostly in
 *    autumn and spring), a hawk wheeling on a thermal, butterflies in the warm months, and a squirrel leaping
 *    from a branch just above the screen to a tree just beside it.
 *  - Around sunset: starlings gathering into a murmuration (autumn and winter), crows heading to roost, bats.
 *  - After dark: an owl on a hunting pass, bats, and a flying squirrel gliding down between the trees.
 *  - Nothing flies in a storm, and little in rain.
 *
 * A sighting is fixed the moment it is picked, so drawing it is a pure function of how long it has been in view:
 * nothing is simulated or stored from frame to frame, and between sightings nothing runs at all.
 *
 * It is drawn the way the falling leaves are, because that is what the TV's graphics chip is quickest at. Each
 * creature's poses are painted once, before it appears, into one small picture (a [Flipbook]); every frame then
 * only places pieces of that picture. A murmuration is a single batch of triangles. Only the owl and the squirrel,
 * one large shape each and seldom seen, are outlined afresh every frame.
 */

enum class Creature { SONGBIRDS, FLOCK, CROWS, GEESE, HAWK, STARLINGS, BUTTERFLIES, SQUIRREL, OWL, BATS, FLYING_SQUIRREL }

/**
 * One sighting: a creature, or a group of them, crossing the sky once. Every member is out of view at 0 seconds
 * and again at [duration], so nothing ever appears or vanishes in mid-air.
 */
abstract class Visit internal constructor(val creature: Creature) {
    /** Seconds from the first member entering to the last one leaving. */
    abstract val duration: Float

    /**
     * Whether it needs redrawing on every frame while it is in view. Only a leaping squirrel does, for the second or
     * two its leap takes. Everything else moves at the pace of the leaves and the rain.
     */
    open val everyFrame: Boolean get() = false

    internal abstract val count: Int

    /** How far a member's drawing can reach from its place, in 1080p pixels. */
    internal abstract val reach: Float

    /** Where member [i] is at [t] seconds, in pixels on a [w] by [h] screen. */
    internal abstract fun place(i: Int, t: Float, w: Float, h: Float): Offset

    /** The box member [i]'s drawing stays inside at [t]. */
    internal open fun box(i: Int, t: Float, w: Float, h: Float): Rect {
        val p = place(i, t, w, h); val m = reach * h / 1080f
        return Rect(p.x - m, p.y - m, p.x + m, p.y + m)
    }

    /** Whether any of it can be seen at [t]. */
    internal fun inView(t: Float, w: Float, h: Float): Boolean {
        for (i in 0 until count) { val b = box(i, t, w, h); if (b.right > 0f && b.left < w && b.bottom > 0f && b.top < h) return true }
        return false
    }

    /**
     * Paints the pictures this sighting is drawn from, for a screen [h] pixels tall. It can be called from any one
     * thread before the sighting starts, so the painting is done before the creature appears; [draw] does it on the
     * spot if it hasn't been.
     */
    open fun prepare(h: Float) {}

    protected abstract fun DrawScope.paint(t: Float, ink: Color, alpha: Float)

    /**
     * Draws the sighting [t] seconds in. [sky] is the sky's colour where it flies, which decides the creature's own
     * colour; [visibility] thins it in fog or rain.
     */
    fun draw(scope: DrawScope, t: Float, sky: Color, visibility: Float = 1f) {
        if (t < 0f || t > duration) return
        with(scope) { paint(t, inkFor(sky), visibility) }
    }
}

/** Chooses what crosses the sky, and when. */
object Wildlife {
    /** When the next sighting is due (monotonic milliseconds). Kept here so that leaving home and coming back doesn't restart the wait. */
    @Volatile internal var dueAt = 0L

    /** The wait before the next sighting: a minute or two by day, longer after dark. */
    fun gapMs(r: Random, sunElev: Float): Long = ((if (sunElev < NIGHT) r.between(80f, 260f) else r.between(45f, 170f)) * 1000f).toLong()

    /** How clearly anything shows through this weather. */
    fun visibility(sky: Sky): Float = when (sky) { Sky.FOG -> 0.5f; Sky.RAIN -> 0.7f; Sky.SNOW -> 0.8f; Sky.CLOUDY -> 0.92f; else -> 1f }

    private const val DAY = 6f     // the sun this many degrees up: full daylight
    private const val NIGHT = -7f  // and this far down: dark

    /** What might be about in these conditions, each with its weight against the others. Empty: nothing is flying. */
    internal fun odds(season: Season, sky: Sky, sunElev: Float, wind: Float, date: LocalDate): Map<Creature, Float> {
        val m = LinkedHashMap<Creature, Float>()
        if (sky == Sky.STORM) return m
        fun add(c: Creature, weight: Float) { if (weight > 0f) m[c] = weight }
        val wet = sky == Sky.RAIN; val snow = sky == Sky.SNOW
        val open = sky == Sky.CLEAR || sky == Sky.PARTLY
        val cold = season == Season.AUTUMN || season == Season.WINTER
        val halloween = date.month == Month.OCTOBER && date.dayOfMonth >= 24
        val geese = when (season) { Season.AUTUMN -> 2.5f; Season.SPRING -> 1.5f; Season.WINTER -> 0.8f; Season.SUMMER -> 0.4f } * if (wet) 0.6f else 1f
        val bats = if (wet || snow) 0f else if (halloween) 4f else when (season) { Season.SUMMER -> 3f; Season.SPRING, Season.AUTUMN -> 1.5f; Season.WINTER -> 0f }
        when {
            sunElev > DAY -> {
                add(Creature.SONGBIRDS, if (wet) 1f else if (snow) 2f else 3f)
                add(Creature.FLOCK, if (wet) 0.5f else if (snow) 2f else 2.5f)
                add(Creature.CROWS, if (cold) 1.8f else 0.7f)
                add(Creature.GEESE, geese)
                // A hawk needs rising warm air, which needs sun on the ground.
                add(Creature.HAWK, if (open && sunElev > 15f) (if (season == Season.WINTER) 1f else 1.6f) else 0f)
                // Butterflies from spring until the first weeks of autumn (the monarchs' passage); never in wind, rain or fog.
                val earlyAutumn = date.dayOfYear.let { (it in 244..288) || (it in 60..105) } // September to mid October; March to mid April in the south
                val flutter = when (season) { Season.SPRING -> 1.5f; Season.SUMMER -> 2.5f; Season.AUTUMN -> if (earlyAutumn) 1f else 0f; Season.WINTER -> 0f }
                add(Creature.BUTTERFLIES, if (wet || snow || sky == Sky.FOG || wind > 0.55f) 0f else if (open) flutter else flutter * 0.5f)
                add(Creature.SQUIRREL, if (wet) 0.4f else if (snow) 0.6f else when (season) { Season.AUTUMN -> 2.6f; Season.WINTER -> 1.2f; else -> 2f })
            }
            sunElev > NIGHT -> {
                add(Creature.STARLINGS, (if (cold) 3f else if (season == Season.SPRING) 0.8f else 0.3f) * if (wet) 0.3f else 1f)
                add(Creature.CROWS, 2f)
                add(Creature.GEESE, geese)
                add(Creature.SONGBIRDS, if (wet) 0.5f else 1.5f)
                add(Creature.FLOCK, if (wet) 0.5f else 1.5f)
                if (sunElev < 2f) add(Creature.BATS, bats)
                if (sunElev < 0f) add(Creature.OWL, 1f) else add(Creature.SQUIRREL, if (wet || snow) 0.3f else 1f)
            }
            else -> {
                add(Creature.OWL, if (wet) 0.3f else 2f)
                add(Creature.BATS, bats * 0.7f)
                add(Creature.FLYING_SQUIRREL, if (wet) 0.2f else if (snow) 0.5f else if (season == Season.WINTER) 0.8f else 1.5f)
            }
        }
        return m
    }

    /** Chooses the next sighting for these conditions. Null means this wait ends with an empty sky. */
    fun pick(r: Random, season: Season, sky: Sky, sunElev: Float, wind: Float, date: LocalDate): Visit? {
        val o = odds(season, sky, sunElev, wind, date)
        if (o.isEmpty()) return null
        // Fewer creatures are about in rain, snow, fog or the dark: some waits end with nothing.
        val quiet = when { sky == Sky.RAIN -> 0.5f; sky == Sky.SNOW || sky == Sky.FOG -> 0.25f; sunElev < NIGHT -> 0.3f; else -> 0f }
        if (r.nextFloat() < quiet) return null
        var x = r.nextFloat() * o.values.sum()
        var chosen = o.keys.last()
        for ((c, weight) in o) { x -= weight; if (x <= 0f) { chosen = c; break } }
        return of(chosen, r.nextInt(), season)
    }

    /** A particular sighting: the same [seed] always gives the same flight. */
    fun of(creature: Creature, seed: Int, season: Season = Season.AUTUMN): Visit {
        val r = Random(seed)
        return when (creature) {
            Creature.SONGBIRDS -> songbirds(r, season)
            Creature.FLOCK -> Flock(r)
            Creature.CROWS -> Fliers(creature, r, CROW, 3 + r.nextInt(4), 46f, 56f, 9f, 12f, Flight.ROWING, null, stagger = 2.6f)
            Creature.GEESE -> Geese(r, season)
            Creature.HAWK -> Hawk(r)
            Creature.STARLINGS -> Starlings(r)
            Creature.BUTTERFLIES -> Butterflies(r, season)
            Creature.SQUIRREL -> Squirrels(r)
            Creature.OWL -> Owl(r)
            Creature.BATS -> Bats(r)
            Creature.FLYING_SQUIRREL -> Glider(r)
        }
    }

    /**
     * More than ever crosses at once, for the performance check: a full V of geese, a full flock, six crows and a
     * murmuration. Each comes with how many seconds into its crossing to start it, so that all of them are in view
     * together from the first moment and for several seconds after.
     */
    fun parade(season: Season): List<Pair<Visit, Float>> = listOf(
        Triple(Creature.GEESE, 42, 0.4f), Triple(Creature.FLOCK, 13, 0.25f), Triple(Creature.CROWS, 10, 0.3f), Triple(Creature.STARLINGS, 14, 0.35f),
    ).map { (creature, seed, from) -> of(creature, seed, season).let { it to it.duration * from } }

    /** A flash of colour now and then: a cardinal against the snow, a jay in autumn, a goldfinch in summer. */
    private fun songbirds(r: Random, season: Season): Visit {
        val roll = r.nextFloat()
        val cardinal = Color(0xFFB3261E); val jay = Color(0xFF3F6FB5); val goldfinch = Color(0xFFD9B92B); val robin = Color(0xFFB5622E)
        var form = SONGBIRD; var tint: Color? = null
        when (season) {
            Season.WINTER -> if (roll < 0.5f) { form = CRESTED; tint = cardinal }
            Season.AUTUMN -> if (roll < 0.35f) { form = CRESTED; tint = jay } else if (roll < 0.5f) { form = CRESTED; tint = cardinal }
            Season.SPRING -> if (roll < 0.4f) tint = robin else if (roll < 0.55f) { form = CRESTED; tint = jay }
            Season.SUMMER -> if (roll < 0.4f) tint = goldfinch else if (roll < 0.55f) { form = CRESTED; tint = cardinal }
        }
        return Fliers(Creature.SONGBIRDS, r, form, 1 + r.nextInt(3), 36f, 46f, 4.8f, 6.6f, Flight.BOUNDING, tint, stagger = 1.1f)
    }
}

// =====================================================================================
//  Shared pieces: colour, paths across the screen, outlines, and wingbeats.
// =====================================================================================

private const val PI_F = PI.toFloat()
private const val TAU = 2f * PI_F
private const val DEG = 180f / PI_F

private fun Random.between(a: Float, b: Float) = a + nextFloat() * (b - a)
private fun Random.side() = if (nextBoolean()) 1f else -1f
private fun frac(x: Float) = x - floor(x)
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun ramp(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** Lower on the screen sits under the darker scrim and the cards: fade a little so creatures stay behind the UI visually. */
private fun lowFade(y: Float, h: Float) = 1f - (y / h).coerceIn(0f, 1f) * 0.55f

/**
 * A creature's colour against this sky: a dark shape while there is light behind it, and once the sky is too dark
 * for a silhouette to show, a pale one, as if it caught the moon.
 */
internal fun inkFor(sky: Color): Color =
    if (sky.luminance() > 0.04f) lerp(Color(0xFF0B0F16), sky, 0.18f) else lerp(sky, Color(0xFFC9D2E8), 0.3f)

/**
 * A steady crossing from one side of the screen to the other; the height bows and wavers a little on the way.
 * [lead] and [trail] (1080p pixels) are how far beyond the edges it starts and ends, so a whole group fits outside.
 */
private class Course(
    val dir: Float, private val y0: Float, private val y1: Float, private val bow: Float, private val lead: Float, private val trail: Float,
    private val wave: Float = 0f, private val turns: Float = 1f, private val phase: Float = 0f,
) {
    fun x(u: Float, w: Float, sc: Float): Float { val p = -lead * sc + u * (w + (lead + trail) * sc); return if (dir > 0f) p else w - p }
    fun y(u: Float, h: Float): Float = h * (y0 + (y1 - y0) * u + bow * 4f * u * (1f - u) + wave * sin(TAU * turns * u + phase))
}

private fun course(r: Random, top: Float, bottom: Float, lead: Float, trail: Float, bow: Float = 0.05f): Course {
    val y0 = r.between(top, bottom)
    return Course(r.side(), y0, (y0 + r.between(-0.09f, 0.09f)).coerceIn(top, bottom), r.between(-bow, bow), lead, trail, r.between(0.003f, 0.012f), r.between(0.7f, 2f), r.between(0f, TAU))
}

/**
 * A smooth outline through a ring of points (a [tip] stays sharp). Every outline goes into its path turning the
 * same way, so overlapping parts (a wing across a body, a leg across a belly) merge into one silhouette instead of
 * cutting holes in each other.
 */
private class Outline {
    private val xs = FloatArray(96); private val ys = FloatArray(96); private val sharp = BooleanArray(96); private var n = 0
    fun start(): Outline { n = 0; return this }
    fun to(x: Float, y: Float): Outline { xs[n] = x; ys[n] = y; sharp[n] = false; n++; return this }
    fun tip(x: Float, y: Float): Outline { xs[n] = x; ys[n] = y; sharp[n] = true; n++; return this }

    /** A whole outline from (x, y, sharp) triples; with [mirror] the points are one half, reflected about x = 0 to make the other. */
    fun shape(pts: FloatArray, mirror: Boolean = false): Outline {
        start()
        val k = pts.size / 3
        for (i in 0 until k) if (pts[i * 3 + 2] > 0f) tip(pts[i * 3], pts[i * 3 + 1]) else to(pts[i * 3], pts[i * 3 + 1])
        if (mirror) for (i in k - 1 downTo 0) { if (pts[i * 3] == 0f) continue; if (pts[i * 3 + 2] > 0f) tip(-pts[i * 3], pts[i * 3 + 1]) else to(-pts[i * 3], pts[i * 3 + 1]) }
        return this
    }

    fun into(p: Path) {
        if (n < 3) return
        var area = 0f
        for (i in 0 until n) { val j = (i + 1) % n; area += xs[i] * ys[j] - xs[j] * ys[i] }
        val step = if (area >= 0f) 1 else n - 1
        var i = 0
        p.moveTo(xs[0], ys[0])
        repeat(n) {
            val h = (i + n - step) % n; val j = (i + step) % n; val k = (j + step) % n
            val ax = if (sharp[i]) xs[i] else xs[i] + (xs[j] - xs[h]) / 6f; val ay = if (sharp[i]) ys[i] else ys[i] + (ys[j] - ys[h]) / 6f
            val bx = if (sharp[j]) xs[j] else xs[j] - (xs[k] - xs[i]) / 6f; val by = if (sharp[j]) ys[j] else ys[j] - (ys[k] - ys[i]) / 6f
            p.cubicTo(ax, ay, bx, by, xs[j], ys[j])
            i = j
        }
        p.close()
    }
}

/** Reused for every frame, so drawing allocates nothing. Only ever touched while drawing (one thread). */
private object Scratch {
    val a by lazy { Path() }
    val b by lazy { Path() }
    val plain by lazy { Paint().apply { color = Color.White } } // white: a batch of triangles keeps its own colours whichever way they are combined with the paint's
    val outline = Outline()
    val jx = FloatArray(5); val jy = FloatArray(5)

    // The filter that tints a white picture with a creature's colour. The colour only changes with the light, every
    // couple of minutes, so the same filter serves frame after frame.
    private var tintColor = Color.Unspecified
    private var tint: ColorFilter? = null
    fun tint(c: Color): ColorFilter = tint.takeIf { c == tintColor } ?: ColorFilter.tint(c).also { tint = it; tintColor = c }
}

/**
 * A creature's poses, painted once: side by side in one picture, in white so that they can be tinted to suit the
 * sky. While it flies, each frame only places pieces of that picture. A shape that changes is different: the
 * processor has to fill it in and send it to the graphics chip afresh every time, which is the slow way round.
 * [poses] are outlines around the creature's own centre and [scale] the pixels to one of their units.
 */
private class Flipbook(poses: List<Path>, scale: Float, paint: DrawScope.(Path) -> Unit = { drawPath(it, Color.White) }) {
    private val image: ImageBitmap
    private val cw: Int; private val ch: Int; private val ox: Int; private val oy: Int

    init {
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        for (p in poses) { val k = p.getBounds(); l = min(l, k.left); t = min(t, k.top); r = max(r, k.right); b = max(b, k.bottom) }
        // A clear margin around every pose, so neighbours never bleed into one another when a piece is turned or resized.
        ox = ceil(-l * scale).toInt() + MARGIN; oy = ceil(-t * scale).toInt() + MARGIN
        cw = ox + ceil(r * scale).toInt() + MARGIN; ch = oy + ceil(b * scale).toInt() + MARGIN
        image = ImageBitmap(cw * poses.size, ch)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size((cw * poses.size).toFloat(), ch.toFloat())) {
            poses.forEachIndexed { i, p -> withTransform({ translate(i * cw + ox.toFloat(), oy.toFloat()); scale(scale, scale, Offset.Zero) }) { paint(p) } }
        }
        image.prepareToDraw()
    }

    /** Places pose [i] with the creature's centre at the origin: move, turn and size the canvas first. */
    fun stamp(scope: DrawScope, i: Int, alpha: Float, tint: ColorFilter?) =
        scope.drawImage(image, IntOffset(i * cw, 0), IntSize(cw, ch), IntOffset(-ox, -oy), IntSize(cw, ch), alpha, colorFilter = tint, filterQuality = FilterQuality.Low)

    private companion object { const val MARGIN = 2 }
}

/** Which of [n] poses, spread evenly through a wingbeat, is nearest to place [q] in it. */
private fun pose(q: Float, n: Int) = (q * n + 0.5f).toInt() % n

/** Wing height through one beat, 1 at the top to 0 at the bottom: a quick downstroke, then a slower recovery. */
private fun beat(q: Float): Float = if (q < 0.4f) 0.5f + 0.5f * cos(PI_F * q / 0.4f) else 0.5f - 0.5f * cos(PI_F * (q - 0.4f) / 0.6f)

/** How far the hand of the wing lags the arm: it leads a touch on the way down and trails on the way back up. */
private fun flex(q: Float): Float = if (q < 0.4f) -0.18f * sin(PI_F * q / 0.4f) else 0.75f * sin(PI_F * (q - 0.4f) / 0.6f)

/**
 * How a flying bird is seen from the ground: from below, and usually at a slant. [heading] is how far it is turned
 * away from flying straight across (radians: positive away from us, negative toward) and [elevation] how high up
 * we're looking. These are the screen directions of its body, of the wing on our side held level, and of straight up.
 */
private class View(heading: Float, elevation: Float) {
    val bx = cos(heading); val by = sin(heading) * sin(elevation)
    val wx = sin(heading); val wy = -cos(heading) * sin(elevation)
    val uy = -cos(elevation)
}

/** A small bird, a goose and an owl from below: (how far along the body from tail to beak, half its width there), in body lengths. */
private val SMALL_PLAN = floatArrayOf(-0.5f, 0.05f, -0.3f, 0.05f, -0.08f, 0.12f, 0.14f, 0.125f, 0.3f, 0.09f, 0.4f, 0.075f, 0.5f, 0.012f)
private val GOOSE_PLAN = floatArrayOf(-0.44f, 0.03f, -0.36f, 0.075f, -0.15f, 0.115f, 0.05f, 0.11f, 0.2f, 0.055f, 0.42f, 0.036f, 0.57f, 0.052f, 0.67f, 0.012f)
private val OWL_PLAN = floatArrayOf(-0.5f, 0.10f, -0.42f, 0.14f, -0.2f, 0.18f, 0.05f, 0.20f, 0.22f, 0.18f, 0.36f, 0.20f, 0.47f, 0.15f, 0.52f, 0.04f)

/**
 * A distant bird, added to [p] as one small silhouette: the body seen along [v], and two wings that bend at the
 * wrist. [s] is a wing's length and [bodyLen] the body's, in wings; [q] is the place in a wingbeat (negative: wings
 * tucked, as small birds do between bursts), [lo] and [hi] the wing's angle at the bottom and top of a beat, and
 * [kink] how much the hand droops from the arm. [chord] is the wing's width and [blunt] rounds its tip (an owl's
 * are broad and round); [glide] holds the wings out instead of beating them.
 */
private fun farBird(
    o: Outline, p: Path, v: View, plan: FloatArray, x: Float, y: Float, s: Float, bodyLen: Float, dir: Float,
    q: Float, lo: Float, hi: Float, kink: Float, chord: Float = 0.36f, blunt: Float = 0f, glide: Float = 0f,
) {
    val bx = v.bx * dir; val by = v.by; val bl = max(1e-3f, sqrt(bx * bx + by * by))
    val nx = -by / bl; val ny = bx / bl // across the body: it looks as thick from any side
    val len = s * bodyLen; val k = plan.size / 2
    o.start()
    for (i in 0 until k) o.to(x + (bx * plan[i * 2] + nx * plan[i * 2 + 1]) * len, y + (by * plan[i * 2] + ny * plan[i * 2 + 1]) * len)
    for (i in k - 1 downTo 0) o.to(x + (bx * plan[i * 2] - nx * plan[i * 2 + 1]) * len, y + (by * plan[i * 2] - ny * plan[i * 2 + 1]) * len)
    o.into(p)
    if (q < 0f) return
    val a1 = mix(lo + (hi - lo) * beat(q), 0.22f, glide); val a2 = a1 - kink - flex(q) * (1f - glide)
    val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
    val sx = x + bx * len * 0.06f; val sy = y + by * len * 0.06f
    // A wing's width runs along the body, so from behind it is thin and from the side it is broad.
    val cx = bx * s * chord; val cy = by * s * chord
    var side = 1f
    repeat(2) {
        val wx = sx + s * 0.45f * side * v.wx * dir * c1; val wy = sy + s * 0.45f * (side * v.wy * c1 + v.uy * s1)
        val tx = wx + s * 0.55f * (side * v.wx * dir * c2 - 0.25f * bx); val ty = wy + s * 0.55f * (side * v.wy * c2 + v.uy * s2 - 0.25f * by)
        // Seen edge-on a wing still has some thickness: pad its width with a little across its own length.
        var px = -(ty - sy); var py = tx - sx
        val pl = max(1e-3f, sqrt(px * px + py * py)); val pad = s * 0.045f * (if (px * cx + py * cy < 0f) -1f else 1f) / pl
        px *= pad; py *= pad
        // Arm and hand are separate pieces sharing the wrist, so a wing can fold without its outline crossing itself.
        val lx = wx + cx * 0.35f + px; val ly = wy + cy * 0.35f + py; val rx = wx - cx * 0.5f - px; val ry = wy - cy * 0.5f - py
        o.start().tip(sx + cx * 0.4f + px, sy + cy * 0.4f + py).tip(lx, ly).tip(rx, ry).tip(sx - cx * 0.6f - px, sy - cy * 0.6f - py).into(p)
        o.start().tip(lx, ly)
        if (blunt > 0f) o.to(tx + (cx * 0.3f + px) * blunt, ty + (cy * 0.3f + py) * blunt).to(tx - (cx * 0.45f + px) * blunt, ty - (cy * 0.45f + py) * blunt) else o.tip(tx, ty)
        o.tip(rx, ry).into(p)
        side = -1f
    }
}

/** The proportions of a nearer bird, in lengths of its whole body. */
private class BirdForm(
    val body: FloatArray,                                            // the outline of body, head and tail from the side, beak toward +x
    val arm: Float, val hand: Float,                                 // the two halves of a wing
    val chord: Float, val tipChord: Float, val sweep: Float,         // its width at the body, how blunt its tip is, how far the tip rakes back
    val lo: Float, val hi: Float,                                    // wing angle at the bottom and top of a beat (radians)
)

private val SONGBIRD = BirdForm(
    floatArrayOf(
        0.50f, -0.025f, 1f, 0.41f, -0.075f, 0f, 0.365f, -0.125f, 0f, 0.28f, -0.16f, 0f, 0.17f, -0.135f, 0f, 0f, -0.125f, 0f, -0.20f, -0.085f, 0f, -0.36f, -0.05f, 0f,
        -0.57f, -0.06f, 1f, -0.53f, -0.005f, 0f, -0.57f, 0.045f, 1f, -0.34f, 0.035f, 0f, -0.22f, 0.085f, 0f, -0.03f, 0.14f, 0f, 0.20f, 0.125f, 0f, 0.335f, 0.055f, 0f, 0.41f, 0f, 0f,
    ),
    0.36f, 0.52f, 0.30f, 0.03f, 0.25f, -0.7f, 1.0f,
)

/** The same bird with a crest: a cardinal or a jay. */
private val CRESTED = BirdForm(
    floatArrayOf(
        0.49f, -0.025f, 1f, 0.41f, -0.075f, 0f, 0.37f, -0.13f, 0f, 0.30f, -0.17f, 0f, 0.15f, -0.285f, 1f, 0.15f, -0.135f, 0f, 0f, -0.125f, 0f, -0.20f, -0.085f, 0f, -0.36f, -0.05f, 0f,
        -0.62f, -0.06f, 1f, -0.58f, -0.005f, 0f, -0.62f, 0.045f, 1f, -0.34f, 0.035f, 0f, -0.22f, 0.085f, 0f, -0.03f, 0.14f, 0f, 0.20f, 0.125f, 0f, 0.335f, 0.055f, 0f, 0.41f, 0f, 0f,
    ),
    0.36f, 0.52f, 0.30f, 0.03f, 0.25f, -0.7f, 1.0f,
)

private val CROW = BirdForm(
    floatArrayOf(
        0.53f, -0.01f, 1f, 0.40f, -0.07f, 0f, 0.33f, -0.115f, 0f, 0.24f, -0.135f, 0f, 0.13f, -0.12f, 0f, -0.02f, -0.115f, 0f, -0.22f, -0.08f, 0f, -0.38f, -0.065f, 0f,
        -0.58f, -0.085f, 1f, -0.61f, 0f, 0f, -0.57f, 0.075f, 1f, -0.36f, 0.045f, 0f, -0.22f, 0.085f, 0f, -0.03f, 0.125f, 0f, 0.20f, 0.11f, 0f, 0.33f, 0.05f, 0f, 0.41f, 0.02f, 0f,
    ),
    0.45f, 0.58f, 0.32f, 0.14f, 0.15f, -0.6f, 0.8f,
)

/** Nearer birds are seen almost side-on, and a little from below. */
private val SIDE_ON = View(0.3f, 0.38f)

/**
 * One wing, added to [p]: hinged at the shoulder at angle [a1] and bent at the wrist to [a2]; [k] is 1 for the wing on
 * our side, -1 for the other. Arm and hand are separate pieces sharing the wrist, so a wing can fold without its
 * outline crossing itself.
 */
private fun wingInto(o: Outline, p: Path, f: BirdForm, a1: Float, a2: Float, k: Float) {
    val v = SIDE_ON
    val sx = 0.06f; val sy = -0.05f
    val wx = sx + f.arm * k * v.wx * cos(a1); val wy = sy + f.arm * (k * v.wy * cos(a1) + v.uy * sin(a1))
    val tx = wx + f.hand * (k * v.wx * cos(a2) - f.sweep); val ty = wy + f.hand * (k * v.wy * cos(a2) + v.uy * sin(a2))
    val c = f.chord
    o.start().tip(sx + c * 0.35f, sy).tip(wx + c * 0.36f, wy).tip(wx - c * 0.62f, wy).to((sx + wx) / 2f - c * 0.72f, (sy + wy) / 2f).tip(sx - c * 0.65f, sy).into(p)
    o.start().tip(wx + c * 0.36f, wy)
    if (f.tipChord > 0.05f) o.to(tx + f.tipChord * 0.5f, ty).to(tx - f.tipChord * 0.5f, ty) else o.tip(tx, ty)
    o.tip(wx - c * 0.62f, wy).into(p)
}

/**
 * A nearer bird, added to [p] in lengths of its own body: body, head and tail as one outline, with two wings that
 * hinge at the shoulder and bend at the wrist. [q] is the place in a wingbeat (negative: wings tucked).
 */
private fun birdInto(o: Outline, p: Path, f: BirdForm, q: Float) {
    o.shape(f.body).into(p)
    if (q < 0f) return
    val a1 = f.lo + (f.hi - f.lo) * beat(q); val a2 = a1 - flex(q)
    wingInto(o, p, f, a1, a2, -1f)
    wingInto(o, p, f, a1, a2, 1f)
}

// =====================================================================================
//  The birds.
// =====================================================================================

/** Geese in a V: one arm longer than the other, each bird holding its place loosely, a slow beat passing down the line. */
private class Geese(r: Random, season: Season) : Visit(Creature.GEESE) {
    private val n = 9 + r.nextInt(9)
    private val wing = r.between(17f, 22f)
    private val gap = wing * 2.2f
    private val back = FloatArray(n); private val side = FloatArray(n); private val beatAt = FloatArray(n); private val drift = FloatArray(n)
    private val period = r.between(0.50f, 0.60f)
    // Facing the equator, as the sky does: in autumn they are leaving (heading away), in spring coming back.
    private val view = View(if (season == Season.SPRING) -0.8f else 0.8f, 0.45f)
    private val course: Course
    override val duration = r.between(30f, 42f)
    override val count get() = n
    override val reach = 40f
    private var book: Flipbook? = null
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
            course.y(u, h) + (side[i] + sin(t * 0.37f + drift[i] * 1.7f) * gap * 0.12f) * sc,
        )
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val o = Outline(); val s = wing * h / 1080f
        book = Flipbook(List(BEATS) { k -> Path().also { farBird(o, it, view, GOOSE_PLAN, 0f, 0f, s, 1.3f, course.dir, k / BEATS.toFloat(), -0.35f, 0.7f, 0.4f) } }, 1f)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val a = 0.8f * alpha * lowFade(course.y(t / duration, h), h); val tint = Scratch.tint(ink)
        for (i in 0 until n) {
            val p = place(i, t, w, h)
            withTransform({ translate(p.x, p.y) }) { pages.stamp(this, pose(frac(t / period + beatAt[i]), BEATS), a, tint) }
        }
    }

    private companion object { const val BEATS = 12 } // poses through one wingbeat
}

/** A loose flock of small birds in bounding flight: a burst of wingbeats on the way up, wings tucked on the way down. */
private class Flock(r: Random) : Visit(Creature.FLOCK) {
    private val n = 10 + r.nextInt(15)
    private val spreadX = r.between(110f, 170f); private val spreadY = r.between(40f, 70f)
    private val ox = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadX }
    private val oy = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * spreadY }
    private val wing = FloatArray(n) { r.between(8f, LARGEST) }
    private val period = FloatArray(n) { r.between(0.62f, 0.8f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val bound = FloatArray(n) { r.between(7f, 12f) }
    private val sway = r.between(0f, TAU)
    private val view = View(r.between(0.7f, 1f) * r.side(), 0.35f)
    private val course = course(r, 0.07f, 0.36f, spreadX * 1.4f + 40f, spreadX * 1.4f + 40f, bow = 0.07f)
    override val duration = r.between(9f, 13f)
    override val count get() = n
    override val reach = 18f
    private var book: Flipbook? = null
    private var bakedFor = 0f

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        // The flock swells and turns a little as it goes.
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
        val o = Outline(); val s = LARGEST * h / 1080f
        // Eight poses through a wingbeat, then one with the wings tucked.
        book = Flipbook(List(BEATS + 1) { k -> Path().also { farBird(o, it, view, SMALL_PLAN, 0f, 0f, s, 1.25f, course.dir, if (k < BEATS) k / BEATS.toFloat() else -1f, -0.55f, 0.85f, 0.25f) } }, 1f)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val a = 0.82f * alpha * lowFade(course.y(t / duration, h), h); val tint = Scratch.tint(ink)
        for (i in 0 until n) {
            val at = place(i, t, w, h)
            val p = frac(t / period[i] + phase[i]); val k = wing[i] / LARGEST
            withTransform({ translate(at.x, at.y); scale(k, k, Offset.Zero) }) {
                pages.stamp(this, if (p < 0.6f) pose(frac(p / 0.6f * 3f), BEATS) else BEATS, a, tint)
            }
        }
    }

    private companion object { const val BEATS = 8; const val LARGEST = 11.5f } // poses through one wingbeat; the longest wing
}

private enum class Flight { BOUNDING, ROWING }

/** One to a few nearer birds, one behind another: songbirds bounding along, or crows rowing steadily. */
private class Fliers(
    creature: Creature, r: Random, private val form: BirdForm, private val n: Int, minLen: Float, maxLen: Float,
    minSeconds: Float, maxSeconds: Float, private val flight: Flight, private val tint: Color?, stagger: Float,
) : Visit(creature) {
    private val len = FloatArray(n) { r.between(minLen, maxLen) }
    private val delay = FloatArray(n) { if (it == 0) 0f else it * r.between(0.35f, 1f) * stagger }
    private val cross = FloatArray(n).also { val base = r.between(minSeconds, maxSeconds); for (i in 0 until n) it[i] = base * r.between(0.95f, 1.06f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-60f, 60f) }
    private val period = FloatArray(n) { if (flight == Flight.BOUNDING) r.between(0.62f, 0.74f) else r.between(0.38f, 0.46f) }
    private val phase = FloatArray(n) { r.nextFloat() }
    private val bob = FloatArray(n) { if (flight == Flight.BOUNDING) r.between(14f, 22f) else 3.5f }
    override val reach = maxLen * 1.5f + 24f
    private val course = course(r, 0.07f, 0.36f, reach + 60f, reach + 60f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    private val longest = maxLen
    private val beats = if (flight == Flight.BOUNDING) 8 else 12 // poses through one wingbeat
    private var book: Flipbook? = null
    private var bakedFor = 0f

    /** The place in a wingbeat at [t]; negative while a small bird has its wings tucked between bursts. */
    private fun wingbeat(i: Int, t: Float): Float {
        val p = frac(t / period[i] + phase[i])
        return if (flight == Flight.ROWING) p else if (p < 0.6f) frac(p / 0.6f * 3f) else -1f
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = ((t - delay[i]) / cross[i]).coerceIn(0f, 1f)
        val p = frac(t / period[i] + phase[i])
        // Bounding: climbing through the burst of beats, falling while tucked. Rowing: a small lift on each downstroke.
        val rise = -bob[i] * cos(TAU * (p - if (flight == Flight.BOUNDING) 0.6f else 0.3f))
        return Offset(course.x(u, w, sc), course.y(u, h) + (lift[i] + rise) * sc)
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val o = Outline()
        val poses = MutableList(beats) { k -> Path().also { birdInto(o, it, form, k / beats.toFloat()) } }
        if (flight == Flight.BOUNDING) poses += Path().also { birdInto(o, it, form, -1f) } // and one with the wings tucked
        book = Flipbook(poses, longest * h / 1080f)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val colour = Scratch.tint(tint?.let { lerp(ink, it, 0.55f) } ?: ink)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val p = place(i, t, w, h); val next = place(i, t + 0.04f, w, h)
            // The body tips up as it climbs and down as it falls, a little less than its path does.
            val pitch = atan2(next.y - p.y, abs(next.x - p.x)) * DEG * 0.6f
            val q = wingbeat(i, t); val k = len[i] / longest
            withTransform({ translate(p.x, p.y); rotate(pitch * course.dir, Offset.Zero); scale(k * course.dir, k, Offset.Zero) }) {
                pages.stamp(this, if (q < 0f) beats else pose(q, beats), 0.86f * alpha * lowFade(p.y, h), colour)
            }
        }
    }
}

/** A soaring hawk from below, head toward the top: (x, y, sharp) for its right half. One unit is half its wingspan. */
private fun hawkShape(): Path {
    val half = floatArrayOf(
        0f, -0.30f, 0f, 0.055f, -0.26f, 0f, 0.075f, -0.19f, 0f, 0.17f, -0.175f, 0f, 0.50f, -0.215f, 0f, 0.80f, -0.17f, 0f,
        0.975f, -0.115f, 1f, 0.905f, -0.065f, 0f, 1f, -0.03f, 1f, 0.905f, 0.005f, 0f, 0.985f, 0.05f, 1f, 0.875f, 0.065f, 0f, // the spread "fingers" at the wingtip
        0.94f, 0.125f, 1f, 0.825f, 0.115f, 0f, 0.865f, 0.185f, 1f,
        0.70f, 0.16f, 0f, 0.42f, 0.165f, 0f, 0.17f, 0.13f, 0f, 0.105f, 0.21f, 0f, 0.20f, 0.46f, 1f, 0f, 0.52f, 0f,           // trailing edge and the fanned tail
    )
    return Path().also { Outline().shape(half, mirror = true).into(it) }
}

/** A hawk circling on a thermal, drifting slowly across the sky on set wings. */
private class Hawk(r: Random) : Visit(Creature.HAWK) {
    private val span = r.between(56f, 72f)
    private val radius = r.between(110f, 160f)
    private val turn = r.between(9f, 12f) * r.side() // seconds for one circle; the sign is which way it wheels
    private val dir = r.side()
    private val y0 = r.between(0.14f, 0.26f); private val y1 = r.between(0.14f, 0.26f)
    private val start = r.between(0f, TAU)
    override val duration = r.between(38f, 52f)
    override val count get() = 1
    override val reach = 84f
    private var book: Flipbook? = null
    private var bakedFor = 0f

    private fun angle(t: Float) = start + TAU * t / turn

    // Painted a little larger than it flies on the far side of its circle, at the size of the near side.
    override fun prepare(h: Float) {
        if (bakedFor == h) return
        book = Flipbook(listOf(hawkShape()), span * NEAREST * h / 1080f)
        bakedFor = h
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration; val m = (radius + reach) * sc
        val cx = if (dir > 0f) -m + u * (w + 2f * m) else w + m - u * (w + 2f * m)
        // Seen from the ground its circles are flattened.
        return Offset(cx + radius * sc * cos(angle(t)), h * mix(y0, y1, u) + radius * sc * 0.5f * sin(angle(t)))
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val p = place(0, t, w, h); val next = place(0, t + 0.05f, w, h)
        val heading = atan2(next.y - p.y, next.x - p.x) * DEG
        val a = angle(t)
        val near = (1f + (NEAREST - 1f) * sin(a)) / NEAREST // the low side of its circle is the near side
        val bank = 0.84f + 0.10f * cos(a + 0.6f)            // banked wings look shorter
        withTransform({ translate(p.x, p.y); rotate(heading + 90f, Offset.Zero); scale(near * bank, near, Offset.Zero) }) {
            pages.stamp(this, 0, 0.76f * alpha * lowFade(p.y, h), Scratch.tint(ink))
        }
    }

    private companion object { const val NEAREST = 1.1f }
}

/**
 * A murmuration of starlings. The flock is a sheet: every bird chases the same wandering point, each a moment
 * behind it and each on a slightly different line. Where the point runs straight the sheet is drawn out thin;
 * where it doubles back the sheet folds over itself and the birds pile into dark bands, which is what makes the
 * real thing seem to pulse and pour.
 */
private class Starlings(r: Random) : Visit(Creature.STARLINGS) {
    private val n = 900 + r.nextInt(300)
    // For each bird and each of the four swings of its path: where in the swing it is, as a cosine and a sine
    // already multiplied by the swing's size. Then a frame costs eight sines for the whole flock, not four a bird.
    private val swing = FloatArray(n * 8)
    private val ox: FloatArray; private val oy: FloatArray
    private val rangeX: Float
    private val dir: Float; private val y0: Float
    // Each bird is one tiny dart of a triangle: its three corners (x, y) around its place, every bird turned its own way.
    private val dart = FloatArray(n * 6)
    private var mesh: Vertices? = null
    private var inked = 0
    override val duration: Float
    override val count get() = n
    override val reach = 8f

    init {
        val lag = FloatArray(n) { 1.15f * r.nextFloat() }         // seconds behind the front of the flock
        val line = FloatArray(n) { r.between(-1.25f, 1.25f) }     // which line through the sky it follows
        ox = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * 22f }
        oy = FloatArray(n) { (r.nextFloat() + r.nextFloat() - 1f) * 16f }
        val f = FloatArray(4) { r.between(0f, TAU) }
        rangeX = r.between(280f, 360f); val rangeY = r.between(95f, 130f)
        dir = r.side(); y0 = r.between(0.2f, 0.27f)
        for (i in 0 until n) {
            val a = r.between(0f, TAU); val len = r.between(2.1f, 2.9f)
            dart[i * 6] = len * cos(a); dart[i * 6 + 1] = len * sin(a)
            dart[i * 6 + 2] = len * 0.8f * cos(a + 2.5f); dart[i * 6 + 3] = len * 0.8f * sin(a + 2.5f)
            dart[i * 6 + 4] = len * 0.8f * cos(a - 2.5f); dart[i * 6 + 5] = len * 0.8f * sin(a - 2.5f)
        }
        duration = r.between(26f, 34f)
        // Across: two swings of the wandering point the flock chases; up and down: two more. Each bird is [lag] behind
        // the point and [line] to one side of its path.
        for (i in 0 until n) {
            val j = i * 8
            for (k in 0 until 4) {
                val at = f[k] - RATE[k] * lag[i] + SPREAD[k] * line[i]
                val size = (if (k < 2) rangeX else rangeY) * SHARE[k]
                swing[j + k * 2] = size * cos(at); swing[j + k * 2 + 1] = size * sin(at)
            }
        }
    }

    // What every bird shares at one moment, worked out once for the frame.
    private var tunedTo = Float.NaN
    private val sines = FloatArray(4); private val cosines = FloatArray(4)
    private var across = 0f

    private fun tune(t: Float) {
        for (k in 0 until 4) { sines[k] = sin(RATE[k] * t); cosines[k] = cos(RATE[k] * t) }
        val u = t / duration
        across = u + 0.6f / TAU * sin(TAU * u) // quick in from the side, lingering over the middle, quick away
        tunedTo = t
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        if (t != tunedTo) tune(t)
        val sc = h / 1080f; val m = (rangeX + 40f) * sc; val j = i * 8
        val along = -m + across * (w + 2f * m)
        // sin(a + b) = sin a cos b + cos a sin b, with the bird's own b already worked out
        val px = sines[0] * swing[j] + cosines[0] * swing[j + 1] + sines[1] * swing[j + 2] + cosines[1] * swing[j + 3]
        val py = sines[2] * swing[j + 4] + cosines[2] * swing[j + 5] + sines[3] * swing[j + 6] + cosines[3] * swing[j + 7]
        return Offset((if (dir > 0f) along else w - along) + (px + ox[i]) * sc, h * y0 + (py + oy[i]) * sc)
    }

    override fun prepare(h: Float) {
        if (mesh != null) return
        val blank = List(n * 3) { Offset.Zero }
        mesh = Vertices(VertexMode.Triangles, blank, blank, List(n * 3) { Color.Black }, emptyList())
    }

    // The whole flock goes to the graphics chip as one batch of triangles: one drawing instruction, however many birds.
    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        prepare(h)
        val m = mesh ?: return
        val at = m.positions
        for (i in 0 until n) {
            val o = place(i, t, w, h); val j = i * 6
            at[j] = o.x + dart[j] * sc; at[j + 1] = o.y + dart[j + 1] * sc
            at[j + 2] = o.x + dart[j + 2] * sc; at[j + 3] = o.y + dart[j + 3] * sc
            at[j + 4] = o.x + dart[j + 4] * sc; at[j + 5] = o.y + dart[j + 5] * sc
        }
        val colour = ink.copy(alpha = ink.alpha * 0.8f * alpha * lowFade(h * y0, h)).toArgb()
        if (colour != inked) { m.colors.fill(colour); inked = colour }
        drawContext.canvas.drawVertices(m, BlendMode.Dst, Scratch.plain) // Dst: the colour is each corner's own
    }

    private companion object {
        val RATE = floatArrayOf(0.47f, 1.03f, 0.61f, 1.27f)    // how fast each swing goes, radians a second
        val SHARE = floatArrayOf(0.56f, 0.44f, 0.6f, 0.4f)     // how much of the flock's range each swing takes
        val SPREAD = floatArrayOf(0.35f, 0.8f, 0.75f, -0.6f)   // how far a bird's line shifts it along each swing
    }
}

// =====================================================================================
//  After dark.
// =====================================================================================

/**
 * An owl on a low hunting pass, coming toward us at a slant: a few deep, slow beats, then a long glide on broad
 * round wings, dipping across the middle of the sky and lifting away.
 */
private class Owl(r: Random) : Visit(Creature.OWL) {
    private val wing = r.between(80f, 94f)
    private val view = View(r.between(-1f, -0.75f), 0.5f)
    private val phase = r.nextFloat()
    override val reach = 130f
    private val course = Course(r.side(), r.between(0.07f, 0.13f), r.between(0.08f, 0.15f), r.between(0.09f, 0.15f), reach + 12f, reach + 12f)
    override val duration = r.between(6.2f, 7.8f)
    override val count get() = 1

    /** Its flight repeats every 2.9 seconds: two beats, then the glide. */
    private fun cycle(t: Float) = frac(t / 2.9f + phase)
    private fun glide(t: Float): Float { val p = cycle(t); return ramp(0.52f, 0.60f, p) * (1f - ramp(0.92f, 1f, p)) }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration
        val lift = -7f * cos(TAU * (frac(cycle(t) / 0.52f * 2f) - 0.3f)) * (1f - glide(t)) // each downstroke lifts it a little
        return Offset(course.x(u, w, sc), course.y(u, h) + lift * sc)
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        val p = place(0, t, w, h); val c = cycle(t)
        val birds = Scratch.a.apply { rewind() }
        farBird(Scratch.outline, birds, view, OWL_PLAN, p.x, p.y, wing * sc, 0.86f, course.dir, if (c < 0.52f) frac(c / 0.52f * 2f) else 0f, -0.5f, 0.75f, 0.2f, chord = 0.5f, blunt = 1f, glide = glide(t))
        drawPath(birds, ink, 0.8f * alpha * lowFade(p.y, h))
    }
}

/** A bat seen head-on, wings at angle [a] above level: an arm, a long hand, and a scalloped skin between the fingers. */
private fun batInto(o: Outline, p: Path, a: Float) {
    val sx = 0.07f; val sy = -0.02f
    val rx = sx + 0.40f * cos(a + 0.45f); val ry = sy - 0.40f * sin(a + 0.45f)       // wrist
    val tx = rx + 0.62f * cos(a - 0.30f); val ty = ry - 0.62f * sin(a - 0.30f)       // wingtip
    val gx = rx + 0.50f * cos(a - 1.00f); val gy = ry - 0.50f * sin(a - 1.00f)       // the next two fingers
    val hx = rx + 0.40f * cos(a - 1.60f); val hy = ry - 0.40f * sin(a - 1.60f)
    val fx = 0.05f; val fy = 0.30f                                                   // foot
    var k = 1f
    repeat(2) {
        o.start().tip(k * sx, sy).tip(k * rx, ry - 0.02f).tip(k * tx, ty)
            .to(k * mix((tx + gx) / 2f, rx, 0.35f), mix((ty + gy) / 2f, ry, 0.35f)).tip(k * gx, gy)
            .to(k * mix((gx + hx) / 2f, rx, 0.35f), mix((gy + hy) / 2f, ry, 0.35f)).tip(k * hx, hy)
            .to(k * mix((hx + fx) / 2f, rx, 0.25f), mix((hy + fy) / 2f, ry, 0.25f)).tip(k * fx, fy)
            .into(p)
        k = -1f
    }
    // Body, head and ears.
    o.start().to(0f, 0.34f).to(-0.085f, 0.17f).to(-0.085f, -0.05f).to(-0.075f, -0.17f).tip(-0.085f, -0.31f).to(-0.025f, -0.215f)
        .to(0.025f, -0.215f).tip(0.085f, -0.31f).to(0.075f, -0.17f).to(0.085f, -0.05f).to(0.085f, 0.17f).into(p)
}

/** A few bats, each on its own jinking path after insects. */
private class Bats(r: Random) : Visit(Creature.BATS) {
    private val n = 2 + r.nextInt(4)
    private val wing = FloatArray(n) { r.between(21f, LARGEST) }
    private val delay = FloatArray(n) { if (it == 0) 0f else r.between(0.3f, 3.6f) }
    private val cross = FloatArray(n) { r.between(4.5f, 6.5f) }
    private val dir = FloatArray(n) { r.side() }
    private val y0 = FloatArray(n) { r.between(0.08f, 0.36f) }
    private val y1 = FloatArray(n) { r.between(0.08f, 0.36f) }
    private val wobble = FloatArray(n * 8) { if (it % 2 == 0) r.between(2.4f, 6.2f) else r.between(0f, TAU) } // four swerves each: a rate and a phase
    private val period = FloatArray(n) { r.between(0.16f, 0.2f) }
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    override val reach = 40f
    private var book: Flipbook? = null
    private var bakedFor = 0f

    private fun swerve(i: Int, k: Int, t: Float) = sin(wobble[i * 8 + k * 2] * t + wobble[i * 8 + k * 2 + 1])

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = ((t - delay[i]) / cross[i]).coerceIn(0f, 1f); val m = (reach + 80f) * sc
        val along = -m + u * (w + 2f * m)
        return Offset(
            (if (dir[i] > 0f) along else w - along) + (45f * swerve(i, 0, t) + 28f * swerve(i, 1, t)) * sc,
            h * mix(y0[i], y1[i], u) + (38f * swerve(i, 2, t) + 22f * swerve(i, 3, t)) * sc,
        )
    }

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val o = Outline()
        book = Flipbook(List(BEATS) { k -> Path().also { batInto(o, it, mix(-0.5f, 0.8f, beat(k / BEATS.toFloat()))) } }, LARGEST * h / 1080f)
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val tint = Scratch.tint(ink)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val k = wing[i] / LARGEST
            // It leans into each swerve.
            val lean = 17f * sin(wobble[i * 8] * t + wobble[i * 8 + 1] + 1.3f)
            withTransform({ translate(at.x, at.y); rotate(lean, Offset.Zero); scale(k, k, Offset.Zero) }) {
                pages.stamp(this, pose(frac(t / period[i] + i * 0.37f), BEATS), 0.85f * alpha * lowFade(at.y, h), tint)
            }
        }
    }

    private companion object { const val BEATS = 8; const val LARGEST = 28f } // poses through one wingbeat; the longest wing
}

/** A flying squirrel from below, nose toward the top: (x, y, sharp) for its right half. One unit is its body. */
private fun gliderShape(): Path {
    val half = floatArrayOf(
        0f, -0.76f, 0f, 0.09f, -0.68f, 0f, 0.145f, -0.57f, 0f, 0.21f, -0.53f, 1f, 0.125f, -0.45f, 0f, 0.21f, -0.40f, 0f,          // nose, cheek, ear, shoulder
        0.52f, -0.50f, 0f, 0.70f, -0.56f, 1f, 0.66f, -0.43f, 0f, 0.585f, -0.05f, 0f, 0.64f, 0.34f, 0f, 0.73f, 0.45f, 1f, 0.57f, 0.48f, 0f, // front paw, the skin's edge, hind paw
        0.22f, 0.42f, 0f, 0.085f, 0.50f, 0f, 0.13f, 0.72f, 0f, 0.14f, 1.02f, 0f, 0.095f, 1.22f, 0f, 0f, 1.30f, 0f,                 // rump and the flat tail
    )
    return Path().also { Outline().shape(half, mirror = true).into(it) }
}

/** A flying squirrel's glide: in over the top of the screen, down a long shallow slope, and out at the side. */
private class Glider(r: Random) : Visit(Creature.FLYING_SQUIRREL) {
    private val body = r.between(44f, 54f)
    private val dir = r.side()
    private val from = r.between(0.16f, 0.4f)     // where along the top it comes in (from the left edge, for one gliding right)
    private val to = r.between(0.34f, 0.46f)      // how far down the side it leaves
    private val sag = r.between(0.02f, 0.05f)
    private val roll = r.between(0f, TAU)
    override val duration = r.between(3.4f, 4.2f)
    override val count get() = 1
    override val reach = 76f
    private var book: Flipbook? = null
    private var bakedFor = 0f

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        book = Flipbook(listOf(gliderShape()), body * h / 1080f)
        bakedFor = h
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration; val m = reach * sc
        val x = mix(from * w, w + m * 1.1f, u)
        return Offset(if (dir > 0f) x else w - x, mix(-m * 1.1f, to * h, u) + sag * h * 4f * u * (1f - u))
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = book ?: return
        val p = place(0, t, w, h); val next = place(0, t + 0.05f, w, h)
        val heading = atan2(next.y - p.y, next.x - p.x) * DEG + 5f * sin(t * 2.1f + roll)
        val tilt = 0.9f + 0.1f * cos(t * 2.1f + roll) // rocking from side to side as it steers
        withTransform({ translate(p.x, p.y); rotate(heading + 90f, Offset.Zero); scale(tilt, 1f, Offset.Zero) }) {
            pages.stamp(this, 0, 0.8f * alpha * lowFade(p.y, h), Scratch.tint(ink))
        }
    }
}

// =====================================================================================
//  Squirrels and butterflies.
// =====================================================================================

/** A tapering limb from the body at ([x0], [y0]) to a paw at ([x1], [y1]). */
private fun limb(o: Outline, p: Path, x0: Float, y0: Float, x1: Float, y1: Float, w0: Float, w1: Float) {
    val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-4f, sqrt(dx * dx + dy * dy)); val ux = dx / l; val uy = dy / l
    o.start().to(x0 - uy * w0, y0 + ux * w0).to(x1 - uy * w1, y1 + ux * w1).to(x1 + ux * w1 * 1.5f, y1 + uy * w1 * 1.5f)
        .to(x1 + uy * w1, y1 - ux * w1).to(x0 + uy * w0, y0 - ux * w0).into(p)
}

private val TAIL_HALF = floatArrayOf(0.055f, 0.14f, 0.175f, 0.165f, 0.105f)

/**
 * The tail: a plume streaming back from the rump at [lift] above straight back, swelling toward the end, with a
 * slow wave running down it. [puff] widens it.
 */
private fun tailInto(o: Outline, p: Path, lift: Float, wave: Float, puff: Float) {
    val jx = Scratch.jx; val jy = Scratch.jy
    jx[0] = -0.47f; jy[0] = -0.10f
    var a = lift
    for (k in 1..4) {
        jx[k] = jx[k - 1] - 0.31f * cos(a); jy[k] = jy[k - 1] - 0.31f * sin(a)
        a += -0.04f + 0.18f * sin(wave - k * 0.9f)
    }
    o.start()
    for (pass in 0..1) for (step in 0..4) {
        val k = if (pass == 0) step else 4 - step
        val a0 = max(0, k - 1); val b0 = min(4, k + 1)
        val dx = jx[b0] - jx[a0]; val dy = jy[b0] - jy[a0]; val l = max(1e-4f, sqrt(dx * dx + dy * dy))
        val half = TAIL_HALF[k] * puff * if (pass == 0) 1f else -1f
        o.to(jx[k] - dy / l * half, jy[k] + dx / l * half)
        if (pass == 0 && k == 4) o.to(jx[4] + (jx[4] - jx[3]) * 0.4f * puff, jy[4] + (jy[4] - jy[3]) * 0.4f * puff) // the rounded end
    }
    o.into(p)
}

/**
 * A squirrel in mid-leap from the side, nose to the right; one unit is nose to rump. [reach] runs from 0, stretched
 * flat out, to 1, forepaws reaching down for the landing and hind legs swinging under.
 */
private fun squirrelInto(o: Outline, p: Path, reach: Float, tailLift: Float, wave: Float) {
    // Head, back and belly in one line: nose, brow, ear, nape, the arch of the back, rump, belly, chest, jaw.
    o.start().to(0.80f, -0.01f).to(0.72f, -0.10f).to(0.62f, -0.19f).to(0.56f, -0.23f).tip(0.52f, -0.37f).to(0.46f, -0.23f).to(0.38f, -0.19f)
        .to(0.13f, -0.22f).to(-0.15f, -0.24f).to(-0.38f, -0.19f).to(-0.51f, -0.08f).to(-0.47f, 0.07f).to(-0.25f, 0.15f).to(0f, 0.15f)
        .to(0.28f, 0.12f).to(0.45f, 0.07f).to(0.61f, 0.09f).to(0.75f, 0.05f).into(p)
    o.start().to(0.63f, -0.19f).tip(0.61f, -0.34f).to(0.54f, -0.21f).into(p) // the other ear
    val fore = mix(0.30f, 0.62f, reach)
    limb(o, p, 0.30f, 0.06f, 0.30f + 0.45f * cos(fore), 0.06f + 0.45f * sin(fore), 0.085f, 0.04f)
    limb(o, p, 0.25f, 0.05f, 0.25f + 0.43f * cos(fore - 0.3f), 0.05f + 0.43f * sin(fore - 0.3f), 0.08f, 0.04f)
    val hind = mix(0.26f, 0.85f, reach)
    limb(o, p, -0.38f, 0.05f, -0.38f - 0.58f * cos(hind), 0.05f + 0.58f * sin(hind), 0.13f, 0.045f)
    limb(o, p, -0.33f, 0.04f, -0.33f - 0.55f * cos(hind - 0.24f), 0.04f + 0.55f * sin(hind - 0.24f), 0.12f, 0.045f)
    tailInto(o, p, tailLift, wave, 1f)
}

/**
 * A squirrel's leap across a top corner of the screen: off a branch just above the frame, down in an arc, and onto
 * a tree just beside it. Shown at about half speed, as a nature film would. Sometimes a second one is chasing it.
 */
private class Squirrels(r: Random) : Visit(Creature.SQUIRREL) {
    private val n = if (r.nextFloat() < 0.35f) 2 else 1
    private val dir = r.side()
    private val body = FloatArray(n) { r.between(62f, 76f) }
    private val delay = FloatArray(n) { it * r.between(0.4f, 0.55f) }
    private val span = r.between(0.36f, 0.46f)     // how much of the screen's width the leap covers
    private val drop = r.between(0.30f, 0.38f)     // how far down the side it has fallen by the edge
    private val air = r.between(1.15f, 1.3f)       // seconds from the branch to the edge of the screen
    private val stray = FloatArray(n) { if (it == 0) 0f else r.between(-0.03f, 0.03f) }
    private val wave = r.between(0f, TAU)
    override val reach = 150f
    override val count get() = n
    override val everyFrame get() = true

    /** The leap carries on past the edge until the tail is out of sight too, on any screen at least as wide as 5:4. */
    private val whole = air * (1f + (2.3f * 76f / 1350f + 0.03f) / span)
    override val duration = delay[n - 1] + whole

    private fun seconds(i: Int, t: Float) = (t - delay[i]).coerceIn(0f, whole)

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val s = seconds(i, t) / air; val b = body[i] * h / 1080f
        // From the top of its arc, just above the frame: across at a steady pace, down faster and faster.
        val x = (1f - span + stray[i] + span * s) * w
        val top = -0.68f * b
        return Offset(if (dir > 0f) x else w - x, top + (drop * h - top) * s * s)
    }

    /** How steeply it is falling, in radians. */
    private fun fall(i: Int, t: Float, w: Float, h: Float): Float {
        val p = place(i, t, w, h); val next = place(i, t + 0.03f, w, h)
        return atan2(next.y - p.y, abs(next.x - p.x))
    }

    // The body follows the arc, nose down, but less steeply than the path; the tail streams back along the path itself.
    private fun pitch(fall: Float) = fall * 0.62f

    // Whatever the legs and tail are doing, the pose stays inside this box around its place (in body lengths, nose toward +x).
    override fun box(i: Int, t: Float, w: Float, h: Float): Rect {
        val p = place(i, t, w, h); val b = body[i] * h / 1080f
        val a = pitch(fall(i, t, w, h)); val c = cos(a); val s = sin(a)
        var l = Float.MAX_VALUE; var u = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val lx = if (k % 2 == 0) -2.1f else 0.86f; val ly = if (k < 2) -1.0f else 0.62f
            val x = (lx * c - ly * s) * dir; val y = lx * s + ly * c
            l = min(l, x); rr = max(rr, x); u = min(u, y); d = max(d, y)
        }
        return Rect(p.x + l * b, p.y + u * b, p.x + rr * b, p.y + d * b)
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        val coat = lerp(ink, Color(0xFF6E6257), 0.3f)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + whole) continue
            val at = place(i, t, w, h); val b = body[i] * sc
            val falling = fall(i, t, w, h)
            val reach = ramp(0.7f, 1.15f, seconds(i, t) / air)
            val tailLift = 0.1f + (falling - pitch(falling)) * 0.6f
            val swish = wave + i * 1.7f + t * 6f
            val a = alpha * lowFade(at.y, h)
            withTransform({ translate(at.x, at.y); rotate(pitch(falling) * DEG * dir, Offset.Zero); scale(b * dir, b, Offset.Zero) }) {
                // Light catches the long hairs of the tail: a paler fringe around it.
                val fringe = Scratch.b.apply { rewind() }
                tailInto(Scratch.outline, fringe, tailLift, swish, 1.4f)
                drawPath(fringe, coat, 0.3f * a)
                val p = Scratch.a.apply { rewind() }
                squirrelInto(Scratch.outline, p, reach, tailLift, swish)
                drawPath(p, coat, 0.9f * a)
            }
        }
    }
}

/** One side of a butterfly from above, head toward the top: forewing and hindwing. One unit is a forewing. */
private fun butterflyWings(): Path = Path().also {
    val o = Outline()
    o.start().to(0.03f, -0.10f).to(0.50f, -0.62f).to(0.95f, -0.80f).to(1.0f, -0.52f).to(0.80f, -0.16f).to(0.40f, -0.02f).to(0.03f, 0.04f).into(it)
    o.start().to(0.03f, -0.02f).to(0.55f, -0.08f).to(0.80f, 0.22f).to(0.66f, 0.60f).to(0.30f, 0.66f).to(0.05f, 0.30f).into(it)
}

/** One or two butterflies wandering across: wings opening and closing, each beat a small hop upward. */
private class Butterflies(r: Random, season: Season) : Visit(Creature.BUTTERFLIES) {
    private val n = 1 + r.nextInt(2)
    // Monarchs on their autumn passage; whites, sulphurs and blues through spring and summer.
    private val color = if (season == Season.AUTUMN) Color(0xFFE8872B) else listOf(Color(0xFFF2F0E6), Color(0xFFEEDB5C), Color(0xFFE8872B), Color(0xFF9DB5E8))[r.nextInt(4)]
    private val wing = FloatArray(n) { r.between(17f, LARGEST) }
    private val delay = FloatArray(n) { it * r.between(0.8f, 2.4f) }
    private val cross = FloatArray(n) { r.between(11f, 15f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-70f, 70f) }
    private val rate = FloatArray(n) { r.between(4.2f, 5.2f) }
    private val wander = FloatArray(n * 3) { r.between(0f, TAU) }
    private val course = course(r, 0.12f, 0.40f, 130f, 130f, bow = 0.06f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    override val reach = 34f
    private val edge = lerp(color, Color(0xFF20140E), 0.8f)
    private var book: Flipbook? = null
    private var bakedFor = 0f

    // One pair of wings in its own colours, darker at the edge; the other side is the same picture turned over.
    override fun prepare(h: Float) {
        if (bakedFor == h) return
        book = Flipbook(listOf(butterflyWings()), LARGEST * h / 1080f) { drawPath(it, color); drawPath(it, edge, 0.75f, Stroke(0.09f)) }
        bakedFor = h
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = ((t - delay[i]) / cross[i]).coerceIn(0f, 1f)
        val hop = 6f * abs(sin(PI_F * rate[i] * t))
        return Offset(
            course.x(u, w, sc) + 60f * sc * sin(0.6f * t + wander[i * 3]),
            course.y(u, h) + (lift[i] + 46f * sin(0.9f * t + wander[i * 3 + 1]) + 20f * sin(2.3f * t + wander[i * 3 + 2]) - hop) * sc,
        )
    }

    override fun DrawScope.paint(t: Float, ink: Color, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        prepare(h)
        val pages = book ?: return
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val next = place(i, t + 0.25f, w, h)
            // Wings are never quite flat nor quite shut; the body points the way it's drifting, nose up.
            val open = 0.16f + 0.84f * abs(cos(PI_F * rate[i] * t))
            val heading = (if (next.x >= at.x) 1f else -1f) * (52f + 14f * sin(1.7f * t + wander[i * 3]))
            val s = wing[i] * sc; val k = wing[i] / LARGEST; val a = 0.9f * alpha * lowFade(at.y, h)
            withTransform({ translate(at.x, at.y); rotate(heading, Offset.Zero) }) {
                var side = 1f
                repeat(2) {
                    withTransform({ scale(side * open * k, k, Offset.Zero) }) { pages.stamp(this, 0, a, null) }
                    side = -1f
                }
                drawLine(edge, Offset(0f, -0.3f * s), Offset(0f, 0.36f * s), strokeWidth = max(1.2f, 0.11f * s), cap = StrokeCap.Round, alpha = a)
            }
        }
    }

    private companion object { const val LARGEST = 23f } // the longest wing
}
