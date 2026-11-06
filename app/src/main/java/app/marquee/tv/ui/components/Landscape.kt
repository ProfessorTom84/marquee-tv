package app.marquee.tv.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The landscape painted under the sky. Picked from where you live, or chosen in Settings. */
enum class Scene(val label: String) {
    CITY("City skyline"),
    SUBURB("Suburban street"),
    RURAL("Rolling countryside"),
    DESERT("Desert mesas"),
    MOUNTAINS("Mountains and pines"),
    COAST("Beach and palms"),
    PLAINS("Plains and farmland"),
    FOREST("Evergreen forest"),
}

data class SceneChoice(val scene: Scene, val description: String, val skylineOnHorizon: Boolean = false, val saguaro: Boolean = false)

object Landscapes {
    private data class Metro(val name: String, val lat: Double, val lon: Double)

    private val metros = listOf(
        Metro("New York", 40.7128, -74.006), Metro("Chicago", 41.8781, -87.6298), Metro("Los Angeles", 34.0522, -118.2437),
        Metro("San Francisco", 37.7749, -122.4194), Metro("Seattle", 47.6062, -122.3321), Metro("Boston", 42.3601, -71.0589),
        Metro("Philadelphia", 39.9526, -75.1652), Metro("Washington", 38.9072, -77.0369), Metro("Houston", 29.7604, -95.3698),
        Metro("Dallas", 32.7767, -96.797), Metro("Atlanta", 33.749, -84.388), Metro("Miami", 25.7617, -80.1918),
        Metro("Denver", 39.7392, -104.9903), Metro("Phoenix", 33.4484, -112.074), Metro("Minneapolis", 44.9778, -93.265),
        Metro("Detroit", 42.3314, -83.0458), Metro("San Diego", 32.7157, -117.1611), Metro("Las Vegas", 36.1699, -115.1398),
        Metro("Austin", 30.2672, -97.7431), Metro("Nashville", 36.1627, -86.7816), Metro("Charlotte", 35.2271, -80.8431),
        Metro("Portland", 45.5152, -122.6784), Metro("Pittsburgh", 40.4406, -79.9959), Metro("New Orleans", 29.9511, -90.0715),
        Metro("St. Louis", 38.627, -90.1994), Metro("Baltimore", 39.2904, -76.6122), Metro("Toronto", 43.6532, -79.3832),
        Metro("Salt Lake City", 40.7608, -111.891), Metro("Kansas City", 39.0997, -94.5786), Metro("Columbus", 39.9612, -82.9988),
    )

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    /** Best guess at the landscape around you: cities and suburbs, deserts, mountains, coasts, plains, forests. */
    fun detect(lat: Double, lon: Double, elevationM: Double?, city: String): SceneChoice {
        if (lat.isNaN() || lon.isNaN()) return SceneChoice(Scene.RURAL, "Default countryside")
        val nearest = metros.minBy { km(lat, lon, it.lat, it.lon) }
        val d = km(lat, lon, nearest.lat, nearest.lon)
        val elev = elevationM ?: 0.0
        val desert = lat in 31.0..37.6 && lon in -117.6..-103.0 && elev < 2300
        return when {
            d < 10 -> SceneChoice(Scene.CITY, "Downtown ${nearest.name}")
            desert -> SceneChoice(Scene.DESERT, "Southwest desert", saguaro = lon < -109.5 && lat < 34.6)
            elev > 1600 || (lon in -123.0..-104.0 && lat in 36.0..49.5 && elev > 1100) -> SceneChoice(Scene.MOUNTAINS, "Mountain country")
            lon < -150 || (lat < 30.8 && lon > -98.0) || (lon < -117.0 && lat < 34.6 && elev < 250) || city.contains("Beach", true) ->
                SceneChoice(Scene.COAST, "Coastal", skylineOnHorizon = d < 45)
            lon < -120.0 && lat > 42.0 -> SceneChoice(Scene.FOREST, "Pacific Northwest forest", skylineOnHorizon = d < 40)
            d < 60 -> SceneChoice(Scene.SUBURB, "Suburbs of ${nearest.name}", skylineOnHorizon = d < 45)
            lon in -104.0..-94.0 && lat in 33.0..49.5 -> SceneChoice(Scene.PLAINS, "Great Plains")
            else -> SceneChoice(Scene.RURAL, "Countryside")
        }
    }
}

// =====================================================================================
// Painting. Everything is drawn in code (no images), tinted by time of day, season and weather.
// =====================================================================================

class SceneLook(
    val day: Boolean,
    val twilight: Boolean,
    val gray: Boolean,
    val season: Season,
    val halloween: Boolean,
    val december: Boolean,
    val time: Float,
    val wind: Float,
) {
    /** Darkens and cools colours at night, warms them at golden hour, flattens them under cloud. */
    fun shade(c: Color): Color {
        var x = c
        if (!day) x = lerp(x, Color(0xFF0B1022), 0.72f)
        else if (twilight) x = lerp(x, Color(0xFF3A2238), 0.35f)
        if (gray) x = lerp(x, Color(0xFF3A4150), 0.3f)
        return x
    }
    val snowy get() = season == Season.WINTER
}

fun DrawScope.drawScene(choice: SceneChoice, look: SceneLook) {
    val w = size.width; val h = size.height
    when (choice.scene) {
        Scene.CITY -> {
            skyline(w, h, 0.5f, 0.12f, look.shade(Color(0xFF3B4664)), look, seed = 2, windows = 0.25f)
            skyline(w, h, 0.62f, 0.2f, look.shade(Color(0xFF242C44)), look, seed = 7, windows = 0.45f)
            ground(w, h, 0.84f, look.shade(Color(0xFF1A1E2A)))
            streetLights(w, h, 0.84f, look)
        }
        Scene.SUBURB -> {
            hills(w, h, 0.64f, 0.06f, look.shade(seasonal(look, Color(0xFF6E8E6A))), 3, snowCap = false)
            if (choice.skylineOnHorizon) skyline(w * 0.42f, h, 0.66f, 0.07f, look.shade(Color(0xFF5A6684)).copy(alpha = 0.75f), look, seed = 4, windows = 0.2f, xOffset = w * 0.08f)
            hills(w, h, 0.74f, 0.04f, look.shade(seasonal(look, Color(0xFF4F7A52))), 6, snowCap = false)
            ground(w, h, 0.8f, look.shade(if (look.snowy) Color(0xFFDDE4EE) else seasonal(look, Color(0xFF3E6A40))))
            val r = Random(11)
            var x = w * 0.03f
            while (x < w) {
                val s = h * (0.09f + r.nextFloat() * 0.03f)
                if (r.nextFloat() < 0.55f) { house(x, h * 0.8f, s, r, look); x += s * 1.5f } else { tree(x + s * 0.3f, h * 0.8f, s * 1.15f, look, r.nextInt()); x += s * 0.9f }
                x += s * (0.15f + r.nextFloat() * 0.4f)
            }
            streetLights(w, h, 0.8f, look)
        }
        Scene.RURAL -> {
            hills(w, h, 0.6f, 0.12f, look.shade(seasonal(look, Color(0xFF7A9470))), 3, snowCap = false)
            hills(w, h, 0.7f, 0.08f, look.shade(seasonal(look, Color(0xFF557E56))), 5, snowCap = false)
            ground(w, h, 0.82f, look.shade(if (look.snowy) Color(0xFFDDE4EE) else seasonal(look, Color(0xFF3F6B40))))
            val r = Random(21)
            house(w * 0.62f, h * 0.76f, h * 0.08f, r, look, farm = true)
            for (i in 0..11) tree(w * (0.04f + i * 0.085f + r.nextFloat() * 0.03f), h * (0.78f + r.nextFloat() * 0.04f), h * (0.07f + r.nextFloat() * 0.04f), look, i)
        }
        Scene.DESERT -> {
            mesas(w, h, look)
            ground(w, h, 0.78f, look.shade(Color(0xFFC98E5A)))
            ground(w, h, 0.86f, look.shade(Color(0xFFA86E40)))
            val r = Random(31)
            for (i in 0..6) {
                val x = w * (0.06f + i * 0.15f + r.nextFloat() * 0.05f)
                if (choice.saguaro) saguaro(x, h * (0.82f + r.nextFloat() * 0.06f), h * (0.12f + r.nextFloat() * 0.07f), look)
                else yucca(x, h * (0.82f + r.nextFloat() * 0.06f), h * (0.06f + r.nextFloat() * 0.04f), look)
                shrub(x + w * 0.05f, h * (0.85f + r.nextFloat() * 0.05f), h * 0.02f, look)
            }
        }
        Scene.MOUNTAINS -> {
            peaks(w, h, 0.3f, look.shade(Color(0xFF6E7C98)), 13, capAlways = true, look = look)
            peaks(w, h, 0.45f, look.shade(Color(0xFF4C5A74)), 17, capAlways = look.snowy, look = look)
            pineBand(w, h, 0.74f, h * 0.09f, look, 41)
            ground(w, h, 0.84f, look.shade(if (look.snowy) Color(0xFFDDE4EE) else Color(0xFF2E4A34)))
            pineBand(w, h, 0.86f, h * 0.14f, look, 43)
        }
        Scene.COAST -> {
            if (choice.skylineOnHorizon) skyline(w * 0.35f, h, 0.64f, 0.06f, look.shade(Color(0xFF6A7898)).copy(alpha = 0.7f), look, seed = 5, windows = 0.15f, xOffset = w * 0.55f)
            hills(w * 0.4f, h, 0.64f, 0.05f, look.shade(Color(0xFF5E7488)), 8, snowCap = false)
            ocean(w, h, 0.66f, 0.84f, look)
            ground(w, h, 0.84f, look.shade(Color(0xFFE2C9A0)))
            palm(w * 0.12f, h * 0.9f, h * 0.32f, look, 0f)
            palm(w * 0.2f, h * 0.92f, h * 0.24f, look, 1.3f)
            palm(w * 0.86f, h * 0.91f, h * 0.28f, look, 2.1f)
        }
        Scene.PLAINS -> {
            ground(w, h, 0.7f, look.shade(if (look.snowy) Color(0xFFD5DCE6) else Color(0xFF9DA86A)))
            fields(w, h, 0.7f, look)
            barn(w * 0.7f, h * 0.72f, h * 0.07f, look)
            windmillTower(w * 0.82f, h * 0.73f, h * 0.16f, look)
        }
        Scene.FOREST -> {
            peaks(w, h, 0.42f, look.shade(Color(0xFF6A7C90)), 23, capAlways = true, look = look)
            pineBand(w, h, 0.66f, h * 0.1f, look, 51, color = look.shade(Color(0xFF3A5A4E)))
            pineBand(w, h, 0.76f, h * 0.13f, look, 53, color = look.shade(Color(0xFF2A4A3A)))
            ground(w, h, 0.86f, look.shade(Color(0xFF1E3226)))
            pineBand(w, h, 0.9f, h * 0.18f, look, 57, color = look.shade(Color(0xFF16281E)))
        }
    }
}

/**
 * The few parts of a scene that move (windmill blades, waves, mist). Drawn every frame on top of
 * the cached still scene, so animation stays smooth without repainting houses and hills 30 times a second.
 */
fun DrawScope.drawSceneAnimated(choice: SceneChoice, look: SceneLook) {
    val w = size.width; val h = size.height
    when (choice.scene) {
        Scene.PLAINS -> windmillBlades(w * 0.82f, h * 0.73f, h * 0.16f, look)
        Scene.COAST -> oceanWaves(w, h, 0.66f, 0.84f, look)
        Scene.FOREST -> for (i in 0..2) {
            val x = ((look.time * 0.008f * (i + 1)) % 1f) * w
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x30DDE6EE), Color.Transparent), x - w * 0.6f, x + w * 0.6f), Offset(0f, h * (0.6f + i * 0.08f)), Size(w, h * 0.08f))
        }
        else -> {}
    }
}

private fun seasonal(look: SceneLook, summerGreen: Color): Color = when (look.season) {
    Season.AUTUMN -> lerp(summerGreen, Color(0xFF9A6A3A), 0.55f)
    Season.WINTER -> lerp(summerGreen, Color(0xFF7A8090), 0.6f)
    Season.SPRING -> lerp(summerGreen, Color(0xFF8CC07A), 0.35f)
    Season.SUMMER -> summerGreen
}

private fun DrawScope.ground(w: Float, h: Float, top: Float, c: Color) = drawRect(c, Offset(0f, h * top), Size(w, h * (1f - top)))

private fun DrawScope.hills(w: Float, h: Float, base: Float, amp: Float, c: Color, seed: Int, snowCap: Boolean) {
    val r = Random(seed)
    val p = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * base)
        var x = 0f
        var prevY = h * base
        while (x < w) {
            val nx = x + w / 6f
            val ny = h * (base + (r.nextFloat() - 0.5f) * amp)
            quadraticTo(x + w / 12f, prevY - h * amp * 0.5f, nx, ny)
            x = nx; prevY = ny
        }
        lineTo(w, h); close()
    }
    drawPath(p, c)
    if (snowCap) drawPath(p, Color.White.copy(alpha = 0.3f), style = Stroke(h * 0.01f))
}

private fun DrawScope.skyline(w: Float, h: Float, top: Float, var_: Float, c: Color, look: SceneLook, seed: Int, windows: Float, xOffset: Float = 0f) {
    val r = Random(seed)
    var x = xOffset
    val base = h * 0.86f
    while (x < xOffset + w) {
        val bw = h * (0.03f + r.nextFloat() * 0.05f)
        val bh = h * (top.let { 0.86f - it } * (0.45f + r.nextFloat() * 0.55f) + r.nextFloat() * var_)
        drawRect(c, Offset(x, base - bh), Size(bw, bh))
        if (r.nextFloat() < 0.18f) drawRect(c, Offset(x + bw * 0.45f, base - bh - h * 0.035f), Size(bw * 0.1f, h * 0.035f)) // spire
        // Windows: lit after dark, a few flicker on and off slowly.
        if (!look.day || look.twilight) {
            val cols = (bw / (h * 0.012f)).toInt().coerceAtLeast(1)
            val rows = (bh / (h * 0.018f)).toInt()
            for (cx in 0 until cols) for (ry in 1 until rows) {
                val k = (cx * 31 + ry * 17 + seed * 7) % 100
                val lit = k < windows * 100 && (k % 13 != 0 || sin(look.time * 0.2f + k) > 0)
                if (lit) drawRect(Color(0xCCFFD58A), Offset(x + bw * (cx + 0.3f) / cols, base - bh + ry * h * 0.018f), Size(bw / cols * 0.4f, h * 0.007f))
            }
        }
        x += bw + h * (0.004f + r.nextFloat() * 0.01f)
    }
}

private fun DrawScope.streetLights(w: Float, h: Float, base: Float, look: SceneLook) {
    if (look.day && !look.twilight) return
    var x = w * 0.05f
    while (x < w) {
        val top = Offset(x, h * base - h * 0.06f)
        drawLine(Color(0xFF15182A), Offset(x, h * base), top, strokeWidth = h * 0.003f)
        drawCircle(Brush.radialGradient(listOf(Color(0x99FFD58A), Color.Transparent), top, h * 0.05f), h * 0.05f, top)
        drawCircle(Color(0xFFFFE6B0), h * 0.004f, top)
        x += w * 0.16f
    }
}

private fun DrawScope.house(x: Float, base: Float, s: Float, r: Random, look: SceneLook, farm: Boolean = false) {
    val wallColors = if (farm) listOf(Color(0xFFF2EEE6)) else listOf(Color(0xFFE8DCC8), Color(0xFFB8C6D6), Color(0xFFD9B8A0), Color(0xFFC9D2BC), Color(0xFFF0EEE8))
    val roofColors = listOf(Color(0xFF5A4038), Color(0xFF3E4656), Color(0xFF6A3A30))
    val wall = look.shade(wallColors[r.nextInt(wallColors.size)])
    val roof = look.shade(roofColors[r.nextInt(roofColors.size)])
    val bw = s * 1.2f; val bh = s * 0.7f
    // chimney
    drawRect(roof, Offset(x + bw * 0.7f, base - bh - s * 0.45f), Size(bw * 0.1f, s * 0.3f))
    drawRect(wall, Offset(x, base - bh), Size(bw, bh))
    val roofPath = Path().apply { moveTo(x - bw * 0.08f, base - bh); lineTo(x + bw / 2, base - bh - s * 0.5f); lineTo(x + bw * 1.08f, base - bh); close() }
    drawPath(roofPath, roof)
    if (look.snowy) drawPath(Path().apply { moveTo(x - bw * 0.08f, base - bh); lineTo(x + bw / 2, base - bh - s * 0.5f); lineTo(x + bw * 1.08f, base - bh) }, Color.White.copy(alpha = 0.85f), style = Stroke(s * 0.07f))
    // Windows glow warm after dark; door.
    val windowColor = if (!look.day || look.twilight) Color(0xFFFFCF7A) else look.shade(Color(0xFF8FA6C0))
    drawRect(windowColor, Offset(x + bw * 0.12f, base - bh * 0.72f), Size(bw * 0.2f, bh * 0.28f))
    drawRect(windowColor, Offset(x + bw * 0.68f, base - bh * 0.72f), Size(bw * 0.2f, bh * 0.28f))
    if (!look.day) drawCircle(Brush.radialGradient(listOf(Color(0x40FFCF7A), Color.Transparent), Offset(x + bw / 2, base - bh * 0.5f), s), s, Offset(x + bw / 2, base - bh * 0.5f))
    drawRect(look.shade(Color(0xFF4A3428)), Offset(x + bw * 0.43f, base - bh * 0.5f), Size(bw * 0.14f, bh * 0.5f))
    // Seasonal porch touches
    if (look.halloween && r.nextFloat() < 0.8f) {
        val p = Offset(x + bw * 0.3f, base - s * 0.06f)
        drawCircle(Color(0xFFE07A1F), s * 0.07f, p)
        if (!look.day) drawCircle(Brush.radialGradient(listOf(Color(0x88FFB040), Color.Transparent), p, s * 0.2f), s * 0.2f, p)
    }
    if (look.december && !look.day) for (i in 0..6) {
        val lx = x - bw * 0.05f + i * bw * 0.19f
        drawCircle(listOf(Color(0xFFFF5A5A), Color(0xFF7CE07C), Color(0xFFFFD45A), Color(0xFF6AB8FF))[(i + (look.time * 1.5f).toInt()) % 4], s * 0.025f, Offset(lx, base - bh + s * 0.03f))
    }
}

private fun DrawScope.tree(x: Float, base: Float, s: Float, look: SceneLook, seed: Int) {
    val r = Random(seed)
    val trunk = look.shade(Color(0xFF4A3628))
    drawRect(trunk, Offset(x - s * 0.05f, base - s * 0.55f), Size(s * 0.1f, s * 0.55f))
    when (look.season) {
        Season.WINTER -> {
            for (i in 0..4) {
                val a = -PI / 2 + (i - 2) * 0.45
                val from = Offset(x, base - s * (0.4f + i * 0.08f))
                drawLine(trunk, from, Offset(from.x + (cos(a) * s * 0.35f).toFloat(), from.y + (sin(a) * s * 0.35f).toFloat()), strokeWidth = s * 0.03f)
            }
            drawCircle(Color.White.copy(alpha = 0.5f), s * 0.05f, Offset(x, base - s * 0.85f))
        }
        else -> {
            val palette = when (look.season) {
                Season.AUTUMN -> listOf(Color(0xFFD9822B), Color(0xFFB8451F), Color(0xFFE8B13C), Color(0xFFA0522D))
                Season.SPRING -> listOf(Color(0xFF7FBF6A), Color(0xFF95CC7C), Color(0xFFF2B8C8))
                else -> listOf(Color(0xFF3E7A42), Color(0xFF4F8C4A), Color(0xFF2F6638))
            }
            val sway = sin(look.time * 0.8f + seed) * s * 0.02f * (1f + look.wind * 2f)
            for (i in 0..3) {
                val c = look.shade(palette[r.nextInt(palette.size)])
                drawCircle(c, s * (0.26f + r.nextFloat() * 0.08f), Offset(x + sway + (r.nextFloat() - 0.5f) * s * 0.4f, base - s * (0.65f + r.nextFloat() * 0.25f)))
            }
        }
    }
}

private fun DrawScope.pineBand(w: Float, h: Float, base: Float, height: Float, look: SceneLook, seed: Int, color: Color? = null) {
    val r = Random(seed)
    val c = color ?: look.shade(Color(0xFF244A34))
    var x = -height * 0.2f
    while (x < w) {
        val s = height * (0.7f + r.nextFloat() * 0.5f)
        pine(x, h * base + r.nextFloat() * height * 0.1f, s, c, look)
        x += s * (0.28f + r.nextFloat() * 0.18f)
    }
}

private fun DrawScope.pine(x: Float, base: Float, s: Float, c: Color, look: SceneLook) {
    for (i in 0..2) {
        val top = base - s * (0.45f + i * 0.25f)
        val half = s * (0.28f - i * 0.06f)
        val p = Path().apply { moveTo(x - half, top + s * 0.32f); lineTo(x, top); lineTo(x + half, top + s * 0.32f); close() }
        drawPath(p, c)
        if (look.snowy) drawPath(Path().apply { moveTo(x - half * 0.5f, top + s * 0.16f); lineTo(x, top); lineTo(x + half * 0.5f, top + s * 0.16f) }, Color.White.copy(alpha = 0.75f), style = Stroke(s * 0.03f))
    }
}

private fun DrawScope.peaks(w: Float, h: Float, top: Float, c: Color, seed: Int, capAlways: Boolean, look: SceneLook) {
    val r = Random(seed)
    var x = -w * 0.1f
    while (x < w) {
        val pw = w * (0.18f + r.nextFloat() * 0.16f)
        val peakY = h * (top + r.nextFloat() * 0.12f)
        val baseY = h * 0.8f
        val px = x + pw / 2
        drawPath(Path().apply { moveTo(x, baseY); lineTo(px, peakY); lineTo(x + pw, baseY); close() }, c)
        if (capAlways || look.snowy) {
            val capH = (baseY - peakY) * 0.22f
            drawPath(Path().apply {
                moveTo(px - pw * 0.11f, peakY + capH); lineTo(px, peakY); lineTo(px + pw * 0.11f, peakY + capH)
                lineTo(px + pw * 0.04f, peakY + capH * 0.75f); lineTo(px - pw * 0.02f, peakY + capH * 1.05f); close()
            }, Color.White.copy(alpha = if (look.day) 0.9f else 0.45f))
        }
        x += pw * (0.55f + r.nextFloat() * 0.25f)
    }
}

private fun DrawScope.mesas(w: Float, h: Float, look: SceneLook) {
    val far = look.shade(Color(0xFFB0705A)); val near = look.shade(Color(0xFF9A4E32))
    fun mesa(x: Float, topY: Float, width: Float, c: Color) {
        drawPath(Path().apply {
            moveTo(x, h * 0.8f); lineTo(x + width * 0.12f, topY); lineTo(x + width * 0.86f, topY); lineTo(x + width, h * 0.8f); close()
        }, c)
        drawRect(lerp(c, Color.Black, 0.12f), Offset(x + width * 0.14f, topY + (h * 0.8f - topY) * 0.35f), Size(width * 0.7f, (h * 0.8f - topY) * 0.06f))
    }
    mesa(w * 0.05f, h * 0.6f, w * 0.3f, far)
    mesa(w * 0.55f, h * 0.56f, w * 0.38f, far)
    mesa(w * 0.32f, h * 0.66f, w * 0.22f, near)
    // Summer heat shimmer
    if (look.season == Season.SUMMER && look.day) for (i in 0..5) {
        val y = h * (0.78f + i * 0.008f)
        drawLine(Color(0x22FFFFFF), Offset(0f, y + sin(look.time * 3f + i) * 2f), Offset(w, y - sin(look.time * 3f + i) * 2f), strokeWidth = 1f)
    }
}

private fun DrawScope.saguaro(x: Float, base: Float, s: Float, look: SceneLook) {
    val c = look.shade(Color(0xFF3F6A3A))
    val tw = s * 0.12f
    drawRoundRect(c, Offset(x - tw / 2, base - s), Size(tw, s), androidx.compose.ui.geometry.CornerRadius(tw / 2))
    drawRoundRect(c, Offset(x - tw * 2f, base - s * 0.65f), Size(tw * 0.8f, s * 0.32f), androidx.compose.ui.geometry.CornerRadius(tw / 2))
    drawRect(c, Offset(x - tw * 2f, base - s * 0.38f), Size(tw * 1.6f, tw * 0.7f))
    drawRoundRect(c, Offset(x + tw * 1.2f, base - s * 0.8f), Size(tw * 0.8f, s * 0.38f), androidx.compose.ui.geometry.CornerRadius(tw / 2))
    drawRect(c, Offset(x + tw * 0.4f, base - s * 0.48f), Size(tw * 1.4f, tw * 0.7f))
}

private fun DrawScope.yucca(x: Float, base: Float, s: Float, look: SceneLook) {
    val c = look.shade(Color(0xFF5E7A4A))
    for (i in -3..3) {
        val a = -PI / 2 + i * 0.28
        drawLine(c, Offset(x, base), Offset(x + (cos(a) * s).toFloat(), base + (sin(a) * s).toFloat()), strokeWidth = s * 0.08f)
    }
}

private fun DrawScope.shrub(x: Float, base: Float, s: Float, look: SceneLook) {
    drawCircle(look.shade(Color(0xFF7A7A4A)), s, Offset(x, base))
    drawCircle(look.shade(Color(0xFF6A6A3E)), s * 0.8f, Offset(x + s, base + s * 0.2f))
}

private fun DrawScope.ocean(w: Float, h: Float, top: Float, bottom: Float, look: SceneLook) {
    val deep = look.shade(Color(0xFF1F6A8E)); val shallow = look.shade(Color(0xFF4FA6B8))
    drawRect(Brush.verticalGradient(listOf(deep, shallow), h * top, h * bottom), Offset(0f, h * top), Size(w, h * (bottom - top)))
}

private fun DrawScope.oceanWaves(w: Float, h: Float, top: Float, bottom: Float, look: SceneLook) {
    for (i in 0..7) {
        val y = h * (top + (bottom - top) * (i + 1) / 9f)
        val shift = sin(look.time * (0.6f + i * 0.1f) + i) * w * 0.02f
        drawLine(Color.White.copy(alpha = 0.18f + i * 0.03f), Offset(w * 0.05f + shift + i * 37f % (w * 0.3f), y), Offset(w * 0.35f + shift, y), strokeWidth = 1.6f)
        drawLine(Color.White.copy(alpha = 0.15f + i * 0.03f), Offset(w * 0.55f - shift, y + 4f), Offset(w * 0.9f - shift, y + 4f), strokeWidth = 1.6f)
    }
    // Surf line
    val surfY = h * bottom - 3f + sin(look.time * 0.9f) * 3f
    drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, surfY), Offset(w, surfY), strokeWidth = 3f)
}

private fun DrawScope.palm(x: Float, base: Float, s: Float, look: SceneLook, phase: Float) {
    val c = look.shade(Color(0xFF3A2E22)); val leaf = look.shade(Color(0xFF2E6A3A))
    val sway = sin(look.time * (0.7f + look.wind) + phase) * s * 0.03f * (1f + look.wind * 2f)
    val top = Offset(x + s * 0.12f + sway, base - s)
    val trunk = Path().apply { moveTo(x, base); quadraticTo(x + s * 0.02f, base - s * 0.5f, top.x, top.y) }
    drawPath(trunk, c, style = Stroke(s * 0.035f))
    for (i in 0..6) {
        val a = -PI + i * PI / 6 + sin(look.time + i + phase) * 0.05
        val end = Offset(top.x + (cos(a) * s * 0.32f).toFloat(), top.y + (sin(a) * s * 0.18f).toFloat() + s * 0.08f)
        drawPath(Path().apply { moveTo(top.x, top.y); quadraticTo((top.x + end.x) / 2, top.y - s * 0.06f, end.x, end.y) }, leaf, style = Stroke(s * 0.025f))
    }
}

private fun DrawScope.fields(w: Float, h: Float, top: Float, look: SceneLook) {
    val colors = if (look.snowy) listOf(Color(0xFFDDE4EE), Color(0xFFCFD7E2))
        else if (look.season == Season.AUTUMN || look.season == Season.SUMMER) listOf(Color(0xFFD9B45A), Color(0xFFB8963E), Color(0xFF8A9A4A))
        else listOf(Color(0xFF7AA05A), Color(0xFF94B46A))
    for (i in 0..7) {
        val y0 = h * (top + i * 0.04f); val y1 = h * (top + (i + 1) * 0.04f)
        drawPath(Path().apply { moveTo(0f, y0); lineTo(w, y0 - h * 0.01f); lineTo(w, y1); lineTo(0f, y1 + h * 0.01f); close() }, look.shade(colors[i % colors.size]))
    }
    // Fence
    for (i in 0..30) drawLine(look.shade(Color(0xFF5A4030)), Offset(w * i / 30f, h * (top + 0.07f)), Offset(w * i / 30f, h * (top + 0.04f)), strokeWidth = 2f)
    drawLine(look.shade(Color(0xFF5A4030)), Offset(0f, h * (top + 0.05f)), Offset(w, h * (top + 0.05f)), strokeWidth = 2f)
}

private fun DrawScope.barn(x: Float, base: Float, s: Float, look: SceneLook) {
    val red = look.shade(Color(0xFFA03A2E))
    drawRect(red, Offset(x, base - s), Size(s * 1.4f, s))
    drawPath(Path().apply { moveTo(x - s * 0.1f, base - s); lineTo(x + s * 0.2f, base - s * 1.45f); lineTo(x + s * 1.2f, base - s * 1.45f); lineTo(x + s * 1.5f, base - s); close() }, look.shade(Color(0xFF6A2A22)))
    drawRect(look.shade(Color(0xFFEDE6DA)), Offset(x + s * 0.5f, base - s * 0.6f), Size(s * 0.4f, s * 0.6f), style = Stroke(s * 0.04f))
    drawRoundRect(look.shade(Color(0xFFB8BCC4)), Offset(x - s * 0.55f, base - s * 1.8f), Size(s * 0.4f, s * 1.8f), androidx.compose.ui.geometry.CornerRadius(s * 0.2f))
    if (!look.day) drawCircle(Brush.radialGradient(listOf(Color(0x66FFCF7A), Color.Transparent), Offset(x + s * 0.7f, base - s * 0.3f), s), s, Offset(x + s * 0.7f, base - s * 0.3f))
}

private fun DrawScope.windmillTower(x: Float, base: Float, s: Float, look: SceneLook) {
    val c = look.shade(Color(0xFF6A6E78))
    drawLine(c, Offset(x - s * 0.12f, base), Offset(x, base - s), strokeWidth = s * 0.025f)
    drawLine(c, Offset(x + s * 0.12f, base), Offset(x, base - s), strokeWidth = s * 0.025f)
}

private fun DrawScope.windmillBlades(x: Float, base: Float, s: Float, look: SceneLook) {
    val c = look.shade(Color(0xFF6A6E78))
    val hub = Offset(x, base - s)
    val spin = look.time * (0.6f + look.wind * 3f)
    for (i in 0 until 12) {
        val a = spin + i * (2 * PI.toFloat() / 12)
        drawLine(c, hub, Offset(hub.x + cos(a) * s * 0.2f, hub.y + sin(a) * s * 0.2f), strokeWidth = s * 0.02f)
    }
    drawCircle(c, s * 0.03f, hub)
}
