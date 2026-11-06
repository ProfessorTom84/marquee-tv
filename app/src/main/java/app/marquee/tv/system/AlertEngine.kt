package app.marquee.tv.system

import app.marquee.tv.calendar.CalEvent
import app.marquee.tv.calendar.CalendarRepo
import app.marquee.tv.data.SettingsStore
import app.marquee.tv.traffic.Drive
import app.marquee.tv.traffic.TrafficRepo
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class UpNext(
    val event: CalEvent,
    val drive: Drive?,
    val leaveBy: ZonedDateTime?,
) {
    fun minutesUntilStart(now: ZonedDateTime = ZonedDateTime.now()) = Duration.between(now, event.start).toMinutes()
    fun minutesUntilLeave(now: ZonedDateTime = ZonedDateTime.now()) = leaveBy?.let { Duration.between(now, it).toMinutes() }
    val key get() = "${event.uid}@${event.start.toEpochSecond()}"
}

enum class AlertKind { LEAVE, STARTING }

object TimeFmt {
    private val t = DateTimeFormatter.ofPattern("h:mm")
    private val ta = DateTimeFormatter.ofPattern("h:mm a")
    fun short(z: ZonedDateTime): String = z.format(t)
    fun full(z: ZonedDateTime): String = z.format(ta)
    fun until(minutes: Long): String = when {
        minutes <= 0 -> "now"
        minutes < 60 -> "in $minutes min"
        else -> "in ${minutes / 60}h ${minutes % 60}m"
    }
}

/** Works out what's next on the calendar and when to leave, using live traffic. */
class AlertEngine(
    private val store: SettingsStore,
    private val calendar: CalendarRepo,
    private val traffic: TrafficRepo,
) {
    private val dismissed = mutableSetOf<String>()
    private val snoozedUntil = mutableMapOf<String, Long>()

    suspend fun upcoming(): List<CalEvent> = if (calendar.configured) calendar.upcoming() else emptyList()

    suspend fun upNext(events: List<CalEvent>? = null): UpNext? {
        val now = ZonedDateTime.now()
        val ev = (events ?: upcoming()).firstOrNull { !it.allDay && it.end.isAfter(now) && it.start.isBefore(now.plusHours(18)) } ?: return null
        val minutes = Duration.between(now, ev.start).toMinutes()
        val drive = if (ev.needsTravel && minutes > -10) traffic.drive(ev.location, maxAgeMinutes = if (minutes < 120) 5 else 20) else null
        val leave = drive?.let { ev.start.minusMinutes((it.minutes + store.value.travelBufferMinutes).toLong()) }
        return UpNext(ev, drive, leave)
    }

    fun alertFor(u: UpNext, now: ZonedDateTime = ZonedDateTime.now()): AlertKind? {
        if (u.key in dismissed) return null
        snoozedUntil[u.key]?.let { if (System.currentTimeMillis() < it) return null }
        val leaveIn = u.minutesUntilLeave(now)
        return when {
            leaveIn != null && leaveIn <= 5 && u.minutesUntilStart(now) > -5 -> AlertKind.LEAVE
            leaveIn == null && u.minutesUntilStart(now) in 0..5 -> AlertKind.STARTING
            else -> null
        }
    }

    fun dismiss(u: UpNext) { dismissed += u.key }
    fun snooze(u: UpNext, minutes: Int = 5) { snoozedUntil[u.key] = System.currentTimeMillis() + minutes * 60_000L }
}
