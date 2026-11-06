package app.marquee.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small line icons drawn for Marquee (24×24 grid), so the two settings buttons look clearly different. */
object MarqueeIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(d: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = d)

    private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) = path(fill = SolidColor(Color.Black)) {
        moveTo(cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); close()
    }

    /** Sliders: Marquee's own settings (look, services, accounts). */
    val Tune: ImageVector = icon("Tune") {
        line { moveTo(4f, 6f); lineTo(20f, 6f) }
        line { moveTo(4f, 12f); lineTo(20f, 12f) }
        line { moveTo(4f, 18f); lineTo(20f, 18f) }
        dot(9f, 6f, 2.6f); dot(15f, 12f, 2.6f); dot(7f, 18f, 2.6f)
    }

    /** A TV with a small gear: Google TV's system settings. */
    val TvSettings: ImageVector = icon("TvSettings") {
        line { moveTo(5f, 4.5f); lineTo(19f, 4.5f); quadTo(21f, 4.5f, 21f, 6.5f); lineTo(21f, 14.5f); quadTo(21f, 16.5f, 19f, 16.5f)
            lineTo(5f, 16.5f); quadTo(3f, 16.5f, 3f, 14.5f); lineTo(3f, 6.5f); quadTo(3f, 4.5f, 5f, 4.5f); close() }
        line { moveTo(8f, 20f); lineTo(16f, 20f) }
        // gear: hub plus four teeth
        dot(12f, 10.5f, 1.7f)
        line { moveTo(12f, 7.2f); lineTo(12f, 8f) }
        line { moveTo(12f, 13f); lineTo(12f, 13.8f) }
        line { moveTo(8.7f, 10.5f); lineTo(9.5f, 10.5f) }
        line { moveTo(14.5f, 10.5f); lineTo(15.3f, 10.5f) }
    }

    /** Two people: Pick Together. */
    val People: ImageVector = icon("People") {
        dot(8.5f, 8f, 3f)
        dot(16f, 9f, 2.5f)
        line { moveTo(3f, 19f); quadTo(3.5f, 13.5f, 8.5f, 13.5f); quadTo(13.5f, 13.5f, 14f, 19f) }
        line { moveTo(14.5f, 14.2f); quadTo(15.2f, 14f, 16f, 14f); quadTo(20.5f, 14f, 21f, 19f) }
    }
}
