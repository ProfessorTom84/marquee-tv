package app.marquee.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.marquee.tv.ui.components.CardBake
import coil.ImageLoader
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The baked card picture: cropped to the card, rounded corners, the bottom shade; and the dim as a colour filter. */
class CardBakeTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1200, screenHeight = 400, xdpi = 320, ydpi = 320,
            density = com.android.resources.Density.XHIGH, orientation = com.android.resources.ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        environment = app.cash.paparazzi.detectEnvironment().copy(compileSdkVersion = 34),
    )

    /** A wide, bright test picture (wider than a card, so the crop shows). */
    private fun art(): Bitmap = Bitmap.createBitmap(900, 400, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawRect(0f, 0f, 900f, 400f, Paint().apply {
            shader = LinearGradient(0f, 0f, 900f, 400f, 0xFF3B6FD8.toInt(), 0xFFE19A4C.toInt(), Shader.TileMode.CLAMP)
        })
    }

    @Test fun bakedCards() {
        val ctx = paparazzi.context
        val baked = runBlocking { CardBake(ctx, ImageLoader(ctx), null, 22f, 24f, toGraphicsChip = false).transform(art(), Size(340, 192)) }
        assertEquals(340, baked.width); assertEquals(192, baked.height)
        // Rounded corners: the very corner is transparent; the middle is opaque.
        assertEquals(0, android.graphics.Color.alpha(baked.getPixel(0, 0)))
        assertEquals(255, android.graphics.Color.alpha(baked.getPixel(170, 96)))
        // The shade darkens the bottom compared with the same picture unshaded.
        fun lum(c: Int) = android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)
        val plain = runBlocking { CardBake(ctx, ImageLoader(ctx), null, 22f, 24f, shade = false, toGraphicsChip = false).transform(art(), Size(340, 192)) }
        org.junit.Assert.assertTrue(lum(baked.getPixel(170, 185)) < lum(plain.getPixel(170, 185)) * 0.5)
        org.junit.Assert.assertEquals(lum(plain.getPixel(170, 30)), lum(baked.getPixel(170, 30)))
        val img = baked.asImageBitmap()
        paparazzi.snapshot {
            Row(Modifier.fillMaxSize().background(Color(0xFF07060B)).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(img, null, Modifier.size(170.dp, 96.dp)) // focused: as baked
                Image(img, null, Modifier.size(170.dp, 96.dp), colorFilter = ColorFilter.lighting(Color(0.88f, 0.88f, 0.88f), Color.Black)) // resting
                Image(plain.asImageBitmap(), null, Modifier.size(170.dp, 96.dp)) // no shade
            }
        }
    }
}
