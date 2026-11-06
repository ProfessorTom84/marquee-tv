package app.marquee.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.marquee.tv.R

object C {
    val Ink = Color(0xFF07060B)
    val Night = Color(0xFF060917)
    val Text = Color(0xFFF6F1E8)
    val TextDim = Color(0xFFC9C3BA)
    val TextMute = Color(0xFFA9A194)
    val Cool = Color(0xFFC3C9DA)
    val Amber = Color(0xFFF5B54A)
    val AmberSoft = Color(0xFFFFE2A8)
    val Imdb = Color(0xFFF5C518)
    val Good = Color(0xFF9FE0B5)
    val Warn = Color(0xFFFFB27A)
    val Blue = Color(0xFF8EA2E8)
    val GlassWarm = Color(0x612A1A12)
    val GlassCool = Color(0x66161C3A)
    val Stroke = Color(0x1FFFFFFF)
    val Highlight = Color(0x3DFFFFFF)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Sans = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))
val Serif = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

object T {
    fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = C.Text, spacing: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontFamily = Sans, fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing)
    fun serif(size: TextUnit, color: Color = C.Text, spacing: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontFamily = Serif, fontSize = size, color = color, letterSpacing = spacing)

    // Sizes are tuned for a 1080p TV viewed from the couch (1sp ≈ 1.5px at 1080p / 2x density).
    val eyebrow = sans(10.sp, FontWeight.ExtraBold, C.Amber, 1.6.sp)
    val label = sans(9.sp, FontWeight.Bold, C.Cool, 1.sp)
    val body = sans(12.sp, FontWeight.Medium, C.TextDim)
    val bodyStrong = sans(12.sp, FontWeight.Bold, C.Text)
    val small = sans(10.sp, FontWeight.Medium, C.TextMute)
    val rowTitle = sans(15.sp, FontWeight.Bold, C.Text)
    val cardTitle = sans(15.sp, FontWeight.ExtraBold, C.Text)
}

@Composable
fun MarqueeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = C.Amber, onPrimary = Color(0xFF1A1206), surface = C.Ink, background = C.Ink),
        content = content,
    )
}
