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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Passing wildlife. Every minute or two something alive crosses the sky and is gone, chosen for the hour, the
 * season and the weather:
 *
 *  - By day: a cardinal, a jay, a robin or a pair of chickadees in bounding flight (which, depends on the season),
 *    a loose flock of sparrows, crows rowing past, geese in a V (mostly in autumn and spring), a red-tailed hawk
 *    wheeling on a thermal, butterflies in the warm months, and a squirrel out along a branch that dips into view
 *    under its weight.
 *  - Around sunset: starlings gathering into a murmuration (autumn and winter), crows heading to roost, bats.
 *  - After dark: a barn owl on a hunting pass, bats, and a flying squirrel gliding down between the trees.
 *  - Nothing flies in a storm, and little in rain.
 *
 * A sighting is fixed the moment it is picked, so drawing it is a pure function of how long it has been in view:
 * nothing is simulated or stored from frame to frame, and between sightings nothing runs at all.
 *
 * The creatures are painted as themselves, in their own colours (see WildlifeBirds.kt), and then lit by the hour:
 * dimmer and bluer after dark, warm at sunset, paler and nearer the sky's colour with distance. They are drawn the
 * way the falling leaves are, because that is what the TV's graphics chip is quickest at: each creature's poses are
 * painted once, before it appears, into one picture (a [Sheet]); every frame then only places pieces of it.
 */

enum class Creature { SONGBIRDS, FLOCK, CROWS, GEESE, HAWK, STARLINGS, BUTTERFLIES, SQUIRREL, OWL, BATS, FLYING_SQUIRREL }

/**
 * One sighting: a creature, or a group of them, crossing the sky once. Every member is out of view at 0 seconds
 * and again at [duration], so nothing ever appears or vanishes in mid-air.
 */
abstract class Visit internal constructor(val creature: Creature) {
    /** Seconds from the first member entering to the last one leaving. */
    abstract val duration: Float

    /** Whether it needs redrawing on every frame while it is in view. Everything moves at the pace of the leaves and the rain unless it says so. */
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

    protected abstract fun DrawScope.paint(t: Float, light: Light, alpha: Float)

    /**
     * Draws the sighting [t] seconds in. [sky] is the sky's colour where it flies, which is the light the creature
     * is seen in; [visibility] thins it in fog or rain.
     */
    fun draw(scope: DrawScope, t: Float, sky: Color, visibility: Float = 1f) {
        if (t < 0f || t > duration) return
        val light = Scratch.light; light.set(sky)
        with(scope) { paint(t, light, visibility) }
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
            Creature.SONGBIRDS -> Songbirds(r, season)
            Creature.FLOCK -> Flock(r)
            Creature.CROWS -> Crows(r)
            Creature.GEESE -> Geese(r, season)
            Creature.HAWK -> Hawk(r)
            Creature.STARLINGS -> Starlings(r)
            Creature.BUTTERFLIES -> Butterflies(r, season)
            Creature.SQUIRREL -> SquirrelOnABranch(r, season)
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
}

// =====================================================================================
//  Shared pieces: light, paths across the screen, outlines, and sheets of poses.
// =====================================================================================

internal const val PI_F = PI.toFloat()
internal const val TAU = 2f * PI_F
internal const val DEG = 180f / PI_F

internal fun Random.between(a: Float, b: Float) = a + nextFloat() * (b - a)
internal fun Random.side() = if (nextBoolean()) 1f else -1f
internal fun frac(x: Float) = x - floor(x)
internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun ramp(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** Which of [n] poses, spread evenly through a cycle, is nearest to place [q] in it. */
internal fun pose(q: Float, n: Int) = (q * n + 0.5f).toInt() % n

/**
 * A silhouette's colour against this sky, for what really is only ever seen as one (a murmuration at dusk): dark
 * while there is light behind it and, once the sky is too dark for that to show, pale.
 */
internal fun inkFor(sky: Color): Color =
    if (sky.luminance() > 0.04f) lerp(Color(0xFF0B0F16), sky, 0.18f) else lerp(sky, Color(0xFFC9D2E8), 0.3f)

/**
 * The light of the hour, laid over a creature's own colours as it is drawn: full colour by day, dimmer and tinted
 * with the sky toward dusk, a moonlit blue-grey at night. Anything further off is darker against the sky and
 * hazed toward the sky's own colour.
 */
class Light internal constructor() {
    internal var sky = Color.Unspecified; private set
    internal var ink = Color.Black; private set
    private val filters = arrayOfNulls<ColorFilter>(STEPS + 1)

    internal fun set(to: Color) { if (to != sky) { sky = to; ink = inkFor(to); filters.fill(null) } }

    /** The light on something [far] away: 0 close by, 1 a long way off. The same few filters serve frame after frame. */
    internal fun at(far: Float): ColorFilter {
        val i = (far * STEPS + 0.5f).toInt().coerceIn(0, STEPS)
        return filters[i] ?: make(i / STEPS.toFloat()).also { filters[i] = it }
    }

    private fun make(far: Float): ColorFilter {
        val day = ramp(0.012f, 0.2f, sky.luminance())
        val top = max(0.05f, max(sky.red, max(sky.green, sky.blue)))
        val cast = mix(0.6f, 0.14f, day)                    // how much of the sky's colour is in the light
        val bright = mix(0.42f, 1f, day) * (1f - 0.26f * far)
        val haze = far * mix(0.22f, 0.4f, day)
        // After dark there is no bright sky to show a dark shape against: what little light there is picks everything out a shade paler than the night.
        val moon = (1f - ramp(0f, 0.25f, day)) * 0.17f
        fun channel(c: Float) = (mix(1f, c / top, cast) * bright * (1f - haze)).coerceIn(0f, 1f)
        return ColorFilter.lighting(
            Color(channel(sky.red), channel(sky.green), channel(sky.blue)),
            Color((sky.red * haze + moon * 0.8f).coerceIn(0f, 1f), (sky.green * haze + moon * 0.9f).coerceIn(0f, 1f), (sky.blue * haze + moon * 1.25f).coerceIn(0f, 1f)),
        )
    }

    private companion object { const val STEPS = 4 }
}

/**
 * A steady crossing from one side of the screen to the other; the height bows and wavers a little on the way.
 * [lead] and [trail] (1080p pixels) are how far beyond the edges it starts and ends, so a whole group fits outside.
 */
internal class Course(
    val dir: Float, private val y0: Float, private val y1: Float, private val bow: Float, private val lead: Float, private val trail: Float,
    private val wave: Float = 0f, private val turns: Float = 1f, private val phase: Float = 0f,
) {
    fun x(u: Float, w: Float, sc: Float): Float { val p = -lead * sc + u * (w + (lead + trail) * sc); return if (dir > 0f) p else w - p }
    fun y(u: Float, h: Float): Float = h * (y0 + (y1 - y0) * u + bow * 4f * u * (1f - u) + wave * sin(TAU * turns * u + phase))
}

internal fun course(r: Random, top: Float, bottom: Float, lead: Float, trail: Float, bow: Float = 0.05f): Course {
    val y0 = r.between(top, bottom)
    return Course(r.side(), y0, (y0 + r.between(-0.09f, 0.09f)).coerceIn(top, bottom), r.between(-bow, bow), lead, trail, r.between(0.003f, 0.012f), r.between(0.7f, 2f), r.between(0f, TAU))
}

/**
 * A smooth outline through a ring of points (a [tip] stays sharp). Every outline goes into its path turning the
 * same way, so overlapping parts merge into one shape instead of cutting holes in each other.
 */
internal class Outline {
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
internal object Scratch {
    val light = Light()
    val plain by lazy { Paint().apply { color = Color.White } } // white: a batch of triangles keeps its own colours whichever way they are combined with the paint's
}

/**
 * A creature's poses, painted once: [count] pictures of [cw] by [ch] pixels in one image, each painted by [paint]
 * around the middle of its own cell. While the creature is in view, each frame only places pieces of that image.
 * A shape that changes is different: the processor has to fill it in and send it to the graphics chip afresh
 * every time, which is the slow way round.
 */
internal class Sheet(count: Int, private val cw: Int, private val ch: Int, paint: DrawScope.(Int) -> Unit) {
    private val cols = max(1, min(count, 2040 / cw))
    private val image: ImageBitmap

    init {
        val rows = (count + cols - 1) / cols
        image = ImageBitmap(cw * cols, ch * rows)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size((cw * cols).toFloat(), (ch * rows).toFloat())) {
            for (i in 0 until count) {
                val left = (i % cols) * cw.toFloat(); val top = (i / cols) * ch.toFloat()
                // Nothing may spill into a neighbouring pose.
                clipRect(left + 1f, top + 1f, left + cw - 1f, top + ch - 1f) { withTransform({ translate(left + cw / 2f, top + ch / 2f) }) { paint(i) } }
            }
        }
        image.prepareToDraw()
    }

    /** Places pose [i] with the middle of its cell at the origin: move, turn and size the canvas first. */
    fun stamp(scope: DrawScope, i: Int, alpha: Float, filter: ColorFilter?) =
        scope.drawImage(image, IntOffset((i % cols) * cw, (i / cols) * ch), IntSize(cw, ch), IntOffset(-cw / 2, -ch / 2), IntSize(cw, ch), alpha, colorFilter = filter, filterQuality = FilterQuality.Low)
}

// =====================================================================================
//  Starlings at dusk: the one sighting that really is silhouettes.
// =====================================================================================

/**
 * A murmuration of starlings. The flock is a sheet: every bird chases the same wandering point, each a moment
 * behind it and each on a slightly different line. Where the point runs straight the sheet is drawn out thin;
 * where it doubles back the sheet folds over itself and the birds pile into dark bands, which is what makes the
 * real thing seem to pulse and pour.
 */
internal class Starlings(r: Random) : Visit(Creature.STARLINGS) {
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
    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        prepare(h)
        val m = mesh ?: return
        val ink = light.ink
        val at = m.positions
        for (i in 0 until n) {
            val o = place(i, t, w, h); val j = i * 6
            at[j] = o.x + dart[j] * sc; at[j + 1] = o.y + dart[j + 1] * sc
            at[j + 2] = o.x + dart[j + 2] * sc; at[j + 3] = o.y + dart[j + 3] * sc
            at[j + 4] = o.x + dart[j + 4] * sc; at[j + 5] = o.y + dart[j + 5] * sc
        }
        val colour = ink.copy(alpha = ink.alpha * 0.8f * alpha).toArgb()
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
//  Bats, a flying squirrel and butterflies.
// =====================================================================================

/** A bat seen head-on, wings at angle [a] above level: the skin of its wings into [skin], with a scalloped edge between the fingers, and its body, head and ears into [body]. */
private fun batInto(o: Outline, skin: Path, body: Path, a: Float) {
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
            .into(skin)
        k = -1f
    }
    o.start().to(0f, 0.34f).to(-0.085f, 0.17f).to(-0.085f, -0.05f).to(-0.075f, -0.17f).tip(-0.085f, -0.31f).to(-0.025f, -0.215f)
        .to(0.025f, -0.215f).tip(0.085f, -0.31f).to(0.075f, -0.17f).to(0.085f, -0.05f).to(0.085f, 0.17f).into(body)
}

/** A few bats, each on its own jinking path after insects: the last light shows through the skin of their wings. */
private class Bats(r: Random) : Visit(Creature.BATS) {
    private val n = 2 + r.nextInt(4)
    private val wing = FloatArray(n) { r.between(24f, LARGEST) }
    private val delay = FloatArray(n) { if (it == 0) 0f else r.between(0.3f, 3.6f) }
    private val cross = FloatArray(n) { r.between(4.5f, 6.5f) }
    private val dir = FloatArray(n) { r.side() }
    private val y0 = FloatArray(n) { r.between(0.08f, 0.36f) }
    private val y1 = FloatArray(n) { r.between(0.08f, 0.36f) }
    private val wobble = FloatArray(n * 8) { if (it % 2 == 0) r.between(2.4f, 6.2f) else r.between(0f, TAU) } // four swerves each: a rate and a phase
    private val period = FloatArray(n) { r.between(0.16f, 0.2f) }
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    override val reach = 46f
    private var sheet: Sheet? = null
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
        val o = Outline(); val s = LARGEST * h / 1080f; val cell = (s * 2.5f).toInt() + 4
        val fur = Color(0xFF2B211C); val skinColour = Color(0xFF3A2A24)
        sheet = Sheet(BEATS, cell, cell) { k ->
            val skin = Path(); val body = Path()
            batInto(o, skin, body, mix(-0.5f, 0.8f, beat(k / BEATS.toFloat())))
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawPath(skin, skinColour, 0.78f)                        // thin skin: a little of the sky shows through
                drawPath(skin, fur, 0.9f, Stroke(0.035f))                // the arm and fingers that stretch it
                drawPath(body, fur)
            }
        }
        bakedFor = h
    }

    /** Wing height through one beat, 1 at the top to 0 at the bottom: a quick downstroke, then a slower recovery. */
    private fun beat(q: Float): Float = if (q < 0.4f) 0.5f + 0.5f * cos(PI_F * q / 0.4f) else 0.5f - 0.5f * cos(PI_F * (q - 0.4f) / 0.6f)

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0.25f)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val k = wing[i] / LARGEST
            // It leans into each swerve.
            val lean = 17f * sin(wobble[i * 8] * t + wobble[i * 8 + 1] + 1.3f)
            withTransform({ translate(at.x, at.y); rotate(lean, Offset.Zero); scale(k, k, Offset.Zero) }) {
                pages.stamp(this, pose(frac(t / period[i] + i * 0.37f), BEATS), alpha, filter)
            }
        }
    }

    private companion object { const val BEATS = 8; const val LARGEST = 32f } // poses through one wingbeat; the longest wing
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

/** A flying squirrel's glide: in over the top of the screen, down a long shallow slope, and out at the side. From below it is pale: cream belly, the stretched skin a shade darker, its edge dark. */
private class Glider(r: Random) : Visit(Creature.FLYING_SQUIRREL) {
    private val body = r.between(50f, 62f)
    private val dir = r.side()
    private val from = r.between(0.16f, 0.4f)     // where along the top it comes in (from the left edge, for one gliding right)
    private val to = r.between(0.34f, 0.46f)      // how far down the side it leaves
    private val sag = r.between(0.02f, 0.05f)
    private val roll = r.between(0f, TAU)
    override val duration = r.between(3.8f, 4.6f)
    override val count get() = 1
    override val reach = 88f
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val s = body * h / 1080f; val shape = gliderShape()
        sheet = Sheet(1, (s * 1.6f).toInt() + 4, (s * 2.9f).toInt() + 4) {
            withTransform({ translate(0f, -0.27f * s); scale(s, s, Offset.Zero) }) {
                drawPath(shape, Color(0xFFB7A894))
                clipPath(shape) {
                    // The furred body and tail down the middle, paler than the skin stretched either side of it.
                    drawOval(Color(0xFFE9DFCE), Offset(-0.2f, -0.6f), Size(0.4f, 1.15f))
                    drawOval(Color(0xFF9B8B78), Offset(-0.1f, 0.5f), Size(0.2f, 0.82f))
                }
                drawPath(shape, Color(0xFF5E5145), 0.9f, Stroke(0.03f))
                drawCircle(Color(0xFF15110E), 0.022f, Offset(-0.075f, -0.6f)); drawCircle(Color(0xFF15110E), 0.022f, Offset(0.075f, -0.6f))
            }
        }
        bakedFor = h
    }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val u = t / duration; val m = reach * sc
        val x = mix(from * w, w + m * 1.1f, u)
        return Offset(if (dir > 0f) x else w - x, mix(-m * 1.1f, to * h, u) + sag * h * 4f * u * (1f - u))
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height
        prepare(h)
        val pages = sheet ?: return
        val p = place(0, t, w, h); val next = place(0, t + 0.05f, w, h)
        val heading = atan2(next.y - p.y, next.x - p.x) * DEG + 5f * sin(t * 2.1f + roll)
        val tilt = 0.9f + 0.1f * cos(t * 2.1f + roll) // rocking from side to side as it steers
        withTransform({ translate(p.x, p.y); rotate(heading + 90f, Offset.Zero); scale(tilt, 1f, Offset.Zero) }) {
            pages.stamp(this, 0, alpha, light.at(0.15f))
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
    private val monarch = season == Season.AUTUMN || r.nextFloat() < 0.3f
    private val color = if (monarch) Color(0xFFE8872B) else listOf(Color(0xFFF2F0E6), Color(0xFFEEDB5C), Color(0xFF9DB5E8))[r.nextInt(3)]
    private val wing = FloatArray(n) { r.between(22f, LARGEST) }
    private val delay = FloatArray(n) { it * r.between(0.8f, 2.4f) }
    private val cross = FloatArray(n) { r.between(11f, 15f) }
    private val lift = FloatArray(n) { if (it == 0) 0f else r.between(-70f, 70f) }
    private val rate = FloatArray(n) { r.between(4.2f, 5.2f) }
    private val wander = FloatArray(n * 3) { r.between(0f, TAU) }
    private val course = course(r, 0.12f, 0.40f, 130f, 130f, bow = 0.06f)
    override val duration = (0 until n).maxOf { delay[it] + cross[it] }
    override val count get() = n
    override val reach = 40f
    private val edge = lerp(color, Color(0xFF20140E), if (monarch) 0.92f else 0.6f)
    private var sheet: Sheet? = null
    private var bakedFor = 0f

    // One pair of wings in its own colours: dark veins and a dark border (a monarch's dotted white); the other side is the same picture turned over.
    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val s = LARGEST * h / 1080f; val wings = butterflyWings()
        sheet = Sheet(1, (s * 1.2f).toInt() + 4, (s * 2f).toInt() + 4) {
            withTransform({ translate(-s * 0.5f, 0f); scale(s, s, Offset.Zero) }) {
                drawPath(wings, color)
                clipPath(wings) {
                    val veins = if (monarch) 0.85f else 0.3f
                    for (k in 0 until 6) { val a = -1.0f + k * 0.42f; drawLine(edge, Offset(0.03f, -0.02f), Offset(0.03f + 1.2f * cos(a), -0.02f + 1.2f * sin(a)), strokeWidth = 0.03f, alpha = veins) }
                    drawPath(wings, edge, if (monarch) 1f else 0.7f, Stroke(if (monarch) 0.2f else 0.1f))
                    if (monarch) for (k in 0 until 9) { val a = -0.95f + k * 0.3f; drawCircle(Color(0xFFF4EFE4), 0.022f, Offset(0.03f + 0.86f * cos(a) * (1f - 0.12f * abs(sin(a * 2f))), -0.06f + 0.74f * sin(a))) }
                }
            }
        }
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

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        prepare(h)
        val pages = sheet ?: return
        val filter = light.at(0f)
        for (i in 0 until n) {
            if (t < delay[i] || t > delay[i] + cross[i]) continue
            val at = place(i, t, w, h); val next = place(i, t + 0.25f, w, h)
            // Wings are never quite flat nor quite shut; the body points the way it's drifting, nose up.
            val open = 0.16f + 0.84f * abs(cos(PI_F * rate[i] * t))
            val heading = (if (next.x >= at.x) 1f else -1f) * (52f + 14f * sin(1.7f * t + wander[i * 3]))
            val s = wing[i] * sc; val k = wing[i] / LARGEST
            withTransform({ translate(at.x, at.y); rotate(heading, Offset.Zero) }) {
                var side = 1f
                repeat(2) {
                    withTransform({ scale(side * open * k, k, Offset.Zero); translate(LARGEST * sc * 0.5f, 0f) }) { pages.stamp(this, 0, alpha, filter) }
                    side = -1f
                }
                drawLine(edge, Offset(0f, -0.3f * s), Offset(0f, 0.36f * s), strokeWidth = max(1.2f, 0.11f * s), cap = StrokeCap.Round, alpha = alpha)
            }
        }
    }

    private companion object { const val LARGEST = 30f } // the longest wing
}
