package app.marquee.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.marquee.tv.weather.Sky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** Meteorological seasons, flipped for the southern hemisphere. */
fun seasonFor(date: LocalDate = LocalDate.now(), latitude: Double = Double.NaN): Season {
    val north = latitude.isNaN() || latitude >= 0
    val s = when (date.month) {
        Month.MARCH, Month.APRIL, Month.MAY -> Season.SPRING
        Month.JUNE, Month.JULY, Month.AUGUST -> Season.SUMMER
        Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER -> Season.AUTUMN
        else -> Season.WINTER
    }
    if (north) return s
    return when (s) { Season.SPRING -> Season.AUTUMN; Season.SUMMER -> Season.WINTER; Season.AUTUMN -> Season.SPRING; Season.WINTER -> Season.SUMMER }
}

// =====================================================================================
//  The sky's look at a moment: colours from the real sun position, softened by weather.
// =====================================================================================

private class Palette(
    val zenith: Color, val upper: Color, val lower: Color, val horizon: Color,
    val cloudLit: Color, val cloudShadow: Color, val sun: Color,
)

private fun p(z: Long, u: Long, l: Long, h: Long, lit: Long, sh: Long, sun: Long) =
    Palette(Color(z), Color(u), Color(l), Color(h), Color(lit), Color(sh), Color(sun))

/** Keyframes by the sun's true elevation in degrees: civil twilight ends at -6°, nautical at -12°. */
private val keyframes: List<Pair<Float, Palette>> = listOf(
    -14f to p(0xFF03050F, 0xFF070C20, 0xFF111A38, 0xFF1D2648, 0xFF3A4258, 0xFF10141F, 0xFFFFF4E0), // night
    -8f to p(0xFF0A1030, 0xFF16224E, 0xFF2C3A6E, 0xFF4E5A8A, 0xFF5A628C, 0xFF20253F, 0xFFFFF4E0), // blue hour
    -4f to p(0xFF141A42, 0xFF2E2D62, 0xFF7E4F7C, 0xFFD9787A, 0xFFC98FA0, 0xFF3E3558, 0xFFFF9E58), // twilight
    0f to p(0xFF1C2556, 0xFF4A3F78, 0xFFC4627A, 0xFFFF9A55, 0xFFFFB98A, 0xFF6A4E6E, 0xFFFF9E58),   // sunset
    3f to p(0xFF22396E, 0xFF49609A, 0xFFC0927E, 0xFFF6B56E, 0xFFFFD9A8, 0xFF7C6C80, 0xFFFFC67A),  // golden hour
    9f to p(0xFF1D4683, 0xFF3A6BAA, 0xFF8AA9C9, 0xFFE3CFA8, 0xFFFFF3DE, 0xFF8F96A8, 0xFFFFEFD2),  // late
    20f to p(0xFF1A4A8C, 0xFF2F6DB5, 0xFF6FA3D6, 0xFFB9D3EA, 0xFFFFFFFF, 0xFF94A3B8, 0xFFFFF8E8), // day
)

private fun paletteAt(m: Float): Palette {
    val k = keyframes
    if (m <= k.first().first) return k.first().second
    if (m >= k.last().first) return k.last().second
    val i = k.indexOfLast { it.first <= m }
    val (m0, a) = k[i]; val (m1, b) = k[i + 1]
    val t = ((m - m0) / (m1 - m0)).let { it * it * (3 - 2 * it) }
    return Palette(
        lerp(a.zenith, b.zenith, t), lerp(a.upper, b.upper, t), lerp(a.lower, b.lower, t), lerp(a.horizon, b.horizon, t),
        lerp(a.cloudLit, b.cloudLit, t), lerp(a.cloudShadow, b.cloudShadow, t), lerp(a.sun, b.sun, t),
    )
}

/** Pull a colour toward a neutral grey of similar brightness: overcast skies lose their colour. */
private fun grey(c: Color, amount: Float, brightness: Float = 1f): Color {
    val l = (0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue) * brightness
    val g = Color(min(1f, l * 0.98f), min(1f, l * 1.0f), min(1f, l * 1.05f)) // a whisper of cool
    return lerp(c, g, amount)
}

private class Look(
    val pal: Palette,
    val elev: Float,          // the sun's elevation, degrees
    val sunX: Float,          // where the sun sits on screen (fractions), from its real azimuth and elevation
    val sunY: Float,
    val moonArc: Float,       // 0..1 across the night
    val day: Boolean,
    val sky: Sky,
    val night: Float,         // 0 day … 1 deep night (sun 12° below)
    val moonPhase: Float,     // 0 new … 0.5 full … 1 new
)

private fun lookFor(
    sky: Sky, isDay: Boolean?, sunrise: LocalTime, sunset: LocalTime, now: LocalTime, date: LocalDate, season: Season,
    latitude: Double, longitude: Double,
): Look {
    val n = now.toSecondOfDay() / 60f
    val r = sunrise.toSecondOfDay() / 60f
    val s = sunset.toSecondOfDay() / 60f
    val nightArc = if (n in r..s) 0.5f else { val len = 1440f - (s - r); ((((n - s) + 1440f) % 1440f) / len).coerceIn(0f, 1f) }
    val elev: Float; val sunX: Float
    if (!latitude.isNaN() && !longitude.isNaN()) {
        // The real sun for your coordinates (NOAA solar position equations).
        val p = app.marquee.tv.weather.Solar.position(latitude, longitude, java.time.ZonedDateTime.of(date, now, java.time.ZoneId.systemDefault()))
        elev = p.elevation.toFloat()
        // Facing the equator: in the north the sun rises on the left (east) and sets on the right (west).
        val sa = sin(Math.toRadians(p.azimuth)).toFloat()
        sunX = if (latitude >= 0) 0.5f - 0.4f * sa else 0.5f + 0.4f * sa
    } else {
        // No coordinates: estimate from today's sunrise and sunset times.
        val m = if (n in r..s) min(n - r, s - n) else -min(((r - n) + 1440f) % 1440f, ((n - s) + 1440f) % 1440f)
        elev = (m / 5f).coerceIn(-20f, 45f)
        val arc = if (n in r..s) ((n - r) / (s - r)).coerceIn(0f, 1f) else if (n > s) 1f else 0f
        sunX = 0.1f + 0.8f * arc
    }
    val sunY = if (elev >= 0f) 0.4f - 0.3f * (elev / 50f).coerceIn(0f, 1f) else 0.4f + (-elev) * 0.012f
    val day = isDay ?: (elev > -0.8f)
    var pal = paletteAt(elev)
    // Weather drains the colour and sets the brightness.
    val (g, b) = when (sky) {
        Sky.CLEAR -> 0f to 1f
        Sky.PARTLY -> 0.12f to 1f
        Sky.CLOUDY -> 0.72f to 0.92f
        Sky.FOG -> 0.82f to 1.08f
        Sky.SNOW -> 0.7f to 1.1f
        Sky.RAIN -> 0.82f to 0.72f
        Sky.STORM -> 0.86f to 0.5f
    }
    if (g > 0f) pal = Palette(
        grey(pal.zenith, g, b), grey(pal.upper, g, b), grey(pal.lower, g, b), grey(pal.horizon, g, b * 1.04f),
        grey(pal.cloudLit, g * 0.9f, b), grey(pal.cloudShadow, g, b * 0.92f), pal.sun,
    )
    // A faint seasonal cast near the horizon.
    val tint = when (season) { Season.AUTUMN -> Color(0xFFE6A060); Season.WINTER -> Color(0xFFC6D6EE); Season.SPRING -> Color(0xFFEFC4D2); Season.SUMMER -> Color(0xFFFFDFA0) }
    pal = Palette(pal.zenith, pal.upper, pal.lower, lerp(pal.horizon, tint, 0.06f), pal.cloudLit, pal.cloudShadow, pal.sun)
    val night = ((-elev - 2f) / 10f).coerceIn(0f, 1f)
    return Look(pal, elev, sunX, sunY, nightArc, day, sky, night, moonPhase(date))
}

/** Days into the synodic month, as 0..1 (0 new, 0.5 full). */
private fun moonPhase(date: LocalDate): Float {
    val ref = LocalDate.of(2000, 1, 6).atTime(18, 14).toEpochSecond(ZoneOffset.UTC)
    val t = date.atTime(21, 0).toEpochSecond(ZoneOffset.UTC)
    val days = (t - ref) / 86400.0
    return (((days % 29.530588853) + 29.530588853) % 29.530588853 / 29.530588853).toFloat()
}

// =====================================================================================
//  Tileable gradient noise, used to grow soft, lit clouds into small textures.
// =====================================================================================

private class Noise(private val seed: Int) {
    private fun hash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }
    private val gx = FloatArray(16) { cos(it * PI.toFloat() / 8f) }
    private val gy = FloatArray(16) { sin(it * PI.toFloat() / 8f) }

    /** Perlin noise in roughly -0.7..0.7, periodic in x with [period] cells. */
    fun perlin(x: Float, y: Float, period: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        fun dot(ix: Int, iy: Int, dx: Float, dy: Float): Float {
            val h = hash(Math.floorMod(ix, period), iy) and 15
            return gx[h] * dx + gy[h] * dy
        }
        val u = xf * xf * xf * (xf * (xf * 6 - 15) + 10)
        val v = yf * yf * yf * (yf * (yf * 6 - 15) + 10)
        val a = dot(xi, yi, xf, yf); val b = dot(xi + 1, yi, xf - 1, yf)
        val c = dot(xi, yi + 1, xf, yf - 1); val d = dot(xi + 1, yi + 1, xf - 1, yf - 1)
        val ab = a + (b - a) * u; val cd = c + (d - c) * u
        return ab + (cd - ab) * v
    }

    /** Fractal noise in about 0..1. */
    fun fbm(x: Float, y: Float, period: Int, octaves: Int, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var norm = 0f; var f = 1
        for (o in 0 until octaves) {
            sum += amp * perlin(x * f, y * f, period * f); norm += amp
            amp *= gain; f *= 2
        }
        return (sum / norm) * 0.85f + 0.5f
    }
}

private enum class CloudKind { CIRRUS, CUMULUS, STRATOCUMULUS, STRATUS, FOG }

private class CloudSpec(
    val kind: CloudKind,
    val top: Float, val bottom: Float,   // fraction of the screen height
    val coverage: Float,                 // 0..1
    val opacity: Float,
    val speed: Float,                    // screen widths per minute
    val cells: Int,                      // noise cells across one texture (two screen widths)
    val seed: Int,
)

private class CloudLayer(val image: ImageBitmap, val top: Float, val height: Float, val period: Float, val speed: Float)

private fun specsFor(sky: Sky, seed: Int): List<CloudSpec> = when (sky) {
    Sky.CLEAR -> listOf(CloudSpec(CloudKind.CIRRUS, 0f, 0.42f, 0.34f, 0.32f, 0.06f, 5, seed))
    Sky.PARTLY -> listOf(
        CloudSpec(CloudKind.CIRRUS, 0f, 0.35f, 0.3f, 0.22f, 0.05f, 5, seed + 1),
        CloudSpec(CloudKind.STRATOCUMULUS, 0.0f, 0.62f, 0.44f, 0.95f, 0.11f, 11, seed),
    )
    Sky.CLOUDY, Sky.SNOW -> listOf(
        CloudSpec(CloudKind.STRATUS, 0f, 0.78f, 0.86f, 0.95f, 0.06f, 6, seed),
        CloudSpec(CloudKind.STRATOCUMULUS, 0.04f, 0.7f, 0.55f, 0.7f, 0.12f, 8, seed + 7),
    )
    Sky.RAIN, Sky.STORM -> listOf(
        CloudSpec(CloudKind.STRATUS, 0f, 0.82f, 0.95f, 1f, 0.08f, 6, seed),
        CloudSpec(CloudKind.STRATOCUMULUS, 0.04f, 0.72f, 0.62f, 0.75f, 0.16f, 9, seed + 7),
    )
    Sky.FOG -> listOf(
        CloudSpec(CloudKind.STRATUS, 0f, 0.55f, 0.7f, 0.55f, 0.03f, 5, seed),
        CloudSpec(CloudKind.FOG, 0.3f, 1f, 0.75f, 0.7f, 0.05f, 4, seed + 3),
    )
}

/**
 * Grows one drifting cloud layer into a small texture (a third of screen resolution: clouds are soft,
 * so it upscales cleanly). Light comes from above: the more cloud above a point, the darker it gets,
 * which gives bright tops and heavy bases. The screen's scrim is pre-applied (SrcAtop), so the layer
 * looks right on top of the baked sky without an extra full-screen scrim pass every frame.
 */
private fun buildLayer(spec: CloudSpec, pal: Palette, screen: IntSize, density: androidx.compose.ui.unit.Density, overlay: Brush?, lightDir: Offset): CloudLayer {
    val period = screen.width * 2f
    val layerTop = spec.top * screen.height
    val layerH = (spec.bottom - spec.top) * screen.height
    // Cumulus has crisp, billowy edges, so it gets a finer texture; the soft kinds upscale cleanly from a third.
    // Rendered small and upscaled on purpose: a slightly out-of-focus sky reads as calm and photographic
    // behind the UI, rather than as an illustration.
    val div = when (spec.kind) { CloudKind.CIRRUS, CloudKind.STRATOCUMULUS -> 4f; CloudKind.CUMULUS -> 5f; else -> 6f }
    val tw = max(64, (period / div).toInt())
    val th = max(24, (layerH / div).toInt())
    val noise = Noise(spec.seed)
    val warp = Noise(spec.seed * 31 + 5)
    val d = FloatArray(tw * th)
    // Cells: stretched sideways for cirrus and stratus.
    val cx = spec.cells.toFloat()
    val aspect = (layerH / period)
    val stretch = when (spec.kind) { CloudKind.CIRRUS -> 5f; CloudKind.STRATUS -> 2.2f; CloudKind.FOG -> 3f; CloudKind.CUMULUS -> 1.15f; CloudKind.STRATOCUMULUS -> 1.7f }
    val cy = cx * aspect * stretch
    val octaves = if (spec.kind == CloudKind.CIRRUS) 5 else 6
    val threshold = 1f - spec.coverage
    val soft = when (spec.kind) { CloudKind.CUMULUS -> 0.075f; CloudKind.CIRRUS -> 0.22f; CloudKind.STRATUS -> 0.28f; CloudKind.FOG -> 0.4f; CloudKind.STRATOCUMULUS -> 0.2f }
    val thickness = FloatArray(tw * th)
    if (spec.kind == CloudKind.CUMULUS) cumulusField(d, thickness, tw, th, noise, spec, aspect = layerH / period)
    else {
    for (j in 0 until th) {
            val v = j / th.toFloat()
            // Fade the layer in and out vertically, cumulus sitting a little lower than its band's top.
            val band = when (spec.kind) {
                CloudKind.CUMULUS -> smooth(0f, 0.25f, v) * (1f - smooth(0.62f, 1f, v))
                CloudKind.FOG -> smooth(0f, 0.45f, v) * (1f - smooth(0.85f, 1f, v) * 0.4f)
                CloudKind.STRATUS -> 1f - smooth(0.55f, 1f, v)
                CloudKind.STRATOCUMULUS -> smooth(0f, 0.2f, v) * (1f - smooth(0.5f, 1f, v))
                else -> smooth(0f, 0.12f, v) * (1f - smooth(0.55f, 1f, v))
            }
            for (i in 0 until tw) {
                val x = i / tw.toFloat() * cx
                val y = v * cy
                val q = if (spec.kind == CloudKind.CUMULUS || spec.kind == CloudKind.FOG || spec.kind == CloudKind.STRATOCUMULUS) warp.fbm(x * 0.5f, y * 0.5f, max(1, spec.cells / 2), 2) - 0.5f else 0f
                var n = noise.fbm(x + q * 0.7f, y + q * 0.5f, spec.cells, octaves, if (spec.kind == CloudKind.CUMULUS) 0.56f else 0.5f)
                if (spec.kind == CloudKind.CIRRUS) n = n * 0.75f + noise.perlin(x * 7f, y * 0.8f, spec.cells * 7) * 0.25f + 0.12f
                val dens = ((n - threshold) / soft).coerceIn(0f, 1f) * band
                d[j * tw + i] = dens
            }
        }
    }
    if (spec.kind != CloudKind.CUMULUS) for (k in d.indices) thickness[k] = d[k] * 0.5f
    // Light: march a few steps toward the sun (or moon) through the cloud's thickness. Billows facing the light
    // come out bright, the far side and the base fall into shadow. Overcast light comes from straight above.
    val absorb = when (spec.kind) { CloudKind.CUMULUS -> 1.5f; CloudKind.STRATUS -> 1.4f; CloudKind.FOG -> 0.6f; CloudKind.CIRRUS -> 0.4f; CloudKind.STRATOCUMULUS -> 1.1f }
    val steps = 8
    val stepLen = max(1f, th * 0.045f)
    val lx = lightDir.x * stepLen; val ly = lightDir.y * stepLen
    val px = IntArray(tw * th)
    val lit = pal.cloudLit; val sh = pal.cloudShadow
    // The screen's scrim, sampled per row, so the layer looks right on top of the baked (already darkened) sky:
    // the cloud's colour becomes "scrim over cloud", its alpha unchanged.
    val scrim: IntArray? = overlay?.let { ov ->
        val strip = ImageBitmap(1, screen.height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(strip), Size(1f, screen.height.toFloat())) { drawRect(ov) }
        IntArray(screen.height).also { strip.readPixels(it, 0, 0, 1, screen.height) }
    }
    for (j in 0 until th) {
        val sc = scrim?.let { Color(it[(layerTop + (j + 0.5f) / th * layerH).toInt().coerceIn(0, it.size - 1)]) }
        for (i in 0 until tw) {
            val k = j * tw + i
            val dd = d[k]
            if (dd <= 0.002f) { px[k] = 0; continue }
            var depth = 0f
            var x = i.toFloat(); var y = j.toFloat()
            for (s in 0 until steps) {
                x += lx; y += ly
                // Past the edge, assume the layer carries on as it was (no stepped banding near the top).
                val yi = y.toInt().coerceIn(0, th - 1)
                depth += thickness[yi * tw + Math.floorMod(x.toInt(), tw)]
            }
            var light = exp(-absorb * depth * 0.35f)
            // Thin edges let light through: a soft silver lining.
            light = (light + (1f - dd) * 0.08f).coerceIn(0f, 1f)
            var c = lerp(sh, lit, light.pow(0.8f))
            if (sc != null) c = lerp(c, sc.copy(alpha = 1f), sc.alpha)
            val a = (dd.pow(0.85f) * spec.opacity).coerceIn(0f, 1f)
            px[k] = Color(c.red, c.green, c.blue, a).toArgb()
        }
    }
    val img = Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888).asImageBitmap()
    img.prepareToDraw()
    return CloudLayer(img, layerTop, layerH, period, spec.speed * screen.width / 60f)
}

/**
 * Fair-weather cumulus: each cloud is a cluster of soft blobs piled into a dome over a flat base,
 * then its edges are eroded with noise so they billow. Clouds lower in the band are smaller and fainter,
 * as if further away. Wraps horizontally.
 */
private fun cumulusField(d: FloatArray, t: FloatArray, tw: Int, th: Int, noise: Noise, spec: CloudSpec, aspect: Float) {
    val rnd = Random(spec.seed)
    val count = (spec.cells * (0.9f + spec.coverage * 1.4f)).toInt().coerceAtLeast(4)
    val bases = FloatArray(count)
    for (c in 0 until count) {
        val depth = rnd.nextFloat()                      // 0 near (high, big) … 1 far (low, small)
        val base = th * (0.42f + 0.45f * depth)
        val scale = 1f - 0.55f * depth
        val cw = tw * (0.035f + 0.055f * rnd.nextFloat()) * scale * (0.7f + spec.coverage)
        val ch = cw * (0.42f + 0.25f * rnd.nextFloat()) / aspect * (th / tw.toFloat()) * aspect * (tw / th.toFloat())
        val cx = rnd.nextFloat() * tw
        bases[c] = base
        val strength = 1f - 0.35f * depth
        val blobs = 7 + rnd.nextInt(6)
        repeat(blobs) {
            val u = rnd.nextFloat() * 2f - 1f
            val bx = cx + u * cw * 0.5f
            val dome = 1f - u * u
            val r = cw * (0.13f + 0.13f * rnd.nextFloat()) * (0.6f + 0.6f * dome)
            val by = base - ch * (0.15f + 0.75f * rnd.nextFloat()) * dome
            val x0 = (bx - r * 2.2f).toInt(); val x1 = (bx + r * 2.2f).toInt()
            val y0 = max(0, (by - r * 2.2f).toInt()); val y1 = min(th - 1, (base + r * 0.4f).toInt())
            val inv = 1f / (r * r)
            for (yy in y0..y1) {
                // Flat bottoms: fade out just below the base line.
                val under = smooth(base + r * 0.35f, base - r * 0.15f, yy.toFloat())
                if (under <= 0f) continue
                val dy = yy - by
                for (xx in x0..x1) {
                    val dx = xx - bx
                    val g = exp(-(dx * dx + dy * dy) * inv * 1.1f) * under * strength
                    if (g < 0.003f) continue
                    val i = Math.floorMod(xx, tw)
                    d[yy * tw + i] += g
                }
            }
        }
    }
    // Erode with noise for billowy, natural edges, then shape into density.
    val cx = spec.cells.toFloat()
    val cy = cx * th / tw.toFloat()
    for (j in 0 until th) for (i in 0 until tw) {
        val k = j * tw + i
        val raw = d[k]
        if (raw < 0.02f) { d[k] = 0f; t[k] = 0f; continue }
        // Billows: big lumps plus fine fluff ("1 - |noise|" makes rounded cauliflower shapes).
        val fx = i / tw.toFloat() * cx * 3f; val fy = j / th.toFloat() * cy * 3f
        val lumps = noise.fbm(fx, fy, spec.cells * 3, 3, 0.5f) - 0.5f
        val fluff = (1f - abs(noise.perlin(fx * 4f, fy * 4f, spec.cells * 12)) * 2f) - 0.55f
        val thick = raw + lumps * 0.8f + fluff * 0.12f - 0.4f
        t[k] = max(0f, thick)
        d[k] = smooth(0f, 0.32f, thick)
    }
}

/** Direction toward the light (texture space, y down): the sun or moon's place in the sky, or overhead when overcast. */
private fun lightDirection(look: Look): Offset {
    if (look.sky != Sky.CLEAR && look.sky != Sky.PARTLY) return Offset(0f, -1f)
    val moon = look.night > 0.5f
    val sx = if (moon) 0.62f + 0.3f * look.moonArc else look.sunX
    val sy = if (moon) 0.3f - 0.2f * sin(PI.toFloat() * look.moonArc) else look.sunY
    // From the middle of the cloud band toward the orb; the sky is wider than tall, so weight x down.
    var dx = (sx - 0.5f) * 0.6f; var dy = sy - 0.3f
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    dx /= len; dy /= len
    return Offset(dx, dy)
}

private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

// =====================================================================================
//  The baked sky: gradient, sun or moon, stars, scrim and a fine grain (which also kills banding).
// =====================================================================================

/** Tests and previews bake the sky on the spot instead of in the background. */
object SkyRendering { @Volatile var synchronous = false; @Volatile var stillTime = 37f }

private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val c: Color)

private fun starsFor(seed: Int): List<Star> {
    val rnd = Random(seed)
    val tints = listOf(Color.White, Color(0xFFD6E4FF), Color(0xFFFFEBD2), Color.White)
    val field = List(240) {
        val b = rnd.nextFloat().pow(3f)
        Star(rnd.nextFloat(), rnd.nextFloat().pow(1.5f) * 0.75f, 0.5f + 1.3f * b, 0.22f + 0.78f * b, tints[rnd.nextInt(tints.size)])
    }
    // A denser drift of faint stars along a diagonal band: the suggestion of the Milky Way.
    val band = List(220) {
        val t = rnd.nextFloat()
        val off = (rnd.nextFloat() + rnd.nextFloat() - 1f) * 0.09f
        Star(0.15f + t * 0.8f, (0.02f + t * 0.5f + off).coerceIn(0f, 0.8f), 0.45f + rnd.nextFloat() * 0.4f, 0.12f + rnd.nextFloat() * 0.3f, tints[rnd.nextInt(tints.size)])
    }
    return field + band
}

private fun DrawScope.paintSky(look: Look, stars: List<Star>, overlay: Brush?, grainAlpha: Float, orbs: Boolean = true) {
    val w = size.width; val h = size.height
    val pal = look.pal
    drawRect(Brush.verticalGradient(0f to pal.zenith, 0.42f to pal.upper, 0.78f to pal.lower, 1f to pal.horizon))
    val clearish = look.sky == Sky.CLEAR || look.sky == Sky.PARTLY

    // The glow on the horizon under a low sun, wide and elliptical.
    val sunX = w * look.sunX
    val low = (1f - abs(look.elev) / 10f).coerceIn(0f, 1f)
    if (low > 0f && look.sky != Sky.STORM) {
        val c = lerp(pal.horizon, pal.sun, 0.5f)
        withTransform({ scale(2.4f, 1f, pivot = Offset(sunX, h)) }) {
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f * low * if (clearish) 1f else 0.4f), Color.Transparent), Offset(sunX, h), h * 0.75f), h * 0.75f, Offset(sunX, h))
        }
    }

    // The sun's wide atmospheric bloom sits under the scrim (so it never washes out the UI)…
    val orbY = h * look.sunY
    val veil = when (look.sky) { Sky.CLEAR -> 1f; Sky.PARTLY -> 0.9f; Sky.FOG -> 0.45f; Sky.CLOUDY, Sky.SNOW -> 0.32f; else -> 0.12f }
    if (look.elev > -1.5f) {
        val o = Offset(sunX, orbY); val sc = pal.sun
        drawCircle(Brush.radialGradient(0f to sc.copy(alpha = 0.34f * veil), 0.35f to sc.copy(alpha = 0.12f * veil), 1f to Color.Transparent, center = o, radius = h * 0.95f), h * 0.95f, o)
    }
    // …while the stars, the sun's disc and the moon go over it, so they stay crisp and bright.
    overlay?.let { drawRect(it) }

    // Stars and the Milky Way, fading toward the horizon and with any cloud.
    val starVis = look.night * when (look.sky) { Sky.CLEAR -> 1f; Sky.PARTLY -> 0.85f; else -> 0f }
    if (starVis > 0.01f) {
        val mc = Offset(w * 0.55f, h * 0.26f)
        withTransform({ rotate(-22f, mc); scale(1f, 0.16f, mc) }) {
            drawCircle(Brush.radialGradient(0f to Color(0x1FC8D6FF), 0.5f to Color(0x0DC8D6FF), 1f to Color.Transparent, center = mc, radius = w * 0.5f), w * 0.5f, mc, alpha = starVis)
        }
        val scale = h / 1080f
        stars.forEach { s ->
            val fade = (1f - s.y / 0.8f).coerceIn(0f, 1f)
            val a = s.a * fade * starVis
            if (a < 0.02f) return@forEach
            val o = Offset(s.x * w, s.y * h)
            if (s.r > 1.5f) drawCircle(Brush.radialGradient(listOf(s.c.copy(alpha = a * 0.35f), Color.Transparent), o, 5f * scale), 5f * scale, o)
            drawCircle(s.c.copy(alpha = a), max(0.6f, s.r * scale * 1.2f), o)
        }
    }

    if (!orbs) {
        // Behind the UI there's no disc to get cut in half by a card: only the light. A clear night gets a soft,
        // cool moonlight spilling from beyond the top-right corner, brighter near full moon.
        if (look.night > 0.25f && look.elev <= -1.5f && look.sky != Sky.STORM && look.sky != Sky.RAIN) {
            val illum = (1 - cos(2 * PI * look.moonPhase)).toFloat() / 2f
            val c = Offset(w * 0.95f, -h * 0.18f)
            val a = (0.05f + 0.09f * illum) * (if (clearish) 1f else 0.5f)
            drawCircle(Brush.radialGradient(0f to Color(0xFFC9D6F5).copy(alpha = a), 0.45f to Color(0xFFC9D6F5).copy(alpha = a * 0.35f), 1f to Color.Transparent, center = c, radius = h * 1.15f), h * 1.15f, c)
        }
    } else
    // The sun rides the open upper sky (the lower part sits under the UI), sinking out of view after sunset.
    if (look.elev > -1.5f) {
        // A warm inner glow and a bright soft-edged disc.
        val o = Offset(sunX, orbY)
        val sc = pal.sun
        drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.75f * veil), 0.4f to sc.copy(alpha = 0.28f * veil), 1f to Color.Transparent, center = o, radius = h * 0.17f), h * 0.17f, o)
        if (clearish) {
            val r = h * 0.032f
            val warm = lerp(Color.White, sc, 0.25f + 0.6f * low)
            drawCircle(Brush.radialGradient(0f to lerp(Color.White, sc, 0.35f * low), 0.72f to warm, 1f to sc.copy(alpha = 0f), center = o, radius = r), r, o)
        }
    } else if (look.night > 0.25f && look.sky != Sky.STORM && look.sky != Sky.RAIN) {
        // The moon keeps to the open right-hand sky, clear of headlines and the clock.
        val mx = w * (0.62f + 0.3f * look.moonArc)
        val my = h * (0.3f - 0.2f * sin(PI.toFloat() * look.moonArc))
        drawMoon(Offset(mx, my), h * 0.03f, look.moonPhase, if (clearish) 1f else 0.35f, pal)
    }

    if (grainAlpha > 0f) drawRect(grainBrush(), alpha = grainAlpha)
}

private fun DrawScope.drawMoon(c: Offset, r: Float, phase: Float, vis: Float, pal: Palette) {
    val illum = (1 - cos(2 * PI * phase)).toFloat() / 2f
    drawCircle(Brush.radialGradient(0f to Color(0xFFE8ECF8).copy(alpha = 0.22f * vis * (0.35f + 0.65f * illum)), 1f to Color.Transparent, center = c, radius = r * 9f), r * 9f, c)
    // Earthshine on the dark part, then the lit part bounded by the terminator.
    drawCircle(lerp(pal.zenith, Color(0xFF8E96AE), 0.18f).copy(alpha = 0.9f * vis), r, c)
    val k = cos(2 * PI * phase).toFloat()
    val waxing = phase < 0.5f
    val lit = Path().apply {
        val steps = 36
        for (i in 0..steps) {
            val y = -r + 2 * r * i / steps
            val limb = sqrt(max(0f, r * r - y * y))
            val x = if (waxing) limb else -limb
            if (i == 0) moveTo(c.x + x, c.y + y) else lineTo(c.x + x, c.y + y)
        }
        for (i in steps downTo 0) {
            val y = -r + 2 * r * i / steps
            val limb = sqrt(max(0f, r * r - y * y))
            val x = if (waxing) k * limb else -k * limb
            lineTo(c.x + x, c.y + y)
        }
        close()
    }
    drawPath(lit, Color(0xFFF3F0E6).copy(alpha = vis))
    clipPath(lit) {
        // A few soft maria.
        listOf(Triple(-0.3f, -0.25f, 0.38f), Triple(0.25f, -0.1f, 0.3f), Triple(0.05f, 0.35f, 0.28f), Triple(-0.15f, 0.15f, 0.2f)).forEach { (dx, dy, s) ->
            val o = Offset(c.x + dx * r, c.y + dy * r)
            drawCircle(Brush.radialGradient(listOf(Color(0x24505A6E), Color.Transparent), o, r * s * 1.4f), r * s * 1.4f, o, alpha = vis)
        }
    }
}

// =====================================================================================
//  Moving parts: rain and snow with depth, distant lightning, twinkling stars.
// =====================================================================================

private class Drop(val x: Float, val y: Float, val speed: Float, val len: Float, val width: Float, val alpha: Float, val phase: Float)

private fun softDot(): ImageBitmap {
    val n = 64
    val px = IntArray(n * n) { i ->
        val x = (i % n - n / 2f + 0.5f) / (n / 2f); val y = (i / n - n / 2f + 0.5f) / (n / 2f)
        val d = sqrt(x * x + y * y)
        val a = (1f - smooth(0.15f, 1f, d)).coerceIn(0f, 1f)
        Color(1f, 1f, 1f, a).toArgb()
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val dotSprite by lazy { softDot() }

/**
 * A premium, quiet sky that follows the real weather and the real sun: colour from the sun's height,
 * soft lit clouds drifting with the wind, rain and snow with depth, stars and a moon in its real phase.
 * Everything static is painted once into a bitmap off the main thread; each frame only moves
 * a couple of cloud textures and the precipitation.
 */
@Composable
fun LiveSky(
    sky: Sky?,
    isDay: Boolean?,
    modifier: Modifier = Modifier,
    windKph: Double = 0.0,
    sunrise: LocalTime? = null,
    sunset: LocalTime? = null,
    latitude: Double = Double.NaN,
    longitude: Double = Double.NaN,
    date: LocalDate = LocalDate.now(),
    now: LocalTime = LocalTime.now(),
    @Suppress("UNUSED_PARAMETER") scene: SceneChoice? = null,
    overlay: Brush? = null,      // a screen's darkening scrim (vertical), baked in so it costs nothing per frame
    grain: Boolean = false,
    @Suppress("UNUSED_PARAMETER") landscape: Boolean = false,
    animate: Boolean = true,     // false: a still frame (the weather card)
    sideScrim: Brush? = null,    // a horizontal scrim drawn above the clouds (Cinematic's text side)
    orbs: Boolean = true,        // draw the sun and moon discs (off behind UI, where cards would cut them in half)
    fadeMs: Int = 4000,          // how long each step of the sky's slow change cross-fades
    stepMinutes: Int = 2,        // how often (in sky time) the light is re-painted
) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    // Size known during composition, so the first frame is already the finished sky.
    val px = IntSize(constraints.maxWidth.coerceAtMost(4096), constraints.maxHeight.coerceAtMost(4096))
    SkyContent(sky, px, isDay, windKph, sunrise, sunset, latitude, longitude, date, now, overlay, grain, animate, sideScrim, fadeMs, stepMinutes, orbs)
}

@Composable
private fun SkyContent(
    sky: Sky?, px: IntSize, isDay: Boolean?, windKph: Double, sunrise: LocalTime?, sunset: LocalTime?, latitude: Double, longitude: Double,
    date: LocalDate, now: LocalTime, overlay: Brush?, grain: Boolean, animate: Boolean, sideScrim: Brush?, fadeMs: Int, stepMinutes: Int,
    orbs: Boolean = true,
) {
    val weather = sky ?: Sky.CLEAR
    val season = remember(date, latitude) { seasonFor(date, latitude) }
    val bucket = now.toSecondOfDay() / (60 * stepMinutes.coerceAtLeast(1))
    val look = remember(weather, isDay, sunrise, sunset, bucket, date, season, latitude, longitude) {
        lookFor(weather, isDay, sunrise ?: LocalTime.of(6, 45), sunset ?: LocalTime.of(18, 15), now, date, season, latitude, longitude)
    }
    val density = LocalDensity.current
    val inspection = LocalInspectionMode.current || SkyRendering.synchronous
    val stars = remember(date) { starsFor(date.dayOfYear) }
    val seed = date.dayOfYear * 13 + weather.ordinal * 101

    fun bakeSky(): ImageBitmap? = if (px == IntSize.Zero) null else ImageBitmap(px.width, px.height).also { bmp ->
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(px.width.toFloat(), px.height.toFloat())) {
            paintSky(look, stars, overlay, if (grain) 0.045f else 0.025f, orbs)
        }
        bmp.prepareToDraw()
    }
    fun bakeClouds(): List<CloudLayer> = if (px == IntSize.Zero) emptyList()
        else specsFor(weather, seed).map { buildLayer(it, look.pal, px, density, overlay, lightDirection(look)) }

    // The light is re-painted every couple of minutes (off the main thread) and each new painting cross-fades
    // over the last, so sunset and dusk change continuously rather than in steps.
    class Frame(val base: ImageBitmap?, val clouds: List<CloudLayer>)
    val key = remember(px, look, overlay, grain, orbs) { Any() }
    var current by remember { mutableStateOf<Frame?>(null) }
    var previous by remember { mutableStateOf<Frame?>(null) }
    var fadeStart by remember { mutableStateOf(0L) }
    if (inspection) {
        current = remember(key) { Frame(bakeSky(), bakeClouds()) }
    } else {
        val keyState = androidx.compose.runtime.rememberUpdatedState(key)
        // Always bake with this composition's values (look, size, scrim), not the first one's.
        val bake = androidx.compose.runtime.rememberUpdatedState { Frame(bakeSky(), bakeClouds()) }
        val fading = animate
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { keyState.value }.conflate().collect {
                val work = bake.value
                val f = withContext(Dispatchers.Default) { work() }
                if (f.base == null) return@collect
                previous = if (fading) current else null
                fadeStart = System.nanoTime()
                current = f
            }
        }
        androidx.compose.runtime.LaunchedEffect(fadeStart) { kotlinx.coroutines.delay(fadeMs.toLong() + 200); previous = null }
    }
    val base = current?.base
    val clouds = current?.clouds.orEmpty()

    val wet = weather == Sky.RAIN || weather == Sky.STORM
    val wind = (windKph / 40.0).coerceIn(0.0, 1.0).toFloat()
    val moving = animate && !inspection
    val lively = wet || weather == Sky.SNOW || seasonFor(date, latitude) == Season.AUTUMN || seasonFor(date, latitude) == Season.SPRING
    val fps = if (lively) 33L else 50L
    val time by produceState(SkyRendering.stillTime, moving) {
        if (!moving) return@produceState
        val start = withFrameMillis { it }
        var last = start
        while (true) withFrameMillis { if (it - last >= fps) { last = it; value = 37f + (it - start) / 1000f } }
    }

    val drops = remember(wet) {
        if (!wet) emptyList() else {
            val r = Random(3)
            List(150) { Drop(r.nextFloat(), r.nextFloat(), 1.05f + r.nextFloat() * 0.3f, 0.016f, 1f, 0.16f, 0f) } +
                List(36) { Drop(r.nextFloat(), r.nextFloat(), 1.8f + r.nextFloat() * 0.4f, 0.045f, 1.8f, 0.26f, 0f) }
        }
    }
    val flakes = remember(weather) {
        if (weather != Sky.SNOW) emptyList() else {
            val r = Random(5)
            List(120) { Drop(r.nextFloat(), r.nextFloat(), 0.035f + r.nextFloat() * 0.02f, 1.3f, 0f, 0.55f, r.nextFloat() * 6.28f) } +
                List(46) { Drop(r.nextFloat(), r.nextFloat(), 0.06f + r.nextFloat() * 0.03f, 2.6f, 0f, 0.75f, r.nextFloat() * 6.28f) } +
                List(10) { Drop(r.nextFloat(), r.nextFloat(), 0.11f + r.nextFloat() * 0.04f, 13f, 1f, 0.32f, r.nextFloat() * 6.28f) }
        }
    }
    val nightBucket = (look.night * 4).toInt()
    val life = remember(season, weather, nightBucket, animate) { if (animate) SeasonalLife.pick(season, weather, look.night) else SeasonalLife.pick(Season.WINTER, Sky.SNOW, 0f) }
    val twinklers = remember(stars) { stars.filter { it.a > 0.55f && it.y < 0.55f }.take(26) }

    Canvas(Modifier.fillMaxSize().graphicsLayer { clip = true }) {
        val w = size.width; val h = size.height
        val sc = h / 1080f
        val prev = previous
        val fade = if (prev == null) 1f else ((System.nanoTime() - fadeStart) / (fadeMs * 1e6f)).coerceIn(0f, 1f)
        if (prev?.base != null && fade < 1f) drawImage(prev.base)
        if (base != null) drawImage(base, alpha = if (prev?.base != null) fade else 1f)
        else if (prev?.base == null) drawRect(Brush.verticalGradient(0f to look.pal.zenith, 0.42f to look.pal.upper, 0.78f to look.pal.lower, 1f to look.pal.horizon))

        // Twinkle: a few bright stars breathe a little.
        if (look.night > 0.3f && (weather == Sky.CLEAR || weather == Sky.PARTLY)) twinklers.forEachIndexed { i, s ->
            val a = ((sin(time * (0.8f + i % 5 * 0.23f) + i * 1.7f) + 1f) / 2f) * 0.5f * look.night
            drawCircle(s.c.copy(alpha = a), max(0.8f, s.r * sc * 1.3f), Offset(s.x * w, s.y * h))
        }

        // Clouds: each texture spans two screen widths and wraps.
        val layers = if (prev != null && fade < 1f) prev.clouds.map { it to (1f - fade) } + clouds.map { it to fade } else clouds.map { it to 1f }
        layers.forEach { (l, la) ->
            val off = ((time * l.speed * (1f + wind * 2f)) % l.period)
            for (copy in 0..1) {
                val x = -off + copy * l.period
                if (x > w || x + l.period < 0f) continue
                translate(left = x) {
                    drawImage(l.image, dstOffset = IntOffset(0, l.top.toInt()), dstSize = IntSize(l.period.toInt(), l.height.toInt()), alpha = la, filterQuality = FilterQuality.Low)
                }
            }
        }

        // Storms: the cloud deck lights up from inside, and a forked bolt drops from the cloud base.
        if (weather == Sky.STORM && animate) {
            val (slot, k, t) = stormCycle(time)
            if (k > 0f) {
                val bolt = boltPath(slot, w, h)
                if (t < 0.24f) drawBolt(bolt, k)
                val o = Offset(w * (0.18f + Random(slot).nextFloat() * 0.64f), h * 0.18f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFDDE6FF).copy(alpha = 0.38f * k), Color.Transparent), o, w * 0.5f), w * 0.5f, o)
            }
        }

        // Rain: two depths of fine streaks, slanted by the wind, fading where the scrim darkens.
        if (drops.isNotEmpty()) {
            val slant = 0.12f + wind * 0.35f
            drops.forEach { d ->
                val y = ((d.y + time * d.speed) % 1.1f - 0.05f)
                val x = ((d.x + time * d.speed * slant * 0.3f) % 1f)
                val len = d.len * h
                val fade = (1f - y * 0.75f).coerceIn(0f, 1f)
                drawLine(Color(0xFFCAD6E8).copy(alpha = d.alpha * fade), Offset(x * w, y * h), Offset(x * w - len * slant, y * h + len), strokeWidth = d.width * sc, cap = StrokeCap.Round)
            }
        }
        // Snow: crisp far flakes, larger near ones, and a few out-of-focus flakes drifting past the lens.
        if (flakes.isNotEmpty()) flakes.forEach { f ->
            val y = ((f.y + time * f.speed) % 1.1f - 0.05f)
            val x = ((f.x * w + sin(time * 0.6f + f.phase) * 22f * sc + time * wind * 40f * sc) % w + w) % w
            val fade = (1f - y * 0.6f).coerceIn(0f, 1f)
            if (f.width > 0f) {
                val r = f.len * sc
                drawImage(dotSprite, dstOffset = IntOffset((x - r).toInt(), (y * h - r).toInt()), dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()), alpha = f.alpha * fade)
            } else drawCircle(Color.White.copy(alpha = f.alpha * fade), f.len * sc, Offset(x, y * h))
        }

        // Seasonal life: leaves (with the rain, if it's raining), petals or fireflies.
        if (life.active) life.draw(this, time, wind, wet, look.night)

        sideScrim?.let { drawRect(it, size = Size(w * 0.7f, h)) }
    }
}
