package cz.stihnuto.panel.pages

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.calendar.CalEvent
import java.time.LocalDate
import java.time.LocalDateTime

/** Kalendář: vlevo dnešní datum, vpravo nadcházející události po dnech. */
class CalendarPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val u = Ui.unit(ctx)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((u * 6).toInt(), (u * 6).toInt(), (u * 5).toInt(), 0)
    }
    override val view: View = root

    private val dayNum = Ui.text(ctx, "", u * 30, Ui.PRIMARY, Ui.BLACK)
    private val dayName = Ui.text(ctx, "", u * 7, Ui.TEXT, Ui.MEDIUM)
    private val month = Ui.text(ctx, "", u * 5, Ui.DIM, Ui.MEDIUM)
    private val clock = Ui.text(ctx, "", u * 9, Ui.TEXT, Ui.BLACK)
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (u * 10).toInt())
    }
    private val onChange = { render() }
    private val tick = object : Runnable {
        override fun run() {
            renderHeader()
            app.main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(dayName)
        left.addView(dayNum)
        left.addView(month)
        left.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 6).toInt() })
        root.addView(left, LinearLayout.LayoutParams((u * 52).toInt(), -1))
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun renderHeader() {
        val now = LocalDateTime.now()
        dayName.text = Ui.dayName(now.toLocalDate()).replaceFirstChar { it.uppercase() }
        dayNum.text = "${now.dayOfMonth}"
        month.text = Ui.dateLong(now.toLocalDate()).substringAfter(". ")
        clock.text = Ui.time(now)
    }

    private fun render() {
        renderHeader()
        list.removeAllViews()
        val repo = app.calendar
        if (repo.urls.isEmpty()) {
            list.addView(note("Kalendář ještě není připojený. V nastavení (http://<ip-hodin>:8080) vlož " +
                "tajnou adresu ve formátu iCal z Google Kalendáře."))
            return
        }
        repo.error?.let { list.addView(note(it)) }
        if (!repo.loaded) {
            list.addView(note("Načítám…")); return
        }
        val events = repo.upcoming()
        if (events.isEmpty()) {
            list.addView(note("Žádné události na příštích 14 dní."))
            return
        }
        val today = LocalDate.now()
        events.groupBy { maxOf(it.start.toLocalDate(), today) }.entries.take(10).forEach { (day, evs) ->
            val label = when (day) {
                today -> "Dnes"
                today.plusDays(1) -> "Zítra"
                else -> "${Ui.dayName(day).replaceFirstChar { it.uppercase() }} ${day.dayOfMonth}. ${day.monthValue}."
            }
            list.addView(Ui.text(ctx, label, u * 4.5f, if (day == today) Ui.PRIMARY else Ui.DIM, Ui.MEDIUM),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt(); bottomMargin = (u * 1.5f).toInt() })
            evs.forEach { list.addView(card(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (u * 1.5f).toInt() }) }
        }
    }

    private fun card(e: CalEvent): View {
        val now = LocalDateTime.now()
        val running = !e.allDay && e.start.isBefore(now) && e.end.isAfter(now)
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (running) Ui.CARD_HI else Ui.CARD, u * 3)
            setPadding((u * 3).toInt(), (u * 2.2f).toInt(), (u * 3).toInt(), (u * 2.2f).toInt())
        }
        row.addView(View(ctx).apply { background = Ui.rounded(if (e.allDay) Ui.GOOD else Ui.PRIMARY, u) },
            LinearLayout.LayoutParams((u * 1.2f).toInt(), (u * 8).toInt()))
        val time = when {
            e.allDay -> "celý den"
            running -> "probíhá · do ${Ui.time(e.end)}"
            else -> "${Ui.time(e.start)} – ${Ui.time(e.end)}"
        }
        row.addView(Ui.text(ctx, time, u * 4.2f, Ui.DIM, Ui.MEDIUM),
            LinearLayout.LayoutParams((u * 24).toInt(), -2).apply { leftMargin = (u * 3).toInt() })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, e.title, u * 5f, Ui.TEXT, Ui.MEDIUM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        if (e.location.isNotBlank()) col.addView(Ui.text(ctx, e.location, u * 3.6f, Ui.DIM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun note(t: String) = Ui.text(ctx, t, u * 4.5f, Ui.DIM).apply { setPadding(0, (u * 3).toInt(), 0, 0) }

    override fun onShow() {
        app.calendar.listen(onChange)
        app.calendar.refresh()
        app.main.post(tick)
    }

    override fun onHide() {
        app.calendar.unlisten(onChange)
        app.main.removeCallbacks(tick)
    }
}
