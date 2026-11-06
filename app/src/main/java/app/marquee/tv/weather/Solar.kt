package app.marquee.tv.weather

import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, from NOAA's published solar position equations (the same maths as NOAA's
 * Solar Calculator). Accurate to a fraction of a degree, which is what makes sunset, civil twilight
 * (-6°) and blue hour land at the right minute for your exact location and date.
 */
object Solar {
    data class Position(val elevation: Double, val azimuth: Double) // degrees; azimuth clockwise from north

    fun position(latitude: Double, longitude: Double, at: ZonedDateTime): Position {
        val utc = at.withZoneSameInstant(ZoneOffset.UTC)
        val jd = utc.toLocalDate().toEpochDay() + 2440587.5 + (utc.toLocalTime().toSecondOfDay()) / 86400.0
        val t = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val obliq0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = obliq0 + 0.00256 * cos(Math.toRadians(omega))
        val decl = Math.toDegrees(asin(sin(Math.toRadians(obliq)) * sin(Math.toRadians(appLong))))
        val y = tan(Math.toRadians(obliq / 2)).let { it * it }
        val l0r = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) - 0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr),
        )
        val minutes = utc.toLocalTime().toSecondOfDay() / 60.0
        val trueSolar = (minutes + eqTime + 4 * longitude).mod(1440.0)
        val hourAngle = if (trueSolar / 4 < 0) trueSolar / 4 + 180 else trueSolar / 4 - 180
        val latR = Math.toRadians(latitude); val declR = Math.toRadians(decl); val haR = Math.toRadians(hourAngle)
        val zenith = Math.toDegrees(acos((sin(latR) * sin(declR) + cos(latR) * cos(declR) * cos(haR)).coerceIn(-1.0, 1.0)))
        var elevation = 90 - zenith
        // Atmospheric refraction lifts the sun slightly near the horizon.
        elevation += refraction(elevation)
        val az = Math.toDegrees(atan2(sin(haR), cos(haR) * sin(latR) - tan(declR) * cos(latR))) + 180
        return Position(elevation, az.mod(360.0))
    }

    private fun refraction(e: Double): Double {
        if (e > 85) return 0.0
        val te = tan(Math.toRadians(e))
        val arcsec = when {
            e > 5 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            e > -0.575 -> 1735 + e * (-518.2 + e * (103.4 + e * (-12.79 + e * 0.711)))
            else -> -20.774 / te
        }
        return arcsec / 3600
    }
}
