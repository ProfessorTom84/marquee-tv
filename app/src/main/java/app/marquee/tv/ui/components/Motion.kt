package app.marquee.tv.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The card you pick grows into its page: the card's artwork expands from exactly where it sat to full screen,
 * then dissolves into the details page underneath.
 */
object CardZoom {
    class Shot(val titleId: String, val url: String?, val from: Rect, val at: Long)
    @Volatile var lastClick: Shot? = null
    internal var playing by mutableStateOf<Shot?>(null)

    fun clicked(titleId: String, url: String?, from: Rect?) {
        if (from == null || from.width < 8f) return
        lastClick = Shot(titleId, url, from, SystemClock.uptimeMillis())
    }

    /** Called when a details page opens: plays the zoom if that page is for the card just clicked. */
    fun opened(titleId: String) {
        val s = lastClick ?: return
        lastClick = null
        if (s.titleId == titleId && SystemClock.uptimeMillis() - s.at < 800) playing = s
    }
}

@Composable
fun CardZoomOverlay() {
    val shot = CardZoom.playing ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    val grow = remember(shot) { Animatable(0f) }
    val fade = remember(shot) { Animatable(1f) }
    LaunchedEffect(shot) {
        launch { grow.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        delay(470)
        fade.animateTo(0f, tween(320))
        CardZoom.playing = null
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (size == IntSize.Zero) return@Box
        val w = size.width.toFloat(); val h = size.height.toFloat()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = grow.value
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = shot.from.left * (1 - p)
                translationY = shot.from.top * (1 - p)
                scaleX = (shot.from.width / w) + (1 - shot.from.width / w) * p
                scaleY = (shot.from.height / h) + (1 - shot.from.height / h) * p
                alpha = fade.value
                shape = RoundedCornerShape((1 - p) * 60f)
                clip = true
            },
        ) {
            AsyncImage(shot.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Ease toward the details page's own darkening so the hand-off is seamless.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = grow.value }.background(
                Brush.horizontalGradient(0f to Color(0xF207060B), 0.42f to Color(0xB307060B), 0.7f to Color(0x4007060B), 1f to Color(0x8C07060B)),
            ))
        }
    }
}
