package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Birds, drawn as birds. Each kind is a small model: a body and head in its own colours, two jointed wings built
 * from separate flight feathers, and a fanned tail. It is turned to the viewer the way a bird in the sky really is
 * seen (from below, at a slant, banking in a turn) and painted with light from above.
 *
 * None of this runs while a bird is flying. Before a sighting, each pose it will need (the places in a wingbeat,
 * wings folded, a glide, the headings of a circling hawk) is painted once into a Sheet, off the main thread;
 * the frames then only place those pictures.
 */

/** A point on the bird: forward (toward the beak), up, and toward its right wingtip, in body lengths. */
internal class P3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = P3(x * k, y * k, z * k)
}

/**
 * How a bird is turned to the viewer. [heading] 0 is flying straight across, left to right; positive turns it away
 * from us, negative toward us. [elevation] is how far up we are looking at it (0: level with it, a quarter turn:
 * straight overhead). [bank] rolls it, right wing down; [pitch] lifts its beak.
 */
internal class Aspect(val heading: Float, val elevation: Float, val bank: Float = 0f, val pitch: Float = 0f) {
    private val m = FloatArray(9)

    init {
        val cb = cos(bank); val sb = sin(bank); val cp = cos(pitch); val sp = sin(pitch)
        val ch = cos(heading); val sh = sin(heading); val ce = cos(elevation); val se = sin(elevation)
        for (col in 0 until 3) {
            val lx = if (col == 0) 1f else 0f; val ly = if (col == 1) 1f else 0f; val lz = if (col == 2) 1f else 0f
            val y1 = ly * cb - lz * sb; val z1 = ly * sb + lz * cb          // roll about the body's length
            val x2 = lx * cp - y1 * sp; val y2 = lx * sp + y1 * cp          // nose up
            val x3 = x2 * ch + z1 * sh; val z3 = -x2 * sh + z1 * ch         // turn
            m[col] = x3; m[3 + col] = -(y2 * ce + z3 * se); m[6 + col] = -y2 * se + z3 * ce
        }
    }

    /** Across the screen, down it, and toward the viewer. */
    fun sx(p: P3) = m[0] * p.x + m[1] * p.y + m[2] * p.z
    fun sy(p: P3) = m[3] * p.x + m[4] * p.y + m[5] * p.z
    fun sz(p: P3) = m[6] * p.x + m[7] * p.y + m[8] * p.z
}

/** Where the wings are in a beat. Angles in radians. */
internal class WingPose(
    val flap: Float,                 // raised above level
    val fold: Float = 0.05f,         // the hand swung back at the wrist
    val droop: Float = 0f,           // the hand hanging below the arm (it lags on the way up)
    val sweep: Float = 0f,           // the whole wing raked back
    val tucked: Boolean = false,     // closed against the body, as small birds hold them between bursts
    val fan: Float = 1f,             // how far the tail is spread
)

/** A bird's build (in body lengths: rump to forehead is 1) and its colours. */
internal class BirdKind(
    val girth: Float = 0.2f,
    val trunk: FloatArray = SONGBIRD_TRUNK,
    val headR: Float = 0.135f, val headX: Float = 0.40f, val headY: Float = 0.045f,
    val neck: Float = 0f, val neckR: Float = 0.04f,
    val beak: Float = 0.12f, val beakDepth: Float = 0.05f,
    val tail: Float = 0.55f, val tailFan: Float = 0.15f, val tailFork: Float = 0f, val tailFeathers: Int = 8,
    val arm: Float = 0.42f, val hand: Float = 0.60f, val chord: Float = 0.38f, val chordWrist: Float = 0.33f,
    val primaries: Int = 8, val secondaries: Int = 7, val round: Float = 0.35f, val slots: Float = 0f,
    val flapHi: Float = 1.2f, val flapLo: Float = -0.95f, val foldMax: Float = 0.9f, val dihedral: Float = 0.1f,
    val crest: Float = 0f,
    val back: Color, val belly: Color,
    val head: Color = back, val throat: Color = belly,
    val coverts: Color = back, val flight: Color = lerp(back, Color.Black, 0.25f), val flightTip: Color? = null,
    val underWing: Color = belly, val underFlight: Color = lerp(belly, Color(0xFF6E6E72), 0.35f), val underTip: Color? = null,
    val tailUp: Color = flight, val tailUnder: Color = underFlight, val tailTip: Color? = null,
    val beakColor: Color = Color(0xFF3A342E), val eyeRing: Color? = null,
    val cap: Color? = null, val capFrom: Float = 18f, val mask: Color? = null, val bib: Color? = null, val cheek: Color? = null,
    val breast: Color? = null, val flank: Color? = null, val rump: Color? = null, val vent: Color? = null,
    val wingBar: Color? = null, val bars: Color? = null, val patagium: Color? = null, val bellyBand: Color? = null, val necklace: Color? = null,
    val sideLine: Float = -14f, val fingerTips: Float = 0f, val faceDisc: Color? = null,
) {
    /** How far any part of it can be from its middle, in body lengths: the cell a pose is painted into. */
    val reach: Float get() = max(arm + hand + girth + 0.08f, max(tail + 0.62f, headX + neck + headR + beak + 0.08f))
}

internal val SONGBIRD_TRUNK = floatArrayOf(-0.50f, 0.26f, -0.40f, 0.58f, -0.22f, 0.88f, -0.02f, 1f, 0.16f, 0.95f, 0.30f, 0.78f, 0.40f, 0.60f)
internal val GOOSE_TRUNK = floatArrayOf(-0.50f, 0.24f, -0.42f, 0.62f, -0.24f, 0.92f, -0.04f, 1f, 0.14f, 0.9f, 0.27f, 0.66f, 0.36f, 0.42f)

private val DARK = Color(0xFF14110F)

private fun shade(c: Color, k: Float) = Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha)

/**
 * Paints one bird kind, seen one way, at [scale] pixels to a body length, around the origin of the canvas.
 * Used only while a sighting is being prepared.
 */
internal class BirdPainter(private val k: BirdKind, private val a: Aspect, private val scale: Float) {
    private val path = Path()
    private val outline = Outline()

    // The body's line on screen (d), the direction across it toward its back (e), and how the up and near-side
    // directions of its surface show across the body: see across().
    private val dx: Float; private val dy: Float; private val ex: Float; private val ey: Float
    private val ue: Float; private val we: Float
    private val nearSide: Float   // +1 when its right side is the one facing us

    init {
        val fx = a.sx(P3(1f, 0f, 0f)); val fy = a.sy(P3(1f, 0f, 0f)); val fl = max(1e-3f, sqrt(fx * fx + fy * fy))
        dx = fx / fl; dy = fy / fl
        val ux = a.sx(P3(0f, 1f, 0f)); val uy = a.sy(P3(0f, 1f, 0f))
        // Across the body, on the side its back is on (when the back is edge-on to us, the upper side of the screen).
        val side = (-dy) * ux + dx * uy
        val flip = if (abs(side) > 1e-3f) (if (side > 0f) 1f else -1f) else (if (dx >= 0f) -1f else 1f)
        ex = -dy * flip; ey = dx * flip
        nearSide = if (a.sz(P3(0f, 0f, 1f)) >= 0f) 1f else -1f
        ue = ux * ex + uy * ey
        we = (a.sx(P3(0f, 0f, nearSide)) * ex + a.sy(P3(0f, 0f, nearSide)) * ey)
    }

    private fun px(p: P3) = a.sx(p) * scale
    private fun py(p: P3) = a.sy(p) * scale

    /**
     * Where a line along the body's side shows across its width, from -1 (the lower edge as we see it) to 1: [lat]
     * is how far round from the side line it runs, in degrees (90: along the middle of the back, -90: the belly).
     */
    private fun across(lat: Float): Float { val r = lat / DEG; return (sin(r) * ue + cos(r) * we).coerceIn(-1f, 1f) }

    /** The trunk's radius at [x] along the body. */
    private fun radius(x: Float): Float {
        val t = k.trunk; val n = t.size / 2
        if (x <= t[0]) return t[1] * k.girth * (1f - ((t[0] - x) / 0.2f).coerceIn(0f, 1f) * 0.6f)
        for (i in 1 until n) if (x <= t[i * 2]) { val f = (x - t[i * 2 - 2]) / (t[i * 2] - t[i * 2 - 2]); return mix(t[i * 2 - 1], t[i * 2 + 1], f) * k.girth }
        return t[n * 2 - 1] * k.girth
    }

    /** A point on the body's surface, on the side facing us: [x] along it, [lat] degrees round from its side line. */
    private fun skinX(x: Float, lat: Float): Float { val s = across(lat); return px(P3(x, 0f, 0f)) + ex * s * radius(x) * scale * (if (s > 0f) BACK else BELLY) }
    private fun skinY(x: Float, lat: Float): Float { val s = across(lat); return py(P3(x, 0f, 0f)) + ey * s * radius(x) * scale * (if (s > 0f) BACK else BELLY) }

    private val headAt = P3(k.headX + k.neck, k.headY, 0f)

    fun DrawScope.paint(pose: WingPose) {
        val right = wing(1f, pose); val left = wing(-1f, pose)
        val first = if (right.depth <= left.depth) right else left; val second = if (first === right) left else right
        if (!pose.tucked && !first.inFront) wingOut(first, 0.86f)
        if (!pose.tucked && !second.inFront) wingOut(second, if (second.depth < 0f) 0.9f else 1f)
        tail(pose.fan)
        body(pose.tucked)
        if (!pose.tucked && first.inFront) wingOut(first, 0.92f)
        if (!pose.tucked && second.inFront) wingOut(second, 1f)
    }

    // ------------------------------------------------------------------ the body and head

    private fun DrawScope.body(tucked: Boolean) {
        val t = k.trunk; val n = t.size / 2
        val o = outline.start()
        // A bird is deeper in the chest than it is humped in the back.
        for (i in 0 until n) { val x = t[i * 2]; val r = t[i * 2 + 1] * k.girth * scale * BACK; o.to(px(P3(x, 0f, 0f)) + ex * r, py(P3(x, 0f, 0f)) + ey * r) }
        for (i in n - 1 downTo 0) { val x = t[i * 2]; val r = t[i * 2 + 1] * k.girth * scale * BELLY; o.to(px(P3(x, 0f, 0f)) - ex * r, py(P3(x, 0f, 0f)) - ey * r) }
        path.rewind(); o.into(path)
        val big = k.girth * scale
        val split = across(k.sideLine)
        val body = Path().apply { addPath(path) }
        drawPath(body, twoTone(k.belly, k.back, split, -ex * big * 0.13f, -ey * big * 0.13f, big * 1.0f))
        clipPath(body) {
            // Markings, soft-edged the way feather tracts meet.
            k.vent?.let { blob(skinX(-0.42f, -70f), skinY(-0.42f, -70f), 0.2f * scale, big * 0.8f, it) }
            k.rump?.let { blob(skinX(-0.44f, 70f), skinY(-0.44f, 70f), 0.14f * scale, big * 0.7f, it) }
            k.flank?.let { blob(skinX(-0.08f, -38f), skinY(-0.08f, -38f), 0.3f * scale, big * 0.42f, it) }
            k.breast?.let { blob(skinX(0.14f, -62f), skinY(0.14f, -62f), 0.33f * scale, big * 0.95f, it) }
            k.bellyBand?.let { c ->
                // A band of streaks across the belly.
                for (i in 0 until 9) { val x = -0.14f + 0.035f * i; val lat = -78f + (i * 37 % 50); blob(skinX(x, lat), skinY(x, lat), 0.05f * scale, big * 0.16f, c) }
            }
            k.necklace?.let { c -> line(skinX(0.3f, 10f), skinY(0.3f, 10f), skinX(0.24f, -80f), skinY(0.24f, -80f), c, 0.045f * scale) }
        }
        if (tucked) foldedWing()

        // Neck (for a goose) and head.
        val hx = px(headAt); val hy = py(headAt); val hr = k.headR * scale
        if (k.neck > 0f) {
            val bx = px(P3(0.33f, 0.02f, 0f)); val by = py(P3(0.33f, 0.02f, 0f)); val r0 = k.neckR * 1.7f * scale; val r1 = k.neckR * scale
            path.rewind()
            outline.start().to(bx + ex * r0, by + ey * r0).to(hx + ex * r1, hy + ey * r1).to(hx - ex * r1, hy - ey * r1).to(bx - ex * r0, by - ey * r0).into(path)
            drawPath(path, twoTone(k.throat, k.head, split, (bx + hx) / 2f, (by + hy) / 2f, r0))
        }
        // The beak: two halves, the lower one in shade.
        val b0 = P3(headAt.x + k.headR * 0.72f, headAt.y - k.headR * 0.12f, 0f); val tip = P3(b0.x + k.beak + k.headR * 0.25f, b0.y - k.beakDepth * 0.25f, 0f)
        val up = P3(b0.x, b0.y + k.beakDepth, 0f); val down = P3(b0.x, b0.y - k.beakDepth, 0f)
        path.rewind(); path.moveTo(px(up), py(up)); path.lineTo(px(tip), py(tip)); path.lineTo(px(b0), py(b0)); path.close()
        drawPath(path, k.beakColor)
        path.rewind(); path.moveTo(px(b0), py(b0)); path.lineTo(px(tip), py(tip)); path.lineTo(px(down), py(down)); path.close()
        drawPath(path, shade(k.beakColor, 0.72f))
        if (k.crest > 0f) {
            val c0 = P3(headAt.x + k.headR * 0.45f, headAt.y + k.headR * 0.75f, 0f); val c1 = P3(headAt.x - k.headR * 0.75f, headAt.y + k.headR * (0.9f + k.crest), 0f)
            val c2 = P3(headAt.x - k.headR * 0.95f, headAt.y + k.headR * 0.2f, 0f)
            path.rewind(); outline.start().to(px(c0), py(c0)).tip(px(c1), py(c1)).to(px(c2), py(c2)).into(path)
            drawPath(path, k.cap ?: k.head)
        }
        val skull = Path().apply { addOval(androidx.compose.ui.geometry.Rect(hx - hr, hy - hr, hx + hr, hy + hr)) }
        drawPath(skull, twoTone(k.throat, k.head, across(k.sideLine - 8f), hx, hy, hr))
        clipPath(skull) {
            k.cap?.let { c ->
                // Everything above a line round the head.
                val s = across(k.capFrom) * hr
                path.rewind()
                path.moveTo(hx + ex * s - dx * hr * 2f, hy + ey * s - dy * hr * 2f); path.lineTo(hx + ex * s + dx * hr * 2f, hy + ey * s + dy * hr * 2f)
                path.lineTo(hx + ex * hr * 2f + dx * hr * 2f, hy + ey * hr * 2f + dy * hr * 2f); path.lineTo(hx + ex * hr * 2f - dx * hr * 2f, hy + ey * hr * 2f - dy * hr * 2f); path.close()
                drawPath(path, c)
            }
            k.cheek?.let { blob(hx + ex * across(-22f) * hr * 0.9f - dx * hr * 0.05f, hy + ey * across(-22f) * hr * 0.9f - dy * hr * 0.05f, hr * 0.62f, hr * 0.5f, it) }
            k.bib?.let { blob(hx + dx * hr * 0.55f + ex * across(-75f) * hr, hy + dy * hr * 0.55f + ey * across(-75f) * hr, hr * 0.75f, hr * 0.62f, it) }
            k.mask?.let { blob(hx + dx * hr * 0.92f + ex * across(-25f) * hr * 0.45f, hy + dy * hr * 0.92f + ey * across(-25f) * hr * 0.45f, hr * 0.6f, hr * 0.62f, it) }
        }
        k.faceDisc?.let { c ->
            // An owl's face: a pale flat disc on the front of the head, dark eyes set in it, the beak a small point.
            val fz = a.sz(P3(1f, 0f, 0f))
            if (fz > -0.35f) {
                val centre = P3(headAt.x + k.headR * 0.5f, headAt.y, 0f); val rr = k.headR * 0.88f
                for (pass in 0..1) {
                    val r1 = if (pass == 0) rr * 1.08f else rr * 0.94f
                    val o = outline.start()
                    for (i in 0 until 14) { val t = TAU * i / 14f; val heart = 1f - 0.12f * max(0f, cos(t)).pow(6f)
                        val p = P3(centre.x, centre.y + cos(t) * r1 * heart, sin(t) * r1 * 0.92f); o.to(px(p), py(p)) }
                    path.rewind(); o.into(path)
                    drawPath(path, if (pass == 0) shade(k.head, 0.8f) else c)
                }
                for (side in -1..1 step 2) { val p = P3(centre.x + 0.01f, centre.y + rr * 0.12f, side * rr * 0.36f); drawCircle(DARK, max(0.8f, hr * 0.13f), Offset(px(p), py(p))) }
                val b = P3(centre.x + 0.015f, centre.y - rr * 0.2f, 0f); val b2 = P3(centre.x + 0.02f, centre.y - rr * 0.5f, 0f)
                line(px(b), py(b), px(b2), py(b2), k.beakColor, hr * 0.14f)
                return
            }
        }
        // The eye, on the side of the head facing us, when that side shows at all.
        val eye = P3(headAt.x + k.headR * 0.34f, headAt.y + k.headR * 0.16f, nearSide * k.headR * 0.86f)
        if (abs(a.sz(P3(0f, 0f, 1f))) > 0.2f) {
            val x = px(eye); val y = py(eye); val r = max(0.9f, hr * 0.135f)
            k.eyeRing?.let { drawCircle(it, r * 1.55f, Offset(x, y)) }
            drawCircle(DARK, r, Offset(x, y))
            if (r > 1.4f) drawCircle(Color(0xD9FFFFFF), r * 0.32f, Offset(x - r * 0.3f, y - r * 0.35f))
        }
    }

    /** The wing closed along the body's side: its tip reaching the base of the tail, bars across it, feather edges along it. */
    private fun DrawScope.foldedWing() {
        path.rewind()
        outline.start().to(skinX(0.2f, 28f), skinY(0.2f, 28f)).to(skinX(0.08f, 62f), skinY(0.08f, 62f)).to(skinX(-0.2f, 60f), skinY(-0.2f, 60f))
            .to(skinX(-0.45f, 44f), skinY(-0.45f, 44f)).tip(skinX(-0.68f, 24f), skinY(-0.68f, 24f)).to(skinX(-0.42f, 2f), skinY(-0.42f, 2f))
            .to(skinX(-0.15f, -16f), skinY(-0.15f, -16f)).to(skinX(0.1f, -10f), skinY(0.1f, -10f)).into(path)
        drawPath(path, k.coverts)
        val blade = Path().apply { addPath(path) }
        clipPath(blade) {
            path.rewind()
            outline.start().to(skinX(-0.1f, 40f), skinY(-0.1f, 40f)).to(skinX(-0.45f, 46f), skinY(-0.45f, 46f)).tip(skinX(-0.7f, 24f), skinY(-0.7f, 24f))
                .to(skinX(-0.42f, 0f), skinY(-0.42f, 0f)).to(skinX(-0.16f, -18f), skinY(-0.16f, -18f)).into(path)
            drawPath(path, k.flight)
            for (i in 0 until 3) line(skinX(-0.18f, 22f - i * 14f), skinY(-0.18f, 22f - i * 14f), skinX(-0.62f, 26f - i * 5f), skinY(-0.62f, 26f - i * 5f), shade(k.flight, 1.35f), max(0.6f, 0.008f * scale), 0.5f)
            k.wingBar?.let { c ->
                line(skinX(-0.02f, 44f), skinY(-0.02f, 44f), skinX(-0.1f, -14f), skinY(-0.1f, -14f), c, 0.03f * scale)
                line(skinX(-0.15f, 44f), skinY(-0.15f, 44f), skinX(-0.24f, -10f), skinY(-0.24f, -10f), c, 0.022f * scale)
            }
        }
    }

    /** A soft band from one colour to the other across a rounded part: [low] below the line at [split] (-1 to 1 across it), [high] above, lit from above. */
    private fun twoTone(low: Color, high: Color, split: Float, cx: Float, cy: Float, r: Float): Brush {
        val t = ((split + 1f) / 2f).coerceIn(0.03f, 0.97f)
        val t1 = max(0.012f, t - 0.2f); val t2 = min(0.988f, max(t1 + 0.01f, t + 0.07f))
        return Brush.linearGradient(
            0f to shade(low, 0.74f), t1 to low, t2 to high, 1f to shade(high, 1.12f),
            start = Offset(cx - ex * r, cy - ey * r), end = Offset(cx + ex * r, cy + ey * r),
        )
    }

    private fun DrawScope.blob(cx: Float, cy: Float, along: Float, wide: Float, c: Color) {
        val angle = atan2(dy, dx) * DEG
        withTransform({ translate(cx, cy); rotate(angle, Offset.Zero); scale(max(0.5f, along), max(0.5f, wide), Offset.Zero) }) {
            drawCircle(Brush.radialGradient(0f to c, 0.62f to c, 1f to c.copy(alpha = 0f), center = Offset.Zero, radius = 1f), 1f, Offset.Zero)
        }
    }

    private fun DrawScope.line(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, w: Float, alpha: Float = 1f) =
        drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = max(0.5f, w), cap = StrokeCap.Round, alpha = alpha)

    // ------------------------------------------------------------------ the tail

    private fun DrawScope.tail(fan: Float) {
        val n = k.tailFeathers; val base = P3(-0.44f, 0.01f, 0f)
        val upper = a.sz(P3(0f, 1f, 0f)) > 0f
        val spread = k.tailFan * fan
        val colour = if (upper) k.tailUp else k.tailUnder
        // Seen nearly edge-on a tail would be a line: birds steer with it, so it is always turned a little.
        val twist = -nearSide * 0.34f * (1f - min(1f, abs(a.sz(P3(0f, 1f, 0f))) * 1.6f)); val ct = cos(twist); val st = sin(twist)
        // From above the middle feathers lie on top; from below, the outer ones.
        for (step in 0 until n) {
            val i = if (upper) (if (step % 2 == 0) step / 2 else n - 1 - step / 2) else (if (step % 2 == 0) (n - 1) / 2 - step / 2 else n / 2 + step / 2)
            if (i < 0 || i >= n) continue
            val f = if (n == 1) 0f else i / (n - 1f) * 2f - 1f               // -1 to 1 across the tail
            val angle = f * spread * 2.2f
            val dir = P3(-cos(angle), -0.04f - sin(angle) * st, sin(angle) * ct)
            val len = k.tail * (1f + k.tailFork * (abs(f) - 0.5f) * 0.4f)
            val half = max(0.022f, k.tail * sin(spread * 2.2f) / n * 1.15f + 0.012f)
            val side = P3(sin(angle), -cos(angle) * st, cos(angle) * ct)
            feather(base + side * (f * 0.02f), dir, side, len, half, 0.78f, shade(colour, if (i % 2 == 0) 1f else 0.93f), k.tailTip, if (k.bars != null) 4 else 0, k.bars, 0.8f)
        }
    }

    // ------------------------------------------------------------------ the wings

    private class Wing(val s: P3, val r: P3, val span: P3, val chord: P3, val hand: P3, val handChord: P3, val upper: Boolean, val depth: Float, val inFront: Boolean, val folded: Float)

    private fun wing(side: Float, pose: WingPose): Wing {
        val cf = cos(pose.flap); val sf = sin(pose.flap); val cs = cos(pose.sweep); val ss = sin(pose.sweep)
        val span = P3(-ss, sf * cs, side * cf * cs)
        val chord = P3(-cs, -sf * ss, -side * cf * ss)
        val normal = P3(0f, cf, -side * sf)                                   // the wing's upper face
        val s = P3(0.15f, k.girth * 0.5f, side * k.girth * 0.7f)
        val r = s + span * k.arm
        val cd = cos(pose.fold); val sd = sin(pose.fold); val ck = cos(pose.droop); val sk = sin(pose.droop)
        val flat = span * cd + chord * sd
        val hand = flat * ck - normal * sk
        val handChord = chord * cd - span * sd
        val upper = a.sz(normal) > 0f
        return Wing(s, r, span, chord, hand, handChord, upper, a.sz(r), a.sz(s) > 0.035f, (pose.fold / max(0.3f, k.foldMax)).coerceIn(0f, 1f))
    }

    private fun DrawScope.wingOut(w: Wing, light: Float) {
        val lit = if (w.upper) light else light * 0.93f
        val flight = shade(if (w.upper) k.flight else k.underFlight, lit)
        val tipColour = (if (w.upper) k.flightTip else k.underTip)?.let { shade(it, lit) }
        val bars = k.bars?.let { shade(it, lit) }
        // Primaries, fanned round the hand: the outermost lies along it and makes the wingtip.
        val np = k.primaries
        for (step in 0 until np) {
            val j = if (w.upper) np - 1 - step else step
            val g = if (np == 1) 1f else j / (np - 1f)
            val base = w.r + w.hand * (k.hand * 0.5f * g)
            // As the wing folds, the fan of primaries closes toward the line of the hand.
            val turn = mix(mix(0.3f, 1.47f, g.pow(0.85f)), 1.5f, 0.55f * w.folded)
            val dir = w.handChord * cos(turn) + w.hand * sin(turn)
            val len = mix(k.chordWrist, k.hand * 0.5f, g) + k.hand * 0.24f * k.round * sin(PI_F * g.pow(0.8f))
            val half = k.hand * 0.5f / np * 1.25f + 0.01f
            val tipWidth = mix(0.8f, 0.34f, k.slots * g * g)
            feather(base, dir, w.hand, len, half, tipWidth, shade(flight, if (j % 2 == 0) 1f else 0.92f), tipColour, if (bars != null) 4 else 0, bars, 0.87f - 0.27f * k.fingerTips * ramp(0.3f, 0.7f, g))
        }
        // Secondaries along the arm, pointing back.
        val ns = k.secondaries
        for (step in 0 until ns) {
            val i = if (w.upper) step else ns - 1 - step
            val f = (i + 0.5f) / ns
            val base = w.s + w.span * (k.arm * f)
            val turn = mix(-0.1f, 0.22f, f)
            val dir = w.chord * cos(turn) + w.span * sin(turn)
            val len = mix(k.chord, k.chordWrist, f)
            val half = k.arm / ns * 0.95f + 0.008f
            feather(base, dir, w.span, len, half, 0.85f, shade(flight, if (i % 2 == 0) 0.95f else 1.02f), tipColour, if (bars != null) 3 else 0, bars, 0.86f)
        }
        // The smaller feathers over the front of the wing, from shoulder to wrist and out along the hand.
        val front = shade(if (w.upper) k.coverts else k.underWing, lit)
        val o = outline.start()
        val lead = w.chord * -0.035f
        o.to(px(w.s + lead), py(w.s + lead)).to(px(w.r + lead * 1.3f), py(w.r + lead * 1.3f))
        val h1 = w.r + w.hand * (k.hand * 0.5f)
        o.tip(px(h1), py(h1))
        val h2 = w.r + w.hand * (k.hand * 0.3f) + w.handChord * (k.chordWrist * 0.4f)
        val r2 = w.r + w.chord * (k.chordWrist * 0.46f)
        val m2 = w.s + w.span * (k.arm * 0.5f) + w.chord * (mix(k.chord, k.chordWrist, 0.5f) * 0.5f)
        val s2 = w.s + w.chord * (k.chord * 0.55f)
        o.to(px(h2), py(h2)).to(px(r2), py(r2)).to(px(m2), py(m2)).to(px(s2), py(s2))
        path.rewind(); o.into(path)
        drawPath(path, front)
        if (w.upper) k.wingBar?.let { c ->
            line(px(s2), py(s2), px(m2), py(m2), shade(c, lit), 0.028f * scale); line(px(m2), py(m2), px(r2), py(r2), shade(c, lit), 0.028f * scale)
        }
        if (!w.upper) k.patagium?.let { c ->
            // The dark leading edge of a soaring hawk's inner wing, seen from below.
            val p1 = w.s + w.span * (k.arm * 0.12f) + w.chord * 0.02f; val p2 = w.r + w.chord * 0.02f
            line(px(p1), py(p1), px(p2), py(p2), shade(c, lit), 0.05f * scale)
        }
        // The wing's leading edge: it is never quite edge-on to nothing.
        val edge = shade(if (w.upper) k.coverts else k.underWing, lit * 0.8f)
        line(px(w.s), py(w.s), px(w.r), py(w.r), edge, 0.022f * scale); line(px(w.r), py(w.r), px(h1), py(h1), edge, 0.018f * scale)
    }

    /**
     * One flight feather: from [base] along [dir], [len] long and [half] to each side along [across], its end
     * narrowed to [tipWidth] of that and rounded. [tip] recolours its last third; [barCount] bars cross it.
     */
    private fun DrawScope.feather(base: P3, dir: P3, across: P3, len: Float, half: Float, tipWidth: Float, c: Color, tip: Color?, barCount: Int, bar: Color?, tipFrom: Float) {
        val b0 = base - across * half; val b1 = base + across * half
        val t0 = base + dir * (len * 0.84f) - across * (half * tipWidth); val t1 = base + dir * (len * 0.84f) + across * (half * tipWidth)
        val beyond = base + dir * (len * 1.1f)
        path.rewind()
        path.moveTo(px(b0), py(b0)); path.lineTo(px(t0), py(t0)); path.quadraticTo(px(beyond), py(beyond), px(t1), py(t1)); path.lineTo(px(b1), py(b1)); path.close()
        drawPath(path, c)
        if (tip != null || (bar != null && barCount > 0)) {
            val blade = Path().apply { addPath(path) }
            clipPath(blade) {
                if (bar != null) for (i in 1..barCount) {
                    val at = base + dir * (len * (i / (barCount + 1.2f)))
                    drawLine(bar, Offset(px(at - across * (half * 1.2f)), py(at - across * (half * 1.2f))), Offset(px(at + across * (half * 1.2f)), py(at + across * (half * 1.2f))), strokeWidth = max(0.6f, len * 0.055f * scale), alpha = 0.85f)
                }
                if (tip != null) {
                    val from = base + dir * (len * tipFrom)
                    drawLine(tip, Offset(px(from), py(from)), Offset(px(beyond), py(beyond)), strokeWidth = half * 3f * scale)
                }
            }
        }
        // A hair of shadow down one edge keeps neighbouring feathers apart.
        drawLine(shade(c, 0.7f), Offset(px(b1), py(b1)), Offset(px(t1), py(t1)), strokeWidth = max(0.5f, 0.006f * scale), alpha = 0.55f)
    }

    companion object {
        private const val BACK = 0.86f; private const val BELLY = 1.12f

        /** Wings through one beat: [q] 0 is the top of the downstroke. The way down is the working half; on the way up the wing folds and trails. */
        fun beat(k: BirdKind, q: Float): WingPose {
            val down = 0.5f
            return if (q < down) {
                val s = q / down; val ease = 0.5f + 0.5f * cos(PI_F * s)
                WingPose(flap = k.flapLo + (k.flapHi - k.flapLo) * ease, fold = 0.04f, droop = -0.14f * sin(PI_F * s), sweep = mix(0.06f, -0.2f, s))
            } else {
                val s = (q - down) / (1f - down); val ease = 0.5f - 0.5f * cos(PI_F * s); val bend = sin(PI_F * s).pow(0.8f)
                WingPose(flap = k.flapLo + (k.flapHi - k.flapLo) * ease, fold = 0.04f + k.foldMax * bend, droop = 0.5f * bend, sweep = mix(-0.2f, 0.06f, s) + 0.16f * bend)
            }
        }

        /** Wings held out on the air. */
        fun glide(k: BirdKind, fan: Float = 1f) = WingPose(flap = k.dihedral, fold = 0.08f, droop = -0.04f, sweep = 0.04f, fan = fan)

        val TUCKED = WingPose(0f, tucked = true, fan = 0.5f)
    }
}

// =====================================================================================
//  The kinds. Colours are the birds' own; the light of the hour is laid over them when they are drawn.
// =====================================================================================

internal object Birds {
    private val WHITE = Color(0xFFF2EFE8)

    /** A male northern cardinal: red all over, a black mask round a thick orange beak, a crest. */
    val CARDINAL = BirdKind(
        back = Color(0xFFB8261E), belly = Color(0xFFD23A2A), head = Color(0xFFCB2E22), throat = Color(0xFFD23A2A),
        coverts = Color(0xFFA9241F), flight = Color(0xFF8E2A26), underWing = Color(0xFFD9584A), underFlight = Color(0xFFB98078),
        tailUp = Color(0xFF9A2622), tailUnder = Color(0xFFB8564C), beakColor = Color(0xFFE8752E), mask = Color(0xFF1B1513),
        crest = 0.9f, beak = 0.15f, beakDepth = 0.085f, tail = 0.72f, tailFan = 0.12f,
    )

    /** A blue jay: blue above with black bars and white patches, pale below, a black necklace, a crest. */
    val BLUE_JAY = BirdKind(
        back = Color(0xFF4577C4), belly = Color(0xFFE6E6E2), head = Color(0xFF4C7FCB), throat = Color(0xFFEDEDEA),
        coverts = Color(0xFF3F74C6), flight = Color(0xFF2F5FAE), flightTip = WHITE, underWing = Color(0xFFD9DADC), underFlight = Color(0xFF9AA3B2),
        tailUp = Color(0xFF3566B8), tailUnder = Color(0xFF8E98AA), tailTip = WHITE, bars = Color(0xFF1B2333), wingBar = WHITE,
        beakColor = Color(0xFF1E1E22), necklace = Color(0xFF1A1C22), crest = 0.8f, tail = 0.75f, tailFan = 0.14f, beak = 0.14f, sideLine = -4f,
    )

    /** An American robin: brick-orange breast, grey-brown back, dark head with a broken white eye ring, yellow beak. */
    val ROBIN = BirdKind(
        back = Color(0xFF6A6058), belly = Color(0xFFE9E3D8), head = Color(0xFF2F2B2A), throat = Color(0xFFC96A33),
        breast = Color(0xFFC8642E), flank = Color(0xFFC8642E), vent = WHITE, coverts = Color(0xFF665C54), flight = Color(0xFF4F4741),
        underWing = Color(0xFFD08A5A), underFlight = Color(0xFF8D857E), tailUp = Color(0xFF3F3936), tailUnder = Color(0xFF6E6661),
        beakColor = Color(0xFFE2B33A), eyeRing = WHITE, girth = 0.215f, tail = 0.6f,
    )

    /** A male American goldfinch in summer: lemon yellow, black cap, black wings barred white. */
    val GOLDFINCH = BirdKind(
        back = Color(0xFFEDC92A), belly = Color(0xFFF5D83A), head = Color(0xFFEFCC2C), cap = Color(0xFF16130F), capFrom = 38f,
        coverts = Color(0xFF1B1814), flight = Color(0xFF191613), wingBar = WHITE, underWing = Color(0xFFE9E2C8), underFlight = Color(0xFF7D786E),
        tailUp = Color(0xFF1B1814), tailUnder = Color(0xFFB9B4A6), rump = WHITE, vent = WHITE, beakColor = Color(0xFFE79A5C),
        beak = 0.1f, beakDepth = 0.055f, tail = 0.45f, tailFork = 0.6f, girth = 0.21f, headR = 0.14f,
    )

    /** A black-capped chickadee: black cap and bib, white cheek, grey back, buff sides. */
    val CHICKADEE = BirdKind(
        back = Color(0xFF7E848C), belly = Color(0xFFEFEBE2), head = WHITE, throat = WHITE, cap = Color(0xFF1A1817), capFrom = 6f,
        bib = Color(0xFF1A1817), flank = Color(0xFFD9BE96), coverts = Color(0xFF6F757E), flight = Color(0xFF53585F), wingBar = Color(0xFFDDDDD8),
        underWing = Color(0xFFE2DED4), underFlight = Color(0xFF8E9096), tailUp = Color(0xFF53585F), tailUnder = Color(0xFF9A9CA0),
        beakColor = Color(0xFF1C1A19), beak = 0.07f, beakDepth = 0.035f, girth = 0.225f, headR = 0.155f, tail = 0.62f, tailFan = 0.1f,
    )

    /** A male eastern bluebird: bright blue above, rust on the breast, white below. */
    val BLUEBIRD = BirdKind(
        back = Color(0xFF3F7FD6), belly = Color(0xFFEDE8DE), head = Color(0xFF3F7FD6), throat = Color(0xFFC6743F),
        breast = Color(0xFFC6743F), flank = Color(0xFFC98150), vent = WHITE, coverts = Color(0xFF3A78D0), flight = Color(0xFF2E5FAE),
        underWing = Color(0xFFCFD6E2), underFlight = Color(0xFF8792A6), tailUp = Color(0xFF2F64B8), tailUnder = Color(0xFF7F8DA6),
        beakColor = Color(0xFF1E1D1D), tail = 0.5f, girth = 0.21f,
    )

    /** A sparrow, for a flock: streaked brown above, grey-buff below. */
    val SPARROW = BirdKind(
        back = Color(0xFF8A6A49), belly = Color(0xFFCFC3AE), head = Color(0xFF7A6248), throat = Color(0xFFD4CABA), cap = Color(0xFF6A4A32), capFrom = 34f,
        coverts = Color(0xFF8A6744), flight = Color(0xFF5E4A38), wingBar = Color(0xFFE2D8C4), underWing = Color(0xFFCFC6B5), underFlight = Color(0xFF8F877B),
        tailUp = Color(0xFF5E4A38), tailUnder = Color(0xFF8F877B), beakColor = Color(0xFF4A4038), beak = 0.09f, beakDepth = 0.055f, tail = 0.5f, girth = 0.215f,
    )

    /** An American crow: black, with the sky's sheen on its back and long fingered wingtips. */
    val CROW = BirdKind(
        back = Color(0xFF1C1E26), belly = Color(0xFF131418), head = Color(0xFF1A1C23), throat = Color(0xFF121317),
        coverts = Color(0xFF232632), flight = Color(0xFF16171D), underWing = Color(0xFF1A1B21), underFlight = Color(0xFF24262E),
        tailUp = Color(0xFF15161B), tailUnder = Color(0xFF202128), beakColor = Color(0xFF0F0F12),
        girth = 0.185f, headR = 0.12f, headX = 0.42f, beak = 0.2f, beakDepth = 0.055f, tail = 0.5f, tailFan = 0.2f, tailFeathers = 10,
        arm = 0.5f, hand = 0.62f, chord = 0.42f, chordWrist = 0.38f, primaries = 9, secondaries = 9, round = 0.6f, slots = 0.85f,
        flapHi = 0.8f, flapLo = -0.5f, foldMax = 0.45f, sideLine = 10f,
    )

    /** A Canada goose: black neck and head with a white chinstrap, brown back, pale breast, white under the tail. */
    val GOOSE = BirdKind(
        trunk = GOOSE_TRUNK, girth = 0.17f, neck = 0.3f, neckR = 0.034f, headR = 0.055f, headX = 0.4f, headY = 0.03f, beak = 0.085f, beakDepth = 0.028f,
        back = Color(0xFF6A5A4A), belly = Color(0xFFB9AB96), head = Color(0xFF17171A), throat = Color(0xFF17171A), cheek = WHITE,
        breast = Color(0xFFD2C6B2), vent = WHITE, rump = WHITE, coverts = Color(0xFF75644F), flight = Color(0xFF2F2924),
        underWing = Color(0xFF8C7C68), underFlight = Color(0xFF4A423B), tailUp = Color(0xFF1B1A1A), tailUnder = Color(0xFF242222),
        beakColor = Color(0xFF111113), tail = 0.2f, tailFan = 0.2f, tailFeathers = 8,
        arm = 0.5f, hand = 0.62f, chord = 0.36f, chordWrist = 0.32f, primaries = 9, secondaries = 10, round = 0.25f, slots = 0.3f,
        flapHi = 0.62f, flapLo = -0.42f, foldMax = 0.28f, sideLine = -30f,
    )

    /** A red-tailed hawk, mostly seen from below: pale, with dark wingtips, a dark bar on the wing's leading edge, a belly band and a rust tail. */
    val HAWK = BirdKind(
        girth = 0.2f, headR = 0.12f, headX = 0.4f, beak = 0.09f, beakDepth = 0.05f,
        back = Color(0xFF6F4E36), belly = Color(0xFFEFE6D2), head = Color(0xFF6B4A33), throat = Color(0xFFE9DFCA), bellyBand = Color(0xFF7A5236),
        coverts = Color(0xFF75553B), flight = Color(0xFF4E3828), underWing = Color(0xFFEDE4D0), underFlight = Color(0xFFE2D9C6), underTip = Color(0xFF3A2C22),
        bars = Color(0xFFC9BDA8), patagium = Color(0xFF5E4330), tailUp = Color(0xFFB5552B), tailUnder = Color(0xFFE0A070), tailTip = Color(0xFF6A3A22),
        beakColor = Color(0xFF3A3632), tail = 0.5f, tailFan = 0.34f, tailFeathers = 10,
        arm = 0.6f, hand = 0.72f, chord = 0.56f, chordWrist = 0.54f, primaries = 10, secondaries = 10, round = 0.9f, slots = 1f,
        flapHi = 0.7f, flapLo = -0.4f, foldMax = 0.3f, dihedral = 0.16f, sideLine = 0f, fingerTips = 1f,
    )

    /** A barn owl: white below, gold and grey above, a white heart of a face, broad round wings. */
    val BARN_OWL = BirdKind(
        girth = 0.23f, headR = 0.2f, headX = 0.34f, headY = 0.02f, beak = 0f, beakDepth = 0.02f, faceDisc = Color(0xFFF7F4EC),
        back = Color(0xFFCFA460), belly = Color(0xFFF6F2E9), head = Color(0xFFD3AB6A), throat = Color(0xFFF7F4EC),
        coverts = Color(0xFFCFA765), flight = Color(0xFFC9A873), underWing = Color(0xFFF7F3EA), underFlight = Color(0xFFEFEAE0), underTip = Color(0xFFCFC8BC),
        bars = Color(0xFFB09A7A), tailUp = Color(0xFFD4B47E), tailUnder = Color(0xFFF0EBE0), beakColor = Color(0xFFE6D9C6),
        tail = 0.3f, tailFan = 0.26f, tailFeathers = 8,
        arm = 0.6f, hand = 0.7f, chord = 0.56f, chordWrist = 0.54f, primaries = 10, secondaries = 9, round = 1f, slots = 0.25f,
        flapHi = 0.85f, flapLo = -0.5f, foldMax = 0.3f, dihedral = 0.06f, sideLine = 6f,
    )
}
