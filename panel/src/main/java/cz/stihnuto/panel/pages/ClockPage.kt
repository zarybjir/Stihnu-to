package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Hlavní okno: vlevo co největší čas (hodiny modře, minuty bíle), nad ním
 * datum, pod ním nejbližší událost. Vpravo úzký sloupec s předpovědí (ikona,
 * teplota, šance na déšť, mm), rolovací prstem až na 36 hodin, a pod časem souhrn dne.
 */
class ClockPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val canvasView = ClockView(ctx)
    override val view: View = canvasView

    private val tick = object : Runnable {
        override fun run() {
            canvasView.invalidate()
            app.main.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }
    private val onData = { canvasView.invalidate() }

    override fun onShow() {
        app.main.post(tick)
        app.weather.listen(onData)
        app.calendar.listen(onData)
    }

    override fun onHide() {
        app.main.removeCallbacks(tick)
        app.weather.unlisten(onData)
        app.calendar.unlisten(onData)
    }

    private class ClockView(ctx: Context) : View(ctx) {
        private val hours = Ui.paint(Ui.PRIMARY, 1f, Ui.BLACK)
        private val minutes = Ui.paint(Ui.TEXT, 1f, Ui.BLACK)
        private val date = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val chip = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM)
        private val hourLabel = Ui.paint(Ui.DIM, 1f, Ui.MEDIUM)
        private val rain = Ui.paint(Ui.BLUE, 1f, Ui.MEDIUM, Paint.Align.RIGHT)
        private val mm = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.RIGHT)
        private val small = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.CENTER)
        private val value2 = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val legend = Ui.paint(Ui.FAINT, 1f, Ui.REGULAR, Paint.Align.RIGHT)
        private val temp = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.RIGHT)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val app = App.instance
            val h = height.toFloat()
            // s horní lištou je obrazovka nižší — velikost hodinovky ale drž jako bez ní
            val hu = h + (if (app.settings.topBar) Ui.unit(context) * 12f else 0f)
            val w = width.toFloat()
            val pad = h * 0.06f
            val now = LocalDateTime.now()
            val wx = app.weather.data

            // --- vpravo úzký sloupec s předpovědí (až 36 h, rolovací)
            val allH = wx?.hours?.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.take(36)
            totalHrs = allH?.size ?: 0
            val visible = if (app.settings.topBar) 9 else 10
            val maxOff = maxOf(0, totalHrs - visible)
            if (hourOffset > maxOff) hourOffset = maxOff
            if (hourOffset < 0) hourOffset = 0
            val hrs = allH?.drop(hourOffset)?.take(visible)
            // šance na déšť počítej z celého okna, ať se sloupec při rolování neškube na šířce
            val anyRain = allH?.any { it.precipProb >= 20 || it.precip >= 0.1 } == true
            val colW = if (hrs.isNullOrEmpty()) 0f else hu * (if (anyRain) 0.47f else 0.31f)
            val r1 = w - pad * 0.3f
            val r0 = r1 - colW
            val t0 = pad * 0.5f
            val t1 = h - pad * 0.9f

            // --- vlevo čas, velikost podle dostupné šířky (číslice Roboto mají stejnou šířku)
            val leftR = r0 - pad * 0.25f
            val leftL = pad * 0.1f
            val cx = leftL + (leftR - leftL) / 2
            val hh = "${now.hour}"
            val mmText = ":%02d".format(now.minute)
            var size = h * 0.66f
            fun total(): Float {
                hours.textSize = size; minutes.textSize = size
                return hours.measureText("00") + minutes.measureText(":00")
            }
            while (total() > leftR - leftL - pad * 0.25f && size > 10) size *= 0.99f
            val cap = size * 0.711f
            val base = h * 0.43f + cap / 2
            val realW = hours.measureText(hh) + minutes.measureText(mmText)
            val x0 = cx - realW / 2
            c.drawText(hh, x0, base, hours)
            c.drawText(mmText, x0 + hours.measureText(hh), base, minutes)

            date.textSize = h * 0.06f
            c.drawText(Ui.dateLong(now.toLocalDate()).replaceFirstChar { it.uppercase() }, cx, base - cap - h * 0.065f, date)

            // --- nejbližší událost
            val chipY = base + h * 0.095f
            app.calendar.upcoming().firstOrNull()?.let { next ->
                val today = LocalDate.now()
                val day = when (next.start.toLocalDate()) {
                    today -> ""
                    today.plusDays(1) -> "zítra "
                    else -> "${Ui.dayShort(next.start.toLocalDate())} "
                }
                val time = if (next.allDay) "celý den" else if (next.start.isBefore(now)) "probíhá" else Ui.time(next.start)
                chip.textSize = h * 0.045f
                var t = "$day$time · ${next.title}"
                while (chip.measureText(t) > (leftR - leftL) * 0.85f && t.length > 4) t = t.dropLast(2) + "…"
                val pw = chip.measureText(t) + h * 0.085f
                val cy = chipY
                val l = cx - pw / 2
                fill.color = Ui.CARD_HI
                c.drawRoundRect(RectF(l, cy - h * 0.04f, l + pw, cy + h * 0.04f), h * 0.04f, h * 0.04f, fill)
                fill.color = Ui.PRIMARY
                c.drawCircle(l + h * 0.033f, cy, h * 0.009f, fill)
                c.drawText(t, l + h * 0.056f, cy + h * 0.016f, chip)
            }

            // --- souhrn dne pod časem
            wx?.days?.firstOrNull()?.let { d ->
                val info = listOf(
                    "dnes" to "${Ui.deg(d.max)} / ${Ui.deg(d.min)}",
                    "srážky" to "${Ui.num(d.precipSum)} mm",
                    "vítr" to "až ${Math.round(d.windMax)} km/h",
                )
                val iw = (leftR - leftL) / info.size
                // svisle doprostřed mezi čas (nebo událost) a spodní okraj
                val top = if (app.calendar.upcoming().isNotEmpty()) chipY + h * 0.045f else base
                val bottom = h * 0.93f
                val labelSize = h * 0.046f
                val iy = top + (bottom - top - h * 0.12f) / 2 + h * 0.046f
                // hodnoty větší, ať vyplní uvolněné místo; zmenší se, jen kdyby se nevešly
                var vs = h * 0.078f
                value2.textSize = vs
                while (info.maxOf { value2.measureText(it.second) } > iw * 0.92f && vs > h * 0.04f) {
                    vs *= 0.95f; value2.textSize = vs
                }
                info.forEachIndexed { i, (label, value) ->
                    val x = leftL + iw * i + iw / 2
                    small.textSize = labelSize
                    c.drawText(label, x, iy, small)
                    c.drawText(value, x, iy + h * 0.082f, value2)
                }
            }

            // --- rolovací sloupec s předpovědí
            if (hrs.isNullOrEmpty()) return
            fill.color = Ui.CARD
            c.drawRoundRect(RectF(r0, t0, r1, t1), h * 0.04f, h * 0.04f, fill)
            val rowH = (t1 - t0 - h * 0.016f) / hrs.size
            colLeft = r0; rowPx = rowH // pro rolování prstem
            hrs.forEachIndexed { i, hr ->
                val nowRow = hourOffset == 0 && i == 0
                val y = t0 + h * 0.008f + i * rowH
                val m = y + rowH / 2
                if (nowRow) {
                    fill.color = Ui.CARD_HI
                    c.drawRoundRect(RectF(r0 + h * 0.008f, y + h * 0.002f, r1 - h * 0.008f, y + rowH - h * 0.002f),
                        rowH / 2, rowH / 2, fill)
                }
                if (hr.time.hour == 0 && !nowRow) {
                    fill.color = Ui.FAINT
                    c.drawRect(r0 + h * 0.02f, y, r1 - h * 0.02f, y + 1.5f, fill)
                }
                hourLabel.textSize = hu * 0.042f
                hourLabel.color = if (nowRow) Ui.TEXT else Ui.DIM
                c.drawText(if (nowRow) "nyní" else "${hr.time.hour}", r0 + hu * 0.028f, m + hu * 0.015f, hourLabel)
                WeatherIcon.draw(c, hr.code, hr.isDay, r0 + hu * 0.135f, m, hu * 0.062f)
                temp.textSize = hu * 0.048f
                c.drawText(Ui.deg(hr.temp), r0 + hu * 0.26f, m + hu * 0.017f, temp)
                if (anyRain && (hr.precipProb >= 20 || hr.precip >= 0.1)) {
                    rain.textSize = hu * 0.035f
                    c.drawText("${hr.precipProb}%", r0 + hu * 0.365f, m + hu * 0.013f, rain)
                    mm.textSize = hu * 0.032f
                    c.drawText(Ui.num(hr.precip), r1 - hu * 0.022f, m + hu * 0.012f, mm)
                }
            }
            // náznak rolování: šipky, když je nahoře/dole víc hodin
            fill.color = Ui.FAINT
            val cxh = (r0 + r1) / 2
            if (hourOffset > 0) caret(c, cxh, t0 + h * 0.022f, hu * 0.02f, true)
            if (hourOffset < maxOff) caret(c, cxh, t1 - h * 0.022f, hu * 0.02f, false)
        }

        private fun caret(c: Canvas, cx: Float, cy: Float, r: Float, up: Boolean) {
            val p = android.graphics.Path()
            val d = if (up) -1f else 1f
            p.moveTo(cx - r, cy - r * 0.5f * d); p.lineTo(cx, cy + r * 0.5f * d); p.lineTo(cx + r, cy - r * 0.5f * d)
            fill.style = Paint.Style.STROKE; fill.strokeWidth = r * 0.4f; fill.color = Ui.DIM
            c.drawPath(p, fill); fill.style = Paint.Style.FILL
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (totalHrs <= visibleRows() || e.x < colLeft) return false // mimo sloupec / není co rolovat → nech projet
                    downY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true) // ať přejetí po sloupci nepřepne okno
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val steps = ((downY - e.y) / rowPx).toInt()
                    if (steps != 0) {
                        hourOffset = (hourOffset + steps).coerceIn(0, maxOf(0, totalHrs - visibleRows()))
                        downY = e.y
                        invalidate()
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        private fun visibleRows() = if (App.instance.settings.topBar) 9 else 10

        private var hourOffset = 0
        private var totalHrs = 0
        private var colLeft = 1e9f
        private var rowPx = 1f
        private var downY = 0f
    }
}
