package app.marquee.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import androidx.tv.material3.Text
import app.marquee.tv.Nav
import app.marquee.tv.ui.MarqueeVm
import app.marquee.tv.ui.components.LiveSky
import app.marquee.tv.ui.theme.C
import app.marquee.tv.ui.theme.T
import app.marquee.tv.weather.Solar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A time-lapse of tonight's sunset for your exact location: from an hour before to the end of
 * nautical twilight in about 45 seconds, with today's real weather. Loops until you press Back.
 */
@Composable
fun SunsetPreview(vm: MarqueeVm, nav: Nav) {
    val s by vm.settings.collectAsState()
    val weather by vm.weather.collectAsState()
    val today = remember { LocalDate.now() }
    // Tonight's sunset: the minute the sun's upper edge meets the horizon (-0.833°), found from the solar equations;
    // falls back to the forecast's time when no location is set.
    val sunset = remember(s.latitude, s.longitude, weather?.sunset) {
        if (s.hasLocation) {
            (12 * 60 until 23 * 60).firstOrNull { m ->
                Solar.position(s.latitude, s.longitude, ZonedDateTime.of(today, LocalTime.of(m / 60, m % 60), ZoneId.systemDefault())).elevation < -0.833
            }?.let { LocalTime.of(it / 60, it % 60) }
        } else null
    } ?: weather?.sunset ?: LocalTime.of(18, 0)
    val start = sunset.minusMinutes(60)
    val spanMin = 60 + 75 // to the end of nautical twilight, roughly
    val durationMs = 45_000f
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val t0 = withFrameMillis { it }
        while (true) withFrameMillis { progress = ((it - t0) % durationMs.toLong()) / durationMs }
    }
    val simulated = start.plusSeconds((progress * spanMin * 60).toLong())
    val elev = if (s.hasLocation) Solar.position(s.latitude, s.longitude, ZonedDateTime.of(today, simulated, ZoneId.systemDefault())).elevation else Double.NaN
    val fmt = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().focusRequester(focus).focusable()) {
        LiveSky(
            weather?.sky, null, Modifier.fillMaxSize(),
            windKph = weather?.windKph ?: 8.0, sunrise = weather?.sunrise, sunset = weather?.sunset,
            latitude = s.latitude, longitude = s.longitude, date = today, now = simulated,
            grain = true, fadeMs = 900, stepMinutes = 2,
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC03040A))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TONIGHT'S SUNSET${if (s.cityName.isNotBlank()) " · ${s.cityName.uppercase()}" else ""}", style = T.label)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(simulated.format(fmt), style = T.serif(64.sp))
                Text(phase(elev, simulated, sunset), style = T.sans(16.sp, FontWeight.SemiBold, C.AmberSoft), modifier = Modifier.padding(bottom = 12.dp))
            }
            Text(
                "Sunset at ${sunset.format(fmt)}" + (if (!elev.isNaN()) " · sun ${"%.1f".format(elev)}° ${if (elev >= 0) "above" else "below"} the horizon" else "") +
                    " · ${weather?.summary ?: "today's weather"}",
                style = T.sans(12.sp, color = C.TextDim),
            )
            Box(Modifier.padding(top = 6.dp).width(520.dp).height(3.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(C.Amber))
            }
            Text("Time-lapse from the real sun position for your location · Back to return", style = T.sans(10.sp, color = C.TextMute))
        }
    }
}

private fun phase(elev: Double, now: LocalTime, sunset: LocalTime): String = when {
    !elev.isNaN() -> when {
        elev > 6 -> "Late afternoon"
        elev > 0 -> "Golden hour"
        elev > -0.833 -> "Sunset"
        elev > -6 -> "Civil twilight"
        elev > -12 -> "Blue hour · nautical twilight"
        else -> "Night"
    }
    now.isBefore(sunset.minusMinutes(40)) -> "Late afternoon"
    now.isBefore(sunset) -> "Golden hour"
    now.isBefore(sunset.plusMinutes(30)) -> "Twilight"
    else -> "Night"
}
