package app.marquee.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.marquee.tv.weather.Sky
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.sin
import kotlin.random.Random

/** Slow Ken Burns drift used behind every screen. */
@Composable
fun driftScale(): Pair<Float, Float> {
    val t = rememberInfiniteTransition(label = "drift")
    val s by t.animateFloat(1.04f, 1.09f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    val x by t.animateFloat(0f, -24f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    return s to x
}

/** Full-bleed artwork with drift; crossfades when the url changes. */
@Composable
fun ArtBackdrop(url: String?, blurRadius: Int = 0, modifier: Modifier = Modifier, drift: Boolean = true) {
    // Drift values are read inside graphicsLayer only, so the animation never recomposes anything.
    val t = if (drift) rememberInfiniteTransition(label = "drift") else null
    val s = t?.animateFloat(1.04f, 1.09f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    val x = t?.animateFloat(0f, -24f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    Crossfade(url, animationSpec = tween(700), label = "art", modifier = modifier.fillMaxSize()) { u ->
        Box(Modifier.fillMaxSize().background(Color(0xFF07060B))) {
            if (u != null) AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { val sc = s?.value ?: 1.04f; scaleX = sc; scaleY = sc; translationX = (x?.value ?: 0f) * density }
                    .then(if (blurRadius > 0) Modifier.blur(blurRadius.dp) else Modifier),
            )
        }
    }
}

/** A living sky that follows the real weather, the sun and the season. */
@Composable
fun WeatherBackdrop(sky: Sky?, isDay: Boolean?, modifier: Modifier = Modifier) = LiveSky(sky, isDay, modifier)

/** The scene for this home: the Settings choice, or detected from the city's location and elevation. */
fun sceneFor(s: app.marquee.tv.data.Settings, weather: app.marquee.tv.weather.Weather?): SceneChoice {
    val auto = Landscapes.detect(s.latitude, s.longitude, weather?.elevationM, s.cityName)
    return runCatching { Scene.valueOf(s.scene) }.getOrNull()?.let { auto.copy(scene = it, description = it.label) } ?: auto
}

@Composable
fun WeatherBackdrop(
    weather: app.marquee.tv.weather.Weather?, modifier: Modifier = Modifier,
    overlay: androidx.compose.ui.graphics.Brush? = null, grain: Boolean = false, sideScrim: androidx.compose.ui.graphics.Brush? = null,
    orbs: Boolean = false, // sun & moon discs only where no UI sits over the sky (Ambient)
) {
    // The performance check's "sky switched off" steps: a plain dark background instead.
    if (app.marquee.tv.system.PerfCheck.liteLevel >= 1) { Box(modifier.fillMaxSize().background(Color(0xFF07060B))); return }
    val store = androidx.compose.runtime.remember { runCatching { app.marquee.tv.MarqueeApp.instance.store }.getOrNull() }
    val s = store?.state?.collectAsState()?.value
    // Re-read the clock every minute so the sky follows the sun through the day.
    val now by androidx.compose.runtime.produceState(LocalTime.now()) { while (true) { delay(30_000); value = LocalTime.now() } }
    LiveSky(
        weather?.sky, weather?.isDay, modifier,
        windKph = weather?.windKph ?: 0.0, sunrise = weather?.sunrise, sunset = weather?.sunset,
        latitude = s?.latitude ?: Double.NaN, longitude = s?.longitude ?: Double.NaN, now = now, overlay = overlay, grain = grain, sideScrim = sideScrim, orbs = orbs,
    )
}

/** Crossfading slideshow of artwork for Ambient mode. */
@Composable
fun Slideshow(urls: List<String>, intervalMs: Long = 30_000, onIndex: (Int) -> Unit = {}) {
    var i by remember(urls) { mutableStateOf(0) }
    LaunchedEffect(urls) {
        while (urls.size > 1) { delay(intervalMs); i = (i + 1) % urls.size; onIndex(i) }
    }
    ArtBackdrop(urls.getOrNull(i))
}

@Composable
fun Scrim(brush: Brush) = Box(Modifier.fillMaxSize().background(brush))

/**
 * Full-bleed artwork with its scrims (and optional grain) painted once into a single opaque bitmap.
 * The TV's GPU is fill-rate bound, so one full-screen layer instead of four or five is the biggest
 * single win for smooth scrolling. Crossfades briefly when the artwork changes.
 */
@Composable
fun BakedBackdrop(url: String?, scrims: List<Brush>, grain: Float = 0f, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var px by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var baked by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url, px, scrims) {
        if (px == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val src = url?.let {
                val req = coil.request.ImageRequest.Builder(context).data(it).size(px.width, px.height)
                    .scale(coil.size.Scale.FILL).allowHardware(false).build()
                (coil.Coil.imageLoader(context).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
            if (url != null && src == null) return@withContext null // keep the previous art rather than flash to black
            val out = androidx.compose.ui.graphics.ImageBitmap(px.width, px.height)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(out),
                androidx.compose.ui.geometry.Size(px.width.toFloat(), px.height.toFloat()),
            ) {
                drawRect(Color(0xFF07060B))
                if (src != null) {
                    // Centre-crop
                    val scale = maxOf(size.width / src.width, size.height / src.height)
                    val w = src.width * scale; val h = src.height * scale
                    drawImage(
                        src.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    )
                }
                scrims.forEach { drawRect(it) }
                if (grain > 0f) drawRect(grainBrush(), alpha = grain)
            }
            out.prepareToDraw()
            out
        }
        if (bmp != null) baked = bmp
    }
    Box(modifier.fillMaxSize().onSizeChanged { px = it }) {
        Crossfade(baked, animationSpec = tween(600), label = "baked") { b ->
            if (b != null) androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            else Box(Modifier.fillMaxSize().background(Color(0xFF07060B)))
        }
    }
}
