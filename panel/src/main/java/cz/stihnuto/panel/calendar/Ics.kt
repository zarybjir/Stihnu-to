package cz.stihnuto.panel.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Jedna událost (už rozbalená z opakování) v místním čase. */
class CalEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String,
)

/**
 * Minimální čtečka iCalendar (.ics) — stačí na Google Kalendář, iCloud i Outlook.
 * Umí celodenní události, časová pásma, opakování (denně/týdně/měsíčně/ročně,
 * INTERVAL, COUNT, UNTIL, BYDAY), výjimky (EXDATE) a přesunuté výskyty (RECURRENCE-ID).
 */
object Ics {
    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class Raw(val props: List<Prop>) {
        fun get(name: String) = props.firstOrNull { it.name == name }
        fun all(name: String) = props.filter { it.name == name }
    }

    fun parse(text: String, from: LocalDateTime, to: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        val raws = readEvents(text)
        // přesunuté/změněné výskyty: UID → časy, které se z opakování vynechají
        val overridden = mutableMapOf<String, MutableSet<LocalDateTime>>()
        for (r in raws) {
            val rid = r.get("RECURRENCE-ID") ?: continue
            val uid = r.get("UID")?.value ?: continue
            try {
                overridden.getOrPut(uid) { mutableSetOf() } += toLocal(rid, zone).first
            } catch (e: Exception) {
                // nečitelný čas — záznam přeskočit
            }
        }

        val out = mutableListOf<CalEvent>()
        for (r in raws) try {
            if (r.get("STATUS")?.value.equals("CANCELLED", true)) continue
            val dtStart = r.get("DTSTART") ?: continue
            val (start, allDay) = toLocal(dtStart, zone)
            val end = r.get("DTEND")?.let { toLocal(it, zone).first }
                ?: r.get("DURATION")?.let { start.plus(parseDuration(it.value)) }
                ?: if (allDay) start.plusDays(1) else start
            val length = Duration.between(start, end)
            val title = unescape(r.get("SUMMARY")?.value ?: "(bez názvu)")
            val location = unescape(r.get("LOCATION")?.value ?: "")
            val uid = r.get("UID")?.value ?: ""
            val excluded = r.all("EXDATE").flatMap { p ->
                p.value.split(',').map { toLocal(Prop("EXDATE", p.params, it), zone).first }
            }.toMutableSet()
            if (r.get("RECURRENCE-ID") == null) overridden[uid]?.let { excluded += it }

            val rrule = r.get("RRULE")?.value
            val starts = if (rrule == null) listOf(start) else expand(start, rrule, to, zone)
            for (s in starts) {
                if (s in excluded) continue
                val e = s.plus(length)
                if (e.isAfter(from) && s.isBefore(to) || (s == e && !s.isBefore(from) && s.isBefore(to))) {
                    out += CalEvent(title, s, e, allDay, location)
                }
            }
        } catch (e: Exception) {
            // jedna vadná událost (nečitelné datum apod.) nesmí shodit celý kalendář — přeskočit
        }
        return out.sortedWith(compareBy({ it.start.toLocalDate() }, { !it.allDay }, { it.start }))
    }

    // ------------------------------------------------------------------

    private fun readEvents(text: String): List<Raw> {
        // „rozbalit" zalomené řádky (pokračování začíná mezerou nebo tabulátorem)
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val events = mutableListOf<Raw>()
        var cur: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { cur = mutableListOf(); depth = 0 }
                line == "END:VEVENT" -> { cur?.let { events += Raw(it) }; cur = null }
                cur == null -> {}
                line.startsWith("BEGIN:") -> depth++ // VALARM apod. přeskočit
                line.startsWith("END:") -> depth--
                depth == 0 -> parseLine(line)?.let { cur.add(it) }
            }
        }
        return events
    }

    private fun parseLine(line: String): Prop? {
        // hodnota začíná první dvojtečkou mimo uvozovky
        var inQuote = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    /** Čas z vlastnosti → místní čas; druhá hodnota = celodenní. */
    private fun toLocal(p: Prop, zone: ZoneId): Pair<LocalDateTime, Boolean> {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || (v.length == 8 && v.all(Char::isDigit))) {
            return LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() to true
        }
        val ldt = LocalDateTime.parse(v.removeSuffix("Z").take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val src: ZoneId = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            p.params["TZID"] != null -> try {
                ZoneId.of(p.params["TZID"])
            } catch (e: Exception) {
                zone
            }
            else -> zone
        }
        return ZonedDateTime.of(ldt, src).withZoneSameInstant(zone).toLocalDateTime() to false
    }

    private fun parseDuration(v: String): Duration {
        val m = Regex("""^-?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")
            .find(v.trim()) ?: return Duration.ZERO
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0
        return Duration.ofDays(g(1) * 7 + g(2)).plusHours(g(3)).plusMinutes(g(4)).plusSeconds(g(5))
    }

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    /** Začátky opakované události do [to]. */
    private fun expand(start: LocalDateTime, rule: String, to: LocalDateTime, zone: ZoneId): List<LocalDateTime> {
        val r = rule.split(';').mapNotNull {
            val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1)
        }.toMap()
        val freq = r["FREQ"] ?: return listOf(start)
        val interval = r["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { toLocal(Prop("UNTIL", emptyMap(), it), zone).first }
        val byDay = r["BYDAY"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val limit = minOf(to, until ?: to)

        val out = mutableListOf<LocalDateTime>()
        var produced = 0
        fun add(t: LocalDateTime): Boolean {
            if (t.isBefore(start)) return true
            if (until != null && t.isAfter(until)) return false
            if (count != null && produced >= count) return false
            produced++
            if (!t.isAfter(limit)) out += t
            return true
        }

        var period = 0L
        loop@ while (period < 5000) {
            val base = when (freq) {
                "DAILY" -> start.plusDays(period * interval)
                "WEEKLY" -> start.plusWeeks(period * interval)
                "MONTHLY" -> start.plusMonths(period * interval)
                "YEARLY" -> start.plusYears(period * interval)
                else -> return listOf(start)
            }
            if (base.isAfter(limit.plusDays(7)) && freq != "DAILY") break
            if (base.isAfter(limit) && freq == "DAILY") break
            val candidates: List<LocalDateTime> = when {
                freq == "WEEKLY" && byDay.isNotEmpty() -> {
                    val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    byDay.mapNotNull { DAYS[it.takeLast(2)] }.map { monday.with(TemporalAdjusters.nextOrSame(it)) }.sorted()
                }
                freq == "MONTHLY" && byDay.isNotEmpty() -> byDay.mapNotNull { d ->
                    val dow = DAYS[d.takeLast(2)] ?: return@mapNotNull null
                    val n = d.dropLast(2).toIntOrNull() ?: 1
                    val first = base.withDayOfMonth(1)
                    if (n > 0) first.with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
                    else first.with(TemporalAdjusters.lastInMonth(dow))
                }.sorted()
                else -> listOf(base)
            }
            for (c in candidates) if (!add(c)) break@loop
            if (count != null && produced >= count) break
            period++
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}
