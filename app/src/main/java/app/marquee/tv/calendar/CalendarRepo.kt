package app.marquee.tv.calendar

import app.marquee.tv.data.SettingsStore
import app.marquee.tv.net.Http
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class CalEvent(
    val uid: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val location: String,
) {
    /** A physical place worth routing to (not a video-call link). */
    val needsTravel: Boolean
        get() {
            val l = location.lowercase()
            if (l.isBlank()) return false
            return listOf("http", "zoom", "meet.google", "teams.microsoft", "webex", "online", "virtual", "call").none { l.contains(it) }
        }
}

/**
 * Reads calendars from their private iCal (ICS) addresses. In Google Calendar:
 * Settings > (calendar) > Integrate calendar > "Secret address in iCal format".
 * This avoids Google's sign-in, which does not allow calendar access from TV-style devices.
 */
class CalendarRepo(private val store: SettingsStore) {
    val configured get() = store.value.calendarUrls.isNotEmpty()

    suspend fun upcoming(days: Long = 35): List<CalEvent> {
        val zone = ZoneId.systemDefault()
        val from = ZonedDateTime.now(zone).minusHours(12)
        val to = from.plusDays(days)
        return store.value.calendarUrls.flatMap { url ->
            runCatching { IcsParser.parse(Http.getText(url.replace("webcal://", "https://"), cacheMinutes = 10), zone, from, to) }.getOrDefault(emptyList())
        }.sortedBy { it.start }
    }
}

/** Minimal iCalendar reader: events, time zones, all-day dates, common recurrence rules, exceptions. */
object IcsParser {
    // ---------------- ICS parsing ----------------
    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    fun parse(ics: String, zone: ZoneId, from: ZonedDateTime, to: ZonedDateTime): List<CalEvent> {
        val lines = unfold(ics)
        val blocks = mutableListOf<List<Prop>>()
        var cur: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { cur = mutableListOf(); depth = 1 }
                line.startsWith("BEGIN:") && cur != null -> depth++
                line == "END:VEVENT" -> { cur?.let { blocks += it }; cur = null }
                line.startsWith("END:") && cur != null -> depth--
                cur != null && depth == 1 -> parseProp(line)?.let { cur.add(it) }
            }
        }
        val overrides = mutableSetOf<String>() // uid@start of moved/edited occurrences
        val out = mutableListOf<CalEvent>()
        val masters = mutableListOf<List<Prop>>()
        for (b in blocks) {
            val rid = b.firstOrNull { it.name == "RECURRENCE-ID" }
            if (rid != null) {
                val uid = b.firstOrNull { it.name == "UID" }?.value ?: ""
                parseTime(rid, zone)?.let { overrides += "$uid@${it.first.toEpochSecond()}" }
                toEvent(b, zone)?.let { if (it.end.isAfter(from) && it.start.isBefore(to)) out += it.local(zone) }
            } else masters += b
        }
        for (b in masters) {
            if (b.firstOrNull { it.name == "STATUS" }?.value == "CANCELLED") continue
            val base = toEvent(b, zone) ?: continue
            val rrule = b.firstOrNull { it.name == "RRULE" }?.value
            val exdates = b.filter { it.name == "EXDATE" }.flatMap { p ->
                p.value.split(',').mapNotNull { v -> parseTime(p.copy(value = v), zone)?.first?.toEpochSecond() }
            }.toSet()
            val starts = if (rrule == null) listOf(base.start) else expand(base.start, rrule, to)
            val dur = Duration.between(base.start, base.end)
            for (st in starts) {
                val key = "${base.uid}@${st.toEpochSecond()}"
                if (st.toEpochSecond() in exdates || key in overrides) continue
                val ev = base.copy(start = st, end = st.plus(dur))
                if (ev.end.isAfter(from) && ev.start.isBefore(to)) out += ev.local(zone)
            }
        }
        return out
    }

    private fun CalEvent.local(zone: ZoneId) = copy(start = start.withZoneSameInstant(zone), end = end.withZoneSameInstant(zone))

    private fun unfold(ics: String): List<String> {
        val raw = ics.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val out = ArrayList<String>(raw.size)
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + l.substring(1)
            else if (l.isNotEmpty()) out += l
        }
        return out
    }

    private fun parseProp(line: String): Prop? {
        // Value starts at the first ':' that is not inside a quoted parameter.
        var inQuote = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(v: String) = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** Returns the instant and whether it is an all-day date. */
    private fun parseTime(p: Prop, zone: ZoneId): Pair<ZonedDateTime, Boolean>? = runCatching {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) {
            LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone) to true
        } else {
            val ldt = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val z = when {
                v.endsWith("Z") -> ZoneOffset.UTC
                p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(zone)
                else -> zone
            }
            // Keep the event's own zone so recurrences follow its DST rules; converted to local at the end.
            ldt.atZone(z) to false
        }
    }.getOrNull()

    private fun toEvent(b: List<Prop>, zone: ZoneId): CalEvent? {
        val startP = b.firstOrNull { it.name == "DTSTART" } ?: return null
        val (start, allDay) = parseTime(startP, zone) ?: return null
        val end = b.firstOrNull { it.name == "DTEND" }?.let { parseTime(it, zone)?.first }
            ?: b.firstOrNull { it.name == "DURATION" }?.let { runCatching { start.plus(Duration.parse(it.value)) }.getOrNull() }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        return CalEvent(
            uid = b.firstOrNull { it.name == "UID" }?.value ?: "${start.toEpochSecond()}",
            title = unescape(b.firstOrNull { it.name == "SUMMARY" }?.value ?: "(No title)"),
            start = start, end = end, allDay = allDay,
            location = unescape(b.firstOrNull { it.name == "LOCATION" }?.value ?: ""),
        )
    }

    /** Expands the common RRULE shapes (DAILY / WEEKLY with BYDAY / MONTHLY / YEARLY, INTERVAL, COUNT, UNTIL). */
    private fun expand(start: ZonedDateTime, rule: String, to: ZonedDateTime): List<ZonedDateTime> {
        val r = rule.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
        val freq = r["FREQ"] ?: return listOf(start)
        val interval = r["INTERVAL"]?.toLongOrNull() ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { u ->
            runCatching {
                if (u.length == 8) LocalDate.parse(u, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59).atZone(start.zone)
                else LocalDateTime.parse(u.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(if (u.endsWith("Z")) ZoneOffset.UTC else start.zone).withZoneSameInstant(start.zone)
            }.getOrNull()
        }
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { dayOf(it.takeLast(2)) }
        val limit = minOf(to, until ?: to)
        val out = mutableListOf<ZonedDateTime>()
        var produced = 0
        fun accept(t: ZonedDateTime): Boolean {
            if (t.isBefore(start)) return true
            if (t.isAfter(limit)) return false
            produced++
            out += t
            return count == null || produced < count
        }
        var guard = 0
        when (freq) {
            "DAILY" -> { var t = start; while (guard++ < 2000 && accept(t)) t = t.plusDays(interval) }
            "WEEKLY" -> {
                val days = byDay?.ifEmpty { null } ?: listOf(start.dayOfWeek)
                var weekStart = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                loop@ while (guard++ < 600) {
                    for (d in days.sortedBy { it.value }) {
                        val t = weekStart.with(TemporalAdjusters.nextOrSame(d)).withHour(start.hour).withMinute(start.minute)
                        if (!accept(t)) break@loop
                    }
                    weekStart = weekStart.plusWeeks(interval)
                    if (weekStart.isAfter(limit)) break
                }
            }
            "MONTHLY" -> { var t = start; while (guard++ < 400 && accept(t)) t = t.plusMonths(interval) }
            "YEARLY" -> { var t = start; while (guard++ < 50 && accept(t)) t = t.plusYears(interval) }
            else -> out += start
        }
        return out.distinct().sorted().filter { ChronoUnit.SECONDS.between(it, limit) >= 0 }
    }

    private fun dayOf(s: String): DayOfWeek? = when (s) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY; "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY; else -> null
    }
}
