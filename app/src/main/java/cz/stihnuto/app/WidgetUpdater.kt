package cz.stihnuto.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object WidgetUpdater {
    const val ACTION_REFRESH = "cz.stihnuto.app.action.REFRESH"
    const val ACTION_RERENDER = "cz.stihnuto.app.action.RERENDER"

    private const val ROWS = 5
    private const val GRACE_MS = 30_000L          // keep a departure visible 30 s after its time
    private const val MIN_FETCH_GAP_MS = 20_000L  // don't hit the API more often than this
    private const val STALE_MS = 3 * 60_000L

    private val rowIds = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4)
    private val badgeIds = intArrayOf(R.id.badge0, R.id.badge1, R.id.badge2, R.id.badge3, R.id.badge4)
    private val headIds = intArrayOf(R.id.head0, R.id.head1, R.id.head2, R.id.head3, R.id.head4)
    private val delayIds = intArrayOf(R.id.delay0, R.id.delay1, R.id.delay2, R.id.delay3, R.id.delay4)
    private val timeIds = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4)
    private val cdIds = intArrayOf(R.id.cd0, R.id.cd1, R.id.cd2, R.id.cd3, R.id.cd4)

    private val C_TEXT = 0xFFF1EDE4.toInt()
    private val C_MUTED = 0xFF8A94A0.toInt()
    private val C_OK = 0xFF5FBE83.toInt()
    private val C_TIGHT = 0xFFE3A23E.toInt()
    private val C_MISS = 0xFFE5626B.toInt()

    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun widgetIds(c: Context): IntArray =
        AppWidgetManager.getInstance(c)
            .getAppWidgetIds(ComponentName(c, DepartureWidget::class.java))

    /** Redraw every widget from cached data. No network. */
    fun renderAll(c: Context, status: String? = null) {
        val m = AppWidgetManager.getInstance(c)
        for (id in widgetIds(c)) render(c, m, id, status)
        scheduleRerender(c)
    }

    /**
     * Fetch fresh departures for every stop shown in a widget, then redraw.
     * Blocking — call off the main thread.
     */
    fun refreshBlocking(c: Context, force: Boolean) {
        val ids = widgetIds(c)
        if (ids.isEmpty()) return
        val key = Store.apiKey(c)
        if (key.isEmpty()) {
            renderAll(c)
            return
        }
        if (force) renderAll(c, "načítám…")

        val stops = ids.toList().mapNotNull { Store.card(c, Store.widgetCard(c, it))?.stopName }.distinct()
        val now = System.currentTimeMillis()
        var error: String? = null
        for (stop in stops) {
            val cached = Store.departures(c, stop)
            if (!force && cached != null && now - cached.fetchedAt < MIN_FETCH_GAP_MS) continue
            try {
                Store.saveDepartures(c, stop, Golemio.fetchDepartures(key, stop))
            } catch (e: Exception) {
                error = e.message ?: "chyba sítě"
            }
        }
        renderAll(c, error?.let { "chyba: $it" })
    }

    /** True when some widget is running out of departures and its data is old. */
    fun needsData(c: Context): Boolean {
        val now = System.currentTimeMillis()
        for (id in widgetIds(c)) {
            val card = Store.card(c, Store.widgetCard(c, id)) ?: continue
            val cache = Store.departures(c, card.stopName)
            val stale = cache == null || now - cache.fetchedAt > STALE_MS
            if (stale && visible(card, cache, now).size < 2) return true
        }
        return false
    }

    fun render(c: Context, m: AppWidgetManager, widgetId: Int, status: String?) {
        val v = RemoteViews(c.packageName, R.layout.widget)
        val open = openAppIntent(c)
        v.setOnClickPendingIntent(R.id.refresh, refreshIntent(c))
        v.setOnClickPendingIntent(R.id.header, open)
        v.setOnClickPendingIntent(R.id.rows, open)
        v.setOnClickPendingIntent(R.id.message, open)

        val card = Store.card(c, Store.widgetCard(c, widgetId))
        if (card == null) {
            v.setTextViewText(R.id.title, c.getString(R.string.app_name))
            v.setTextViewText(R.id.subtitle, "")
            message(
                v,
                if (Store.cards(c).isEmpty()) "Otevři aplikaci a vytvoř si kartu se zastávkou."
                else "Karta už neexistuje. Podrž widget a vyber jinou."
            )
            hideRowsFrom(v, 0)
            m.updateAppWidget(widgetId, v)
            return
        }

        val now = System.currentTimeMillis()
        val cache = Store.departures(c, card.stopName)
        val updated = if (cache != null) "aktualizováno " + clock(cache.fetchedAt) else "zatím bez dat"
        v.setTextViewText(R.id.title, card.name)
        v.setTextViewText(R.id.subtitle, card.stopName + " · " + (status ?: updated))

        if (Store.apiKey(c).isEmpty()) {
            message(v, "Chybí Golemio API klíč — vlož ho v aplikaci (⚙).")
            hideRowsFrom(v, 0)
            m.updateAppWidget(widgetId, v)
            return
        }

        val list = visible(card, cache, now)
        if (list.isEmpty()) {
            message(
                v,
                if (cache == null) "Klepni na ↻ pro načtení odjezdů."
                else "V načtených datech už nic dalšího nejede. Klepni na ↻."
            )
        } else {
            v.setViewVisibility(R.id.message, View.GONE)
        }

        for (i in list.indices) {
            val d = list[i]
            v.setViewVisibility(rowIds[i], View.VISIBLE)
            v.setTextViewText(badgeIds[i], d.line)
            v.setInt(badgeIds[i], "setBackgroundColor", lineColor(d.line))
            v.setTextViewText(headIds[i], d.headsign)
            v.setTextViewText(timeIds[i], clock(d.timeMs))
            val (delayText, delayColor) = delayInfo(d)
            v.setTextViewText(delayIds[i], delayText)
            v.setTextColor(delayIds[i], delayColor)
            // Countdown ticks inside the launcher — no wake-ups needed from us.
            val base = SystemClock.elapsedRealtime() + (d.timeMs - now)
            v.setChronometer(cdIds[i], base, null, true)
            v.setChronometerCountDown(cdIds[i], true)
            v.setTextColor(cdIds[i], if (d.canceled) C_MISS else C_TEXT)
        }
        hideRowsFrom(v, list.size)
        m.updateAppWidget(widgetId, v)
    }

    private fun visible(card: Card, cache: StopDepartures?, now: Long): List<Departure> {
        if (cache == null) return emptyList()
        return cache.items
            .filter { !card.excludedKeys.contains(it.key) && it.timeMs > now - GRACE_MS }
            .take(ROWS)
    }

    /**
     * When the first shown departure has gone, redraw from cache so the list
     * moves on. Inexact alarm — fine for this, and needs no extra permission.
     */
    private fun scheduleRerender(c: Context) {
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        for (id in widgetIds(c)) {
            val card = Store.card(c, Store.widgetCard(c, id)) ?: continue
            val first = visible(card, Store.departures(c, card.stopName), now).firstOrNull() ?: continue
            next = minOf(next, first.timeMs + GRACE_MS + 1_000L)
        }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            c, 2,
            Intent(c, DepartureWidget::class.java).setAction(ACTION_RERENDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (next == Long.MAX_VALUE) {
            am.cancel(pi)
        } else {
            am.set(AlarmManager.RTC, maxOf(next, now + 15_000L), pi)
        }
    }

    private fun message(v: RemoteViews, text: String) {
        v.setTextViewText(R.id.message, text)
        v.setViewVisibility(R.id.message, View.VISIBLE)
    }

    private fun hideRowsFrom(v: RemoteViews, from: Int) {
        for (i in from until ROWS) v.setViewVisibility(rowIds[i], View.GONE)
    }

    private fun openAppIntent(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun refreshIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 1,
            Intent(c, DepartureWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun clock(ms: Long): String =
        hhmm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** Same rules as delayInfo() in the web app. */
    private fun delayInfo(d: Departure): Pair<String, Int> {
        if (d.canceled) return "zrušeno" to C_MISS
        val sec = d.delaySec
        if (!d.tracked || sec == null) return "JŘ" to C_MUTED
        val min = (sec / 60.0).roundToInt()
        return when {
            min <= 0 -> "včas" to C_OK
            min <= 2 -> "+$min" to C_OK
            min <= 5 -> "+$min" to C_TIGHT
            else -> "+$min" to C_MISS
        }
    }

    /** Same colours as lineColor() in the web app. */
    private fun lineColor(line: String): Int {
        val l = line.trim().uppercase()
        if (l == "A") return 0xFF3CB54A.toInt()
        if (l == "B") return 0xFFF0AB00.toInt()
        if (l == "C") return 0xFFE31E24.toInt()
        if (l.startsWith("S") || l.startsWith("R")) return 0xFF2E7D5B.toInt()
        val n = Regex("^\\d+").find(l)?.value?.toIntOrNull()
        if (n != null) {
            if (n in 90..99) return 0xFF7C6BA8.toInt()
            if (n in 1..89) return 0xFFC8102E.toInt()
            if (n >= 900) return 0xFF3D4A66.toInt()
            if (n >= 100) return 0xFF0079C2.toInt()
        }
        return 0xFF4FB8A9.toInt()
    }
}
