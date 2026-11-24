package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * A squirrel out along a branch.
 *
 * A squirrel does not cross open sky, so it brings its branch with it: the end of a bough that hangs just above the
 * screen dips into view as the squirrel comes out along it. It runs in bounds, stops, runs on to near the tip, sits
 * up with its tail curled over its back, flicks the tail, looks about; then it gathers itself and springs up and
 * away to the next tree, and the branch whips back out of sight and shakes its leaves.
 *
 * The squirrel is a jointed figure (spine, head, two-part forelegs, three-part hind legs, a tail on a curve) painted
 * in its own colours, pose by pose, into a Sheet before it appears. Each frame draws the branch as a few thick
 * lines, places its leaves, and places one of those poses on it.
 */

/**
 * A pose from the side, nose toward +x. One unit is nose to rump; y is down and the ground under it is y = 0.
 * The hind foot is given by its toes; [heel] 0 lays the foot flat behind them, 1 carries it in line with the leg.
 */
private class Pose(
    val hx: Float, val hy: Float, val sx: Float, val sy: Float, val arch: Float,
    val kx: Float, val ky: Float, val nose: Float,
    val fx: Float, val fy: Float, val bx: Float, val by: Float, val heel: Float,
    val tail: FloatArray,
) {
    fun toward(o: Pose, t: Float) = Pose(
        mix(hx, o.hx, t), mix(hy, o.hy, t), mix(sx, o.sx, t), mix(sy, o.sy, t), mix(arch, o.arch, t),
        mix(kx, o.kx, t), mix(ky, o.ky, t), mix(nose, o.nose, t),
        mix(fx, o.fx, t), mix(fy, o.fy, t), mix(bx, o.bx, t), mix(by, o.by, t), mix(heel, o.heel, t),
        FloatArray(8) { mix(tail[it], o.tail[it], t) },
    )
}

private object Poses {
    // A bound: stretched out in the air, forepaws down, gathered with the back arched, pushing off, away.
    val RUN = listOf(
        Pose(-0.36f, -0.24f, 0.22f, -0.22f, 0.01f, 0.47f, -0.26f, 0.08f, 0.66f, -0.13f, -0.74f, -0.13f, 1f, floatArrayOf(-0.74f, -0.33f, -1.04f, -0.36f, -1.30f, -0.31f, -1.50f, -0.24f)),
        Pose(-0.32f, -0.29f, 0.23f, -0.19f, 0.05f, 0.46f, -0.20f, 0.20f, 0.36f, 0f, -0.56f, -0.22f, 1f, floatArrayOf(-0.70f, -0.40f, -0.98f, -0.47f, -1.24f, -0.43f, -1.44f, -0.34f)),
        Pose(-0.16f, -0.31f, 0.21f, -0.22f, 0.13f, 0.43f, -0.24f, 0.15f, 0.22f, 0f, 0.05f, 0f, 0.2f, floatArrayOf(-0.40f, -0.56f, -0.60f, -0.78f, -0.86f, -0.86f, -1.08f, -0.80f)),
        Pose(-0.21f, -0.27f, 0.27f, -0.28f, 0.07f, 0.50f, -0.34f, -0.05f, 0.50f, -0.24f, -0.10f, 0f, 0.6f, floatArrayOf(-0.50f, -0.52f, -0.78f, -0.64f, -1.04f, -0.62f, -1.26f, -0.52f)),
        Pose(-0.33f, -0.24f, 0.25f, -0.27f, 0.03f, 0.49f, -0.32f, 0f, 0.62f, -0.22f, -0.50f, -0.05f, 1f, floatArrayOf(-0.64f, -0.42f, -0.94f, -0.48f, -1.20f, -0.44f, -1.42f, -0.34f)),
    )
    val STAND = Pose(-0.26f, -0.25f, 0.22f, -0.27f, 0.06f, 0.42f, -0.42f, -0.10f, 0.27f, 0f, -0.14f, 0f, 0f, floatArrayOf(-0.56f, -0.42f, -0.72f, -0.72f, -0.58f, -0.98f, -0.36f, -1.06f))
    val SIT = Pose(-0.10f, -0.17f, 0.08f, -0.52f, 0.09f, 0.19f, -0.75f, 0.15f, 0.27f, -0.50f, 0.14f, 0f, 0f, floatArrayOf(-0.42f, -0.36f, -0.44f, -0.76f, -0.20f, -1.00f, -0.36f, -1.17f))
    val FLICK = Pose(-0.10f, -0.17f, 0.08f, -0.52f, 0.09f, 0.19f, -0.75f, 0.15f, 0.27f, -0.50f, 0.14f, 0f, 0f, floatArrayOf(-0.38f, -0.40f, -0.26f, -0.82f, 0.02f, -0.98f, -0.10f, -1.18f))
    val LOOK = Pose(-0.10f, -0.17f, 0.07f, -0.53f, 0.09f, 0.15f, -0.79f, -0.28f, 0.25f, -0.52f, 0.14f, 0f, 0f, floatArrayOf(-0.42f, -0.36f, -0.44f, -0.76f, -0.20f, -1.00f, -0.36f, -1.17f))
    val CROUCH = Pose(-0.25f, -0.20f, 0.22f, -0.17f, 0.11f, 0.45f, -0.19f, 0.10f, 0.36f, 0f, -0.08f, 0f, 0f, floatArrayOf(-0.60f, -0.24f, -0.90f, -0.20f, -1.15f, -0.14f, -1.36f, -0.10f))
    val LEAP = Pose(-0.37f, -0.22f, 0.24f, -0.24f, 0f, 0.49f, -0.29f, 0f, 0.70f, -0.20f, -0.78f, -0.10f, 1f, floatArrayOf(-0.74f, -0.30f, -1.04f, -0.32f, -1.30f, -0.30f, -1.50f, -0.27f))

    const val RUN_FRAMES = 10
    const val STAND_AT = RUN_FRAMES; const val SIT_IN = STAND_AT + 1; const val SIT_AT = SIT_IN + 1; const val FLICK_IN = SIT_AT + 1; const val FLICK_AT = FLICK_IN + 1
    const val LOOK_AT = FLICK_AT + 1; const val CROUCH_IN = LOOK_AT + 1; const val CROUCH_AT = CROUCH_IN + 1; const val LEAP_AT = CROUCH_AT + 1; const val COUNT = LEAP_AT + 1

    /** Every picture the scene uses, in the order above. */
    fun all(): List<Pose> {
        val run = List(RUN_FRAMES) { val at = it * RUN.size / RUN_FRAMES.toFloat(); val i = at.toInt(); RUN[i].toward(RUN[(i + 1) % RUN.size], at - i) }
        return run + listOf(STAND, STAND.toward(SIT, 0.5f), SIT, SIT.toward(FLICK, 0.5f), FLICK, LOOK, SIT.toward(CROUCH, 0.55f), CROUCH, LEAP)
    }
}

/** A coat: an eastern grey squirrel, or a rustier fox squirrel. */
private class Coat(val back: Color, val flank: Color, val belly: Color, val face: Color, val tail: Color, val core: Color, val fringe: Color, val paw: Color)

private val GREY = Coat(Color(0xFF7F756A), Color(0xFFA2988C), Color(0xFFF2EEE6), Color(0xFF9C8568), Color(0xFF8F867B), Color(0xFF575049), Color(0xFFE2DDD4), Color(0xFF5F554C))
private val FOX = Coat(Color(0xFF8E6A46), Color(0xFFB98A56), Color(0xFFE9B77A), Color(0xFFB98452), Color(0xFFA9703C), Color(0xFF5E432C), Color(0xFFE8B678), Color(0xFF5B432F))

private fun dim(c: Color, k: Float) = Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha)

/** Paints a squirrel pose at [u] pixels to a body length, with the ground under it at the canvas's origin. */
private class SquirrelPainter(private val u: Float, private val c: Coat) {
    private val path = Path(); private val o = Outline()
    private var jx = 0f; private var jy = 0f

    fun DrawScope.paint(p: Pose) {
        hindLeg(p, -0.05f, -0.015f, 0.68f); foreLeg(p, 0.045f, -0.01f, 0.68f)     // the far side's legs, in shade
        tail(p)
        body(p)
        head(p)
        hindLeg(p, 0f, 0f, 1f)
        foreLeg(p, 0f, 0f, 1f)
    }

    /** A limb between two joints, tapering from [r0] to [r1], rounded at both ends. */
    private fun DrawScope.part(x0: Float, y0: Float, x1: Float, y1: Float, r0: Float, r1: Float, colour: Color) {
        val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-4f, sqrt(dx * dx + dy * dy)); val ax = dx / l; val ay = dy / l
        path.rewind()
        o.start().to(x0 - ay * r0, y0 + ax * r0).to(x1 - ay * r1, y1 + ax * r1).to(x1 + ax * r1, y1 + ay * r1)
            .to(x1 + ay * r1, y1 - ax * r1).to(x0 + ay * r0, y0 - ax * r0).to(x0 - ax * r0, y0 - ay * r0).into(path)
        withTransform({ scale(u, u, Offset.Zero) }) { drawPath(path, colour) }
    }

    /** Where the middle joint of a two-part limb goes, from ([x0], [y0]) to ([x1], [y1]) with parts [a] and [b] long; it bends to the [side] given. */
    private fun bend(x0: Float, y0: Float, x1: Float, y1: Float, a: Float, b: Float, side: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val d = min(a + b - 1e-3f, max(1e-3f, sqrt(dx * dx + dy * dy)))
        val along = (a * a - b * b + d * d) / (2f * d); val out = sqrt(max(0f, a * a - along * along))
        val l = max(1e-3f, sqrt(dx * dx + dy * dy)); val ux = dx / l; val uy = dy / l
        jx = x0 + ux * along - uy * out * side; jy = y0 + uy * along + ux * out * side
    }

    private fun DrawScope.foreLeg(p: Pose, ox: Float, oy: Float, light: Float) {
        val x0 = p.sx - 0.02f + ox; val y0 = p.sy + 0.07f + oy; val x1 = p.fx + ox; val y1 = p.fy + oy - 0.02f
        bend(x0, y0, x1, y1, 0.21f, 0.22f, 1f)                                // the elbow points back
        part(x0, y0, jx, jy, 0.07f, 0.048f, dim(c.flank, light)); part(jx, jy, x1, y1, 0.045f, 0.03f, dim(c.flank, light))
        part(x1 - 0.01f, y1, x1 + 0.05f, y1 + 0.005f, 0.028f, 0.02f, dim(c.paw, light))
    }

    private fun DrawScope.hindLeg(p: Pose, ox: Float, oy: Float, light: Float) {
        val x0 = p.hx + 0.03f + ox; val y0 = p.hy + 0.05f + oy; val tx = p.bx + ox; val ty = p.by + oy - 0.02f
        // The heel: flat on the ground behind the toes, or carried in line with the leg.
        val dx = x0 - tx; val dy = y0 - ty; val l = max(1e-3f, sqrt(dx * dx + dy * dy))
        val ex = mix(tx - 0.2f, tx + dx / l * 0.2f, p.heel); val ey = mix(ty, ty + dy / l * 0.2f, p.heel)
        bend(x0, y0, ex, ey, 0.25f, 0.25f, -1f)                               // the knee points forward
        part(jx, jy, ex, ey, 0.058f, 0.036f, dim(c.flank, light))
        part(ex, ey, tx, ty, 0.036f, 0.026f, dim(c.paw, light))
        part(x0, y0, jx, jy, 0.135f, 0.075f, dim(c.back, light * 1.04f))      // the haunch
    }

    private fun DrawScope.body(p: Pose) {
        val dx = p.sx - p.hx; val dy = p.sy - p.hy; val l = max(1e-3f, sqrt(dx * dx + dy * dy)); val ax = dx / l; val ay = dy / l
        val nx = ay; val ny = -ax                                              // toward its back
        // The spine: hip, the arch of the back, shoulder; then on up the neck to the head.
        val cx = (p.hx + p.sx) / 2f + nx * p.arch * 2f; val cy = (p.hy + p.sy) / 2f + ny * p.arch * 2f
        path.rewind(); o.start()
        for (pass in 0..1) for (step in 0..6) {
            val i = if (pass == 0) step else 6 - step
            val x: Float; val y: Float; val r: Float; var tx: Float; var ty: Float
            if (i <= 4) {
                val s = i / 4f; val a = (1f - s) * (1f - s); val b = 2f * s * (1f - s); val k = s * s
                x = a * p.hx + b * cx + k * p.sx; y = a * p.hy + b * cy + k * p.sy
                tx = 2f * (1f - s) * (cx - p.hx) + 2f * s * (p.sx - cx); ty = 2f * (1f - s) * (cy - p.hy) + 2f * s * (p.sy - cy)
                r = when (i) { 0 -> 0.185f; 1 -> 0.215f; 2 -> 0.205f; 3 -> 0.18f; else -> 0.155f }
            } else {
                val s = (i - 4) / 2f
                x = mix(p.sx, p.kx, s); y = mix(p.sy, p.ky, s); tx = p.kx - p.sx; ty = p.ky - p.sy
                r = if (i == 5) 0.13f else 0.11f
            }
            val tl = max(1e-3f, sqrt(tx * tx + ty * ty)); tx /= tl; ty /= tl
            val side = if (pass == 0) 0.9f else -1f                              // flatter along the back than under the belly
            o.to(x + ty * r * side, y - tx * r * side)
            if (pass == 0 && i == 6) o.to(x + tx * r * 0.6f, y + ty * r * 0.6f)
            if (pass == 1 && i == 0) o.to(x - tx * r * 0.8f, y - ty * r * 0.8f)   // the round of the rump
        }
        o.into(path)
        val mx = (p.hx + p.sx) / 2f; val my = (p.hy + p.sy) / 2f
        val fur = Brush.linearGradient(
            0f to c.belly, 0.24f to c.belly, 0.46f to c.flank, 0.7f to c.back, 1f to dim(c.back, 1.1f),
            start = Offset(mx - nx * 0.23f, my - ny * 0.23f), end = Offset(mx + nx * 0.23f, my + ny * 0.23f),
        )
        withTransform({ scale(u, u, Offset.Zero) }) { drawPath(path, fur) }
    }

    private fun DrawScope.head(p: Pose) {
        val nx = cos(p.nose); val ny = sin(p.nose)           // the way the nose points
        val ux = ny; val uy = -nx                            // toward the top of the head
        // The far ear, then the skull and muzzle as one outline, then the near ear.
        ear(p.kx - nx * 0.085f + ux * 0.1f, p.ky - ny * 0.085f + uy * 0.1f, nx, ny, ux, uy, dim(c.face, 0.7f))
        path.rewind()
        o.start().to(p.kx - nx * 0.15f, p.ky - ny * 0.15f).to(p.kx - nx * 0.09f + ux * 0.125f, p.ky - ny * 0.09f + uy * 0.125f)
            .to(p.kx + nx * 0.06f + ux * 0.12f, p.ky + ny * 0.06f + uy * 0.12f).to(p.kx + nx * 0.2f + ux * 0.045f, p.ky + ny * 0.2f + uy * 0.045f)
            .to(p.kx + nx * 0.255f - ux * 0.005f, p.ky + ny * 0.255f - uy * 0.005f).to(p.kx + nx * 0.2f - ux * 0.07f, p.ky + ny * 0.2f - uy * 0.07f)
            .to(p.kx + nx * 0.03f - ux * 0.12f, p.ky + ny * 0.03f - uy * 0.12f).to(p.kx - nx * 0.11f - ux * 0.1f, p.ky - ny * 0.11f - uy * 0.1f).into(path)
        val skull = Brush.linearGradient(
            0f to c.belly, 0.3f to lerp(c.belly, c.face, 0.5f), 0.6f to c.face, 1f to lerp(c.face, c.back, 0.6f),
            start = Offset(p.kx - ux * 0.13f, p.ky - uy * 0.13f), end = Offset(p.kx + ux * 0.13f, p.ky + uy * 0.13f),
        )
        withTransform({ scale(u, u, Offset.Zero) }) {
            drawPath(path, skull)
            val ex = p.kx + nx * 0.07f + ux * 0.035f; val ey = p.ky + ny * 0.07f + uy * 0.035f
            drawCircle(Color(0xFFEDE4D4), 0.043f, Offset(ex, ey))                                  // the pale ring round the eye
            drawCircle(Color(0xFF15100C), 0.031f, Offset(ex, ey))
            drawCircle(Color(0xE6FFFFFF), 0.01f, Offset(ex - 0.01f, ey - 0.012f))
            drawCircle(Color(0xFF2A1F1A), 0.018f, Offset(p.kx + nx * 0.25f, p.ky + ny * 0.25f))    // nose
        }
        ear(p.kx - nx * 0.05f + ux * 0.105f, p.ky - ny * 0.05f + uy * 0.105f, nx, ny, ux, uy, c.face)
    }

    private fun DrawScope.ear(x: Float, y: Float, nx: Float, ny: Float, ux: Float, uy: Float, colour: Color) {
        path.rewind()
        o.start().to(x - nx * 0.05f, y - ny * 0.05f).to(x - nx * 0.035f + ux * 0.1f, y - ny * 0.035f + uy * 0.1f).to(x + nx * 0.02f + ux * 0.115f, y + ny * 0.02f + uy * 0.115f)
            .to(x + nx * 0.045f, y + ny * 0.045f).into(path)
        withTransform({ scale(u, u, Offset.Zero) }) { drawPath(path, colour) }
    }

    /** The tail: a plume along a curve from the rump through the pose's four points, swelling toward the end; a dark core, a pale frosted edge. */
    private fun DrawScope.tail(p: Pose) {
        val n = 13; val xs = FloatArray(n); val ys = FloatArray(n)
        val px = floatArrayOf(p.hx - 0.02f, p.hx - 0.14f, p.tail[0], p.tail[2], p.tail[4], p.tail[6], 2f * p.tail[6] - p.tail[4])
        val py = floatArrayOf(p.hy + 0.02f, p.hy - 0.04f, p.tail[1], p.tail[3], p.tail[5], p.tail[7], 2f * p.tail[7] - p.tail[5])
        for (i in 0 until n) {
            // A smooth curve through the points (the first and last only steer it).
            val along = 1f + i / (n - 1f) * 4f; val k = min(4, along.toInt()); val t = along - k
            fun through(v: FloatArray) = 0.5f * ((2f * v[k]) + (-v[k - 1] + v[k + 1]) * t + (2f * v[k - 1] - 5f * v[k] + 4f * v[k + 1] - v[k + 2]) * t * t + (-v[k - 1] + 3f * v[k] - 3f * v[k + 1] + v[k + 2]) * t * t * t)
            xs[i] = through(px); ys[i] = through(py)
        }
        fun plume(width: Float, colour: Color, alpha: Float) {
            path.rewind(); o.start()
            for (pass in 0..1) for (step in 0 until n) {
                val i = if (pass == 0) step else n - 1 - step
                val a = max(0, i - 1); val b = min(n - 1, i + 1)
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]; val l = max(1e-4f, sqrt(dx * dx + dy * dy))
                val s = i / (n - 1f)
                val half = (0.03f + 0.17f * sin(PI_F * (min(1f, s * 1.3f) * 0.68f + 0.04f))) * width * (if (s > 0.9f) 1f - (s - 0.9f) * 3.5f else 1f)
                val side = if (pass == 0) 1f else -1f
                o.to(xs[i] - dy / l * half * side, ys[i] + dx / l * half * side)
                if (pass == 0 && i == n - 1) o.to(xs[i] + dx / l * half * 0.9f, ys[i] + dy / l * half * 0.9f)
            }
            o.into(path)
            withTransform({ scale(u, u, Offset.Zero) }) { drawPath(path, colour, alpha) }
        }
        plume(1.17f, c.fringe, 0.9f)
        plume(1f, c.tail, 1f)
        plume(0.42f, c.core, 0.85f)
    }
}

/** A leaf from above, its stalk at the origin and its tip toward +x: one unit long. */
private fun leafShape(): Path = Path().also {
    Outline().start().tip(0f, 0f).to(0.22f, -0.2f).to(0.55f, -0.24f).to(0.85f, -0.12f).tip(1f, 0f).to(0.85f, 0.11f).to(0.55f, 0.2f).to(0.22f, 0.17f).into(it)
}

internal class SquirrelOnABranch(r: Random, private val season: Season) : Visit(Creature.SQUIRREL) {
    private val dir = r.side()                         // the way the squirrel runs: the tree it comes from is off the other edge
    private val u = r.between(116f, 136f)              // pixels to a body length, on a 1080p screen
    private val len = r.between(820f, 920f)            // the branch
    private val sag = r.between(325f, 355f)            // how far its tip dips under the squirrel
    private val rootOut = r.between(30f, 90f)          // how far beyond the edge the branch starts, and how far above the top
    private val rootUp = r.between(44f, 50f)
    private val coat = if (r.nextFloat() < 0.25f) FOX else GREY
    private val t1 = r.between(1.05f, 1.25f)           // the first run, out to where it pauses
    private val t2 = t1 + r.between(0.5f, 0.9f)        // on again
    private val t3 = t2 + r.between(0.75f, 0.9f)       // it reaches its seat near the tip
    private val t4 = t3 + r.between(2.6f, 3.6f)        // it gathers itself
    private val t5 = t4 + 0.42f                        // and springs
    override val duration = t5 + 1.9f
    override val count get() = 2                       // the squirrel, and the branch
    override val reach = 0f
    private val flick1 = t3 + r.between(0.5f, 0.9f); private val flick2 = flick1 + r.between(0.5f, 0.8f); private val look = flick2 + r.between(0.35f, 0.6f)
    private val swayAt = r.between(0f, TAU)

    // Twigs and leaves along the outer half of the branch: where, how the twig hangs, how big the leaf, which colour.
    private val leafCount = if (season == Season.WINTER) 0 else if (season == Season.SUMMER) 11 else if (season == Season.AUTUMN) 8 else 7
    private val leafAt = FloatArray(leafCount) { 0.5f + 0.5f * (it + r.between(0.15f, 0.85f)) / leafCount }
    private val leafHang = FloatArray(leafCount) { r.between(0.9f, 2.1f) * (if (it % 2 == 0) 1f else -0.35f) } // angle from along the branch, radians: mostly hanging below
    private val leafSize = FloatArray(leafCount) { r.between(20f, 27f) * (if (season == Season.SPRING) 0.7f else 1f) }
    private val leafKind = IntArray(leafCount) { r.nextInt(3) }
    private val leafPhase = FloatArray(leafCount) { r.between(0f, TAU) }

    private var poses: Sheet? = null
    private var foliage: Sheet? = null
    private var bakedFor = 0f
    private var cellW = 0; private var cellH = 0

    // ------------------------------------------------------------------ the script, as functions of time

    /** How far along the branch the squirrel is, 0 at the tree to 1 at the tip. */
    private fun along(t: Float): Float = when {
        t < t1 -> mix(0.16f, 0.6f, ramp(0f, t1, t))
        t < t2 -> 0.6f
        t < t3 -> mix(0.6f, SEAT, ramp(t2, t3, t))
        else -> SEAT
    }

    /** How far the tip is pulled down at [t], in 1080p pixels (negative: flung above where it rests). */
    private fun dip(t: Float): Float {
        if (t >= t5) { val s = t - t5; return sag * 1.12f * exp(-2.1f * s) * cos(TAU * 1.7f * s) }
        val a = along(t)
        val weight = ramp(0f, 0.3f, t) * (1f + 0.12f * exp(-3f * t) * sin(TAU * 2.2f * t))   // it lands on the branch, which bounces
        val running = if (t < t1 || (t > t2 && t < t3)) 1f else 0f
        val push = if (t > t4) 0.14f * ramp(t4, t5, t) else 0f                               // crouching to spring presses it down
        return sag * (weight + push + 0.03f * running * sin(TAU * BOUNDS * t)) * load(a) / load(SEAT)
    }

    /** The tip's dip under a weight at [a] along the branch, against the same weight at the tip. */
    private fun load(a: Float) = a * a * (3f - a) / 2f

    /** Where the weight is: after it springs the branch swings freely, as if the weight had been at its tip. */
    private fun weightAt(t: Float) = if (t >= t5) 1f else along(t)

    /** The branch at [s] along it (0 to 1): across from the edge it grows from, and down from the top, in 1080p pixels. */
    private fun branchX(s: Float, d: Float): Float { val bend = d * load(s) / max(60f, s * len); return -rootOut + s * len * (1f - 0.42f * bend * bend) }
    private fun branchY(s: Float, a: Float, d: Float): Float {
        val shape = if (s <= a) s * s * (3f * a - s) / 2f else a * a * (3f * s - a) / 2f
        return -rootUp - 12f * sin(PI_F * 0.9f * s) + d * shape / load(a)
    }

    private fun screenX(x: Float, w: Float, sc: Float) = if (dir > 0f) x * sc else w - x * sc

    /** Seconds since it sprang, or negative. Its spring carries it up and forward, out over the top of the screen. */
    private fun leapX(s: Float) = 640f * s
    private fun leapY(s: Float, from: Float): Float { val rise = sqrt(2f * GRAVITY * (from + 2.2f * u + 60f)); val top = rise / GRAVITY; val q = min(s, top); return from - rise * q + 0.5f * GRAVITY * q * q }

    override fun place(i: Int, t: Float, w: Float, h: Float): Offset {
        val sc = h / 1080f; val a = weightAt(t); val d = dip(t)
        if (i == 1) return Offset(screenX(branchX(1f, d), w, sc), branchY(1f, a, d) * sc)
        if (t < t5) { val s = along(t); return Offset(screenX(branchX(s, d), w, sc), (branchY(s, s, d) - girth(s) / 2f) * sc) }
        val d5 = dip(t5 - 0.001f); val x0 = branchX(SEAT, d5); val y0 = branchY(SEAT, SEAT, d5) - girth(SEAT) / 2f
        val s = t - t5
        return Offset(screenX(x0 + leapX(min(s, 1.2f)), w, sc), leapY(s, y0) * sc)
    }

    override fun box(i: Int, t: Float, w: Float, h: Float): Rect {
        val sc = h / 1080f
        if (i == 0) {
            // Its picture reaches back to the tip of its tail, up to the top of a raised tail and barely below its feet; then it is turned to the slope.
            val p = place(0, t, w, h); val m = u * sc
            val turn = if (t >= t5) 1.1f else slope(along(t), weightAt(t), dip(t)) / DEG
            val c = kotlin.math.abs(cos(turn)); val s = kotlin.math.abs(sin(turn))
            val wide = 1.8f * c + 1.5f * s; val up = 1.5f * c + 1.8f * s; val down = 0.14f * c + 1.8f * s
            return Rect(p.x - wide * m, p.y - up * m, p.x + wide * m, p.y + down * m)
        }
        val a = weightAt(t); val d = dip(t)
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (k in 0..8) { val y = branchY(k / 8f, a, d); top = min(top, y); bottom = max(bottom, y) }
        val x0 = screenX(-rootOut - 20f, w, sc); val x1 = screenX(len + 50f, w, sc)
        return Rect(min(x0, x1), (top - 40f) * sc, max(x0, x1), (bottom + HANG) * sc)
    }

    private fun girth(s: Float) = mix(24f, 6f, s)

    // ------------------------------------------------------------------ painting it

    override fun prepare(h: Float) {
        if (bakedFor == h) return
        val scale = u * h / 1080f
        cellW = (scale * 3.3f).toInt() + 6; cellH = (scale * 2.1f).toInt() + 6
        val painter = SquirrelPainter(scale, coat); val all = Poses.all()
        // Each pose is painted with the ground under its feet a little below the middle of its cell.
        poses = Sheet(all.size, cellW, cellH) { i -> withTransform({ translate(scale * 0.35f, scale * 0.72f) }) { with(painter) { paint(all[i]) } } }
        if (leafCount > 0) {
            val tints = when (season) {
                Season.SPRING -> listOf(Color(0xFF9CC766), Color(0xFF86B955), Color(0xFFB2D27A))
                Season.SUMMER -> listOf(Color(0xFF4F8A3C), Color(0xFF5E9A45), Color(0xFF3F7A36))
                else -> listOf(Color(0xFFD9822B), Color(0xFFC4502A), Color(0xFFE0B03A))
            }
            val size = 28f * h / 1080f; val shape = leafShape()
            foliage = Sheet(3, (size * 2.3f).toInt() + 4, (size * 0.8f).toInt() + 4) { i ->
                withTransform({ scale(size, size, Offset.Zero) }) {
                    drawPath(shape, Brush.linearGradient(listOf(dim(tints[i], 0.82f), tints[i], dim(tints[i], 1.12f)), start = Offset(0f, 0.2f), end = Offset(0.9f, -0.2f)))
                    drawLine(dim(tints[i], 0.62f), Offset(0f, 0f), Offset(0.94f, 0f), strokeWidth = 0.035f)
                }
            }
        }
        bakedFor = h
    }

    override fun DrawScope.paint(t: Float, light: Light, alpha: Float) {
        val w = size.width; val h = size.height; val sc = h / 1080f
        prepare(h)
        val filter = light.at(0f)
        val a = weightAt(t); val d = dip(t)

        // The branch: a run of thick lines, bark-dark with a paler upper side, tapering to the tip.
        var px = screenX(branchX(0f, d), w, sc); var py = branchY(0f, a, d) * sc
        for (k in 1..SEGMENTS) {
            val s = k / SEGMENTS.toFloat()
            val x = screenX(branchX(s, d), w, sc); val y = branchY(s, a, d) * sc
            if (max(py, y) > -30f * sc) {
                val g = girth(s - 0.5f / SEGMENTS) * sc
                drawLine(BARK, Offset(px, py), Offset(x, y), strokeWidth = g, cap = StrokeCap.Round, alpha = alpha, colorFilter = filter)
                drawLine(BARK_LIT, Offset(px, py - g * 0.24f), Offset(x, y - g * 0.24f), strokeWidth = g * 0.36f, cap = StrokeCap.Round, alpha = alpha, colorFilter = filter)
            }
            px = x; py = y
        }
        leaves(t, a, d, w, sc, alpha, filter)

        val sheet = poses ?: return
        val at = place(0, t, w, h)
        if (at.y < -0.9f * u * sc) return
        val cell: Int; var tilt: Float
        val slope = slope(along(t), a, d)
        if (t >= t5) {
            // In the air: stretched out along the way it is going.
            val s = t - t5; val next = place(0, t + 0.03f, w, h)
            // Its body follows the way it is going, though less steeply.
            cell = Poses.LEAP_AT; tilt = atan2(next.y - at.y, (next.x - at.x) * dir) * DEG * 0.8f
            if (s < 0.08f) tilt = mix(slope, tilt, s / 0.08f)
        } else if (t < t1 || (t > t2 && t < t3 - 0.12f)) {
            cell = pose(frac(t * BOUNDS), Poses.RUN_FRAMES); tilt = slope
        } else if (t < t2) {
            cell = Poses.STAND_AT; tilt = slope * 0.8f
        } else if (t < t3 + 0.1f) {
            cell = if (t < t3) Poses.STAND_AT else Poses.SIT_IN; tilt = slope * 0.6f
        } else if (t < t4) {
            // Sitting up. Twice the tail flicks; once it lifts its head to look.
            val f1 = flickAt(t - flick1); val f2 = flickAt(t - flick2)
            cell = if (f1 == 2 || f2 == 2) Poses.FLICK_AT else if (f1 == 1 || f2 == 1) Poses.FLICK_IN else if (t > look && t < look + 0.9f) Poses.LOOK_AT else Poses.SIT_AT
            tilt = slope * 0.3f
        } else {
            cell = if (t < t4 + 0.14f) Poses.CROUCH_IN else Poses.CROUCH_AT; tilt = slope * 0.8f
        }
        val scale = u * sc
        withTransform({ translate(at.x, at.y); rotate(tilt * dir, Offset.Zero); scale(dir, 1f, Offset.Zero); translate(-scale * 0.35f, -scale * 0.72f) }) {
            sheet.stamp(this, cell, alpha, filter)
        }
    }

    /** A flick of the tail takes a third of a second: over, across, and back. 0: at rest, 1: halfway, 2: over. */
    private fun flickAt(s: Float): Int = if (s < 0f || s > 0.34f) 0 else if (s < 0.08f || s > 0.26f) 1 else 2

    /** The branch's slope where the squirrel stands, in degrees below level along the way it runs. */
    private fun slope(s: Float, a: Float, d: Float): Float {
        val s0 = max(0f, s - 0.04f); val s1 = min(1f, s + 0.04f)
        return atan2(branchY(s1, a, d) - branchY(s0, a, d), branchX(s1, d) - branchX(s0, d)) * DEG
    }

    private fun DrawScope.leaves(t: Float, a: Float, d: Float, w: Float, sc: Float, alpha: Float, filter: ColorFilter) {
        val sheet = foliage ?: return
        // They stir in the air, and shake when the branch is flung back.
        val shake = if (t >= t5) 34f * exp(-1.9f * (t - t5)) else 5f * exp(-3f * t)
        for (i in 0 until leafCount) {
            val s = leafAt[i]
            val y = branchY(s, a, d) * sc
            if (y < -HANG * sc) continue
            val x = screenX(branchX(s, d), w, sc)
            val swing = 7f * sin(1.6f * t + leafPhase[i] + swayAt) + shake * sin(21f * t + leafPhase[i])
            val k = leafSize[i] / 28f
            withTransform({ translate(x, y); scale(dir, 1f, Offset.Zero); rotate(slope(s, a, d) + leafHang[i] * DEG + swing, Offset.Zero); scale(k, k, Offset.Zero) }) {
                sheet.stamp(this, leafKind[i], alpha, filter)
            }
        }
    }

    private companion object {
        const val SEAT = 0.9f          // how far out along the branch it sits
        const val BOUNDS = 3.6f        // bounds a second at a run
        const val GRAVITY = 800f       // 1080p pixels a second, each second: slowed a little, as a nature film would
        const val SEGMENTS = 14
        const val HANG = 34f           // how far a leaf can hang below the branch
        val BARK = Color(0xFF4A3B30); val BARK_LIT = Color(0xFF75604C)
    }
}
