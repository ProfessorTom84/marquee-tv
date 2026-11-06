package app.marquee.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.marquee.tv.weather.Sky
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/*
 * Seasonal and weather life layered over the sky, combined the way the weather really is:
 * a rainy autumn day gets rain and a few wet, fast leaves; a windy one sends them sideways.
 *
 *  - Autumn: real leaf shapes (maple and beech) tumbling in 3D at three depths, the nearest out of focus.
 *  - Spring days: a few drifting blossom petals.
 *  - Summer nights: fireflies low in the dark.
 *  - Storms: forked lightning behind the clouds, with the cloud deck flashing.
 * (Rain and snow are drawn in LiveSky.)
 */

private val leafColors = listOf(Color(0xFFC2551F), Color(0xFFD98E2B), Color(0xFF9E3A1E), Color(0xFFB9A13A), Color(0xFF7A2E1A), Color(0xFFCF6B2A))

private fun sprite(n: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(n.toFloat(), n.toFloat()), block)
    return bmp
}

private fun leafPath(maple: Boolean, s: Float): Path = Path().apply {
    val c = s / 2f
    if (maple) {
        // Five pointed lobes from a polar curve, with a short stem.
        val steps = 140
        for (i in 0..steps) {
            val t = i / steps.toFloat() * 2f * PI.toFloat()
            val lobes = abs(cos(2.5f * (t + PI.toFloat() / 2f)))
            val r = s * 0.42f * (0.5f + 0.5f * lobes.pow(0.55f)) * (if (sin(t) > 0.75f) 0.75f else 1f)
            val x = c + r * cos(t); val y = c - r * sin(t) * 0.95f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    } else {
        // Beech / birch: an ovate leaf with a pointed tip.
        moveTo(c, s * 0.06f)
        cubicTo(c + s * 0.34f, s * 0.24f, c + s * 0.3f, s * 0.7f, c, s * 0.9f)
        cubicTo(c - s * 0.3f, s * 0.7f, c - s * 0.34f, s * 0.24f, c, s * 0.06f)
        close()
    }
}

private fun leafSprite(color: Color, maple: Boolean, size: Int): ImageBitmap = sprite(size) {
    val s = size.toFloat()
    val p = leafPath(maple, s)
    val c = Offset(s / 2f, s / 2f)
    drawPath(p, Brush.radialGradient(listOf(lerpC(color, Color(0xFFFFE2A8), 0.18f), color, lerpC(color, Color.Black, 0.35f)), c, s * 0.5f))
    // Veins, faint.
    val vein = lerpC(color, Color.Black, 0.4f).copy(alpha = 0.55f)
    val sw = max(0.6f, s * 0.018f)
    if (maple) {
        listOf(90f, 18f, 162f, 234f + 30f, 306f - 30f).forEach { a ->
            val rad = a * PI.toFloat() / 180f
            drawLine(vein, c, Offset(c.x + cos(rad) * s * 0.33f, c.y - sin(rad) * s * 0.33f), sw)
        }
        drawLine(vein, c, Offset(c.x, s * 0.95f), sw * 1.4f) // stem
    } else {
        drawLine(vein, Offset(c.x, s * 0.1f), Offset(c.x, s * 0.97f), sw * 1.2f)
        for (k in 1..4) {
            val y = s * (0.22f + k * 0.13f)
            drawLine(vein, Offset(c.x, y), Offset(c.x + s * 0.16f, y - s * 0.08f), sw * 0.8f)
            drawLine(vein, Offset(c.x, y), Offset(c.x - s * 0.16f, y - s * 0.08f), sw * 0.8f)
        }
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

private fun petalSprite(size: Int, soft: Boolean): ImageBitmap = sprite(size) {
    val s = size.toFloat()
    val p = Path().apply {
        moveTo(s * 0.5f, s * 0.12f)
        cubicTo(s * 0.86f, s * 0.2f, s * 0.8f, s * 0.78f, s * 0.5f, s * 0.9f)
        cubicTo(s * 0.2f, s * 0.78f, s * 0.14f, s * 0.2f, s * 0.42f, s * 0.2f)
        close()
    }
    drawPath(p, Brush.radialGradient(listOf(Color(0xFFFFF4F6), Color(0xFFF6C3D1), Color(0xFFE79AB2)), Offset(s * 0.5f, s * 0.4f), s * 0.55f), alpha = if (soft) 0.9f else 1f)
}

/** Sprites are built once: sharp ones for far and middle depths, tiny ones that upscale soft for the near, out-of-focus layer. */
private object Sprites {
    val leaves: List<ImageBitmap> by lazy { leafColors.flatMapIndexed { i, c -> listOf(leafSprite(c, i % 2 == 0, 96)) } }
    val softLeaves: List<ImageBitmap> by lazy { leafColors.mapIndexed { i, c -> leafSprite(c, i % 2 == 0, 14) } }
    val petal: ImageBitmap by lazy { petalSprite(48, false) }
    val softPetal: ImageBitmap by lazy { petalSprite(10, true) }
}

internal class Flyer(
    val x: Float, val y: Float, val size: Float, val fall: Float, val sway: Float, val spin: Float,
    val flip: Float, val phase: Float, val kind: Int, val depth: Int, // 0 far, 1 mid, 2 near (soft)
)

class SeasonalLife internal constructor(private val flyers: List<Flyer>, private val mode: Int) {
    companion object {
        const val NONE = 0; const val LEAVES = 1; const val PETALS = 2; const val FIREFLIES = 3

        /** Decide what's alive in the sky from season, weather and time of day. */
        fun pick(season: Season, sky: Sky, night: Float): SeasonalLife {
            val r = Random(11)
            val leafFactor = when (sky) { Sky.CLEAR, Sky.PARTLY -> 1f; Sky.CLOUDY -> 0.85f; Sky.RAIN -> 0.6f; Sky.STORM -> 0.5f; Sky.FOG -> 0.5f; Sky.SNOW -> 0f } * (1f - 0.35f * night)
            return when {
                season == Season.AUTUMN && leafFactor > 0f -> {
                    val far = (12 * leafFactor).toInt(); val mid = (8 * leafFactor).toInt(); val near = if (leafFactor >= 0.6f) 2 else 1
                    SeasonalLife(
                        List(far) { Flyer(r.nextFloat(), r.nextFloat(), 15f + r.nextFloat() * 8f, 0.035f + r.nextFloat() * 0.02f, 18f, 40f + r.nextFloat() * 60f, 0.8f + r.nextFloat(), r.nextFloat() * 6.28f, r.nextInt(6), 0) } +
                            List(mid) { Flyer(r.nextFloat(), r.nextFloat(), 30f + r.nextFloat() * 12f, 0.055f + r.nextFloat() * 0.025f, 36f, 50f + r.nextFloat() * 70f, 1f + r.nextFloat(), r.nextFloat() * 6.28f, r.nextInt(6), 1) } +
                            List(near) { Flyer(r.nextFloat(), r.nextFloat(), 70f + r.nextFloat() * 30f, 0.10f + r.nextFloat() * 0.03f, 70f, 40f + r.nextFloat() * 40f, 0.7f + r.nextFloat() * 0.5f, r.nextFloat() * 6.28f, r.nextInt(6), 2) },
                        LEAVES,
                    )
                }
                season == Season.SPRING && night < 0.3f && (sky == Sky.CLEAR || sky == Sky.PARTLY || sky == Sky.CLOUDY) -> SeasonalLife(
                    List(10) { Flyer(r.nextFloat(), r.nextFloat(), 9f + r.nextFloat() * 6f, 0.025f + r.nextFloat() * 0.015f, 30f, 60f, 1.2f + r.nextFloat(), r.nextFloat() * 6.28f, 0, 0) } +
                        List(2) { Flyer(r.nextFloat(), r.nextFloat(), 34f + r.nextFloat() * 12f, 0.06f, 60f, 40f, 1f, r.nextFloat() * 6.28f, 0, 2) },
                    PETALS,
                )
                season == Season.SUMMER && night > 0.5f && (sky == Sky.CLEAR || sky == Sky.PARTLY) -> SeasonalLife(
                    List(14) { Flyer(r.nextFloat(), 0.55f + r.nextFloat() * 0.4f, 3f + r.nextFloat() * 3f, 0f, 30f, 0f, 0.4f + r.nextFloat() * 0.5f, r.nextFloat() * 6.28f, 0, r.nextInt(2)) },
                    FIREFLIES,
                )
                else -> SeasonalLife(emptyList(), NONE)
            }
        }
    }

    val active get() = mode != NONE

    fun draw(scope: DrawScope, time: Float, wind: Float, wet: Boolean, night: Float) = with(scope) {
        if (mode == NONE) return@with
        val w = size.width; val h = size.height; val sc = h / 1080f
        if (mode == FIREFLIES) {
            flyers.forEach { f ->
                val blink = ((sin(time * f.flip * 2f + f.phase) + 1f) / 2f).pow(3f)
                if (blink < 0.04f) return@forEach
                val x = (f.x * w + sin(time * 0.25f + f.phase) * 60f * sc + w) % w
                val y = f.y * h + cos(time * 0.33f + f.phase * 1.3f) * 26f * sc
                val r = 16f * sc
                val o = Offset(x, y)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE9F27A).copy(alpha = 0.55f * blink), Color(0x00E9F27A)), o, r), r, o)
                drawCircle(Color(0xFFFBFFD0).copy(alpha = 0.9f * blink), 1.6f * sc, o)
            }
            return@with
        }
        // Wet leaves are darker and fall faster; wind carries everything sideways.
        val dim = if (wet) ColorFilter.lighting(Color(0xFF9A9590), Color.Black) else if (night > 0.3f) ColorFilter.lighting(Color(0xFF7A7F94), Color.Black) else null
        val fallBoost = if (wet) 1.6f else 1f
        val drift = 0.012f + wind * 0.06f
        flyers.forEach { f ->
            val period = 1.25f
            val yN = ((f.y + time * f.fall * fallBoost) % period) - 0.12f
            val xN = (((f.x + time * drift * (0.6f + f.depth * 0.4f)) % 1.2f) + 1.2f) % 1.2f - 0.1f
            val x = xN * w + sin(time * 0.7f * f.flip + f.phase) * f.sway * sc
            val y = yN * h
            val s = f.size * sc
            val angle = (time * f.spin + f.phase * 57f) % 360f
            val flip = cos(time * f.flip * 1.9f + f.phase).let { if (abs(it) < 0.18f) 0.18f * (if (it < 0) -1 else 1) else it }
            // Lower on screen sits under the darker scrim: fade a little so it stays behind the UI visually.
            val fade = (1f - (yN.coerceIn(0f, 1f)) * 0.55f)
            val alpha = when (f.depth) { 0 -> 0.62f; 1 -> 0.85f; else -> 0.55f } * fade
            val img = if (mode == LEAVES) (if (f.depth == 2) Sprites.softLeaves[f.kind] else Sprites.leaves[f.kind]) else (if (f.depth == 2) Sprites.softPetal else Sprites.petal)
            withTransform({
                translate(x, y)
                rotate(angle, Offset.Zero)
                scale(flip, 1f, Offset.Zero)
            }) {
                drawImage(img, dstOffset = IntOffset((-s / 2).toInt(), (-s / 2).toInt()), dstSize = IntSize(s.toInt(), s.toInt()),
                    alpha = alpha, colorFilter = dim, filterQuality = FilterQuality.Low)
            }
        }
    }
}

/** A forked bolt from the cloud base down toward the horizon, rebuilt for each strike. */
internal fun boltPath(seed: Int, w: Float, h: Float): Pair<Path, List<Path>> {
    val r = Random(seed)
    val x0 = w * (0.18f + r.nextFloat() * 0.64f)
    val main = Path()
    val branches = mutableListOf<Path>()
    var x = x0; var y = h * 0.27f
    main.moveTo(x, y)
    val end = h * (0.56f + r.nextFloat() * 0.1f)
    val drift = (r.nextFloat() - 0.5f) * w * 0.1f
    while (y < end) {
        val step = h * (0.018f + r.nextFloat() * 0.03f)
        y += step
        x += (r.nextFloat() - 0.5f) * step * 1.6f + drift * step / end
        main.lineTo(x, y)
        if (r.nextFloat() < 0.16f) {
            val b = Path(); var bx = x; var by = y; b.moveTo(bx, by)
            val dir = if (r.nextBoolean()) 1f else -1f
            repeat(3 + r.nextInt(4)) {
                val st = h * (0.012f + r.nextFloat() * 0.02f)
                by += st; bx += dir * st * (0.5f + r.nextFloat()) + (r.nextFloat() - 0.5f) * st
                b.lineTo(bx, by)
            }
            branches += b
        }
    }
    return main to branches
}

internal fun DrawScope.drawBolt(bolt: Pair<Path, List<Path>>, k: Float) {
    val sc = size.height / 1080f
    val (main, branches) = bolt
    val glow = Color(0xFFBFD0FF)
    fun stroke(p: Path, wMul: Float) {
        drawPath(p, glow.copy(alpha = 0.10f * k), style = Stroke(16f * sc * wMul, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(p, glow.copy(alpha = 0.35f * k), style = Stroke(5f * sc * wMul, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(p, Color.White.copy(alpha = 0.9f * k), style = Stroke(1.8f * sc * wMul, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    stroke(main, 1f)
    branches.forEach { stroke(it, 0.55f) }
}

internal fun stormCycle(time: Float): Triple<Int, Float, Float> {
    // Strikes at irregular intervals: 7 to 15 seconds apart.
    val slot = floor(time / 11f).toInt()
    val offset = (Random(slot * 7919).nextFloat() * 4f) - 2f
    val t = time - slot * 11f - 2f - offset
    val k = when {
        t < 0f -> 0f
        t < 0.07f -> 1f
        t < 0.14f -> 0.2f
        t < 0.22f -> 0.85f
        t < 0.55f -> (0.55f - t) / 0.33f * 0.35f
        else -> 0f
    }
    return Triple(slot, k, t)
}
