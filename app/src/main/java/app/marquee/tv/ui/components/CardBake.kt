package app.marquee.tv.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import coil.transform.Transformation
import java.util.concurrent.ConcurrentHashMap

/**
 * Bakes a title card into one picture, once, in the background as its artwork loads: the artwork cropped to the
 * card with rounded corners, the bottom shade that keeps the title readable, and the title's logo.
 *
 * On the Google TV Streamer's graphics chip, drawing those as separate layers on every card cost about 9 ms of a
 * 16.7 ms frame (measured with the built-in performance check). Baked, each card is a single texture draw, kept on
 * the graphics chip (a hardware bitmap) so scrolling never re-uploads it.
 */
class CardBake(
    private val context: Context,
    private val loader: ImageLoader,
    private val logoUrl: String?,
    private val radiusPx: Float,
    private val logoPadPx: Float,
    private val shade: Boolean = true,
    private val toGraphicsChip: Boolean = true, // false only in tests (no graphics chip there)
) : Transformation {
    override val cacheKey = "card-bake-1:${logoUrl ?: "-"}:$radiusPx:$logoPadPx:$shade"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = (size.width as? Dimension.Pixels)?.px ?: input.width
        val h = (size.height as? Dimension.Pixels)?.px ?: input.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // Artwork, centre-cropped to the card, inside the rounded corners.
        val scale = maxOf(w / input.width.toFloat(), h / input.height.toFloat())
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - input.width * scale) / 2f, (h - input.height * scale) / 2f)
        }
        val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.setLocalMatrix(m) }
        }
        c.drawRoundRect(rect, radiusPx, radiusPx, art)

        // The bottom shade (transparent to 80% black from 45% down), same as the old separate layer.
        if (shade) c.drawRoundRect(rect, radiusPx, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        })

        // The title's logo, fitted into the bottom-left 62% × 28% of the card.
        if (logoUrl != null) {
            val lw = (w * 0.62f).toInt(); val lh = (h * 0.28f).toInt()
            val logo = runCatching {
                val req = ImageRequest.Builder(context).data(logoUrl).size(lw, lh).scale(Scale.FIT).allowHardware(false).build()
                ((loader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
            if (logo != null && logo.width > 0 && logo.height > 0) {
                val s = minOf(lw / logo.width.toFloat(), lh / logo.height.toFloat())
                val dw = logo.width * s; val dh = logo.height * s
                val left = logoPadPx; val bottom = h - logoPadPx
                c.drawBitmap(logo, null, RectF(left, bottom - dh, left + dw, bottom), Paint(Paint.FILTER_BITMAP_FLAG))
                logoDrawn[logoUrl] = true
            } else logoDrawn[logoUrl] = false
        }

        // Keep it on the graphics chip: no texture re-uploads while scrolling, and outside the 99 MB texture cache.
        if (!toGraphicsChip) return out
        val hw = runCatching { out.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
        return if (hw != null) { out.recycle(); hw } else out
    }

    companion object {
        /** Logo URL → whether it was baked in (false: it failed to load, so the card shows the title as text). */
        val logoDrawn = ConcurrentHashMap<String, Boolean>()
    }
}
