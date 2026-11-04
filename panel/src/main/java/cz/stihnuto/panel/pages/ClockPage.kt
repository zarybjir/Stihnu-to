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
 * datum, pod ním nejbližší událost. Vpravo úzký sloupec s předpovědí na
 * 8 hodin (ikona, šance na déšť, mm, teplota). Zároveň vzor pro nové okno.
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
        private val rain = Ui.paint(Ui.BLUE, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val mm = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.CENTER)
        private val temp = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.RIGHT)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val pad = h * 0.06f
            val now = LocalDateTime.now()
            val app = App.instance
            val wx = app.weather.data

            // --- vpravo úzký sloupec s předpovědí
            val colW = w * 0.235f
            val r1 = w - pad * 0.5f
            val r0 = r1 - colW
            val t0 = pad * 0.7f
            val t1 = h - pad * 0.7f

            // --- vlevo čas, velikost podle dostupné šířky (číslice Roboto mají stejnou šířku)
            val leftR = r0 - pad * 0.35f
            val leftL = pad * 0.25f
            val cx = leftL + (leftR - leftL) / 2
            val hh = "${now.hour}"
            val mmText = ":%02d".format(now.minute)
            var size = h * 0.6f
            fun total(): Float {
                hours.textSize = size; minutes.textSize = size
                return hours.measureText("00") + minutes.measureText(":00")
            }
            while (total() > leftR - leftL - pad * 0.25f && size > 10) size *= 0.99f
            val cap = size * 0.711f
            val base = h * 0.5f + cap / 2
            val realW = hours.measureText(hh) + minutes.measureText(mmText)
            val x0 = cx - realW / 2
            c.drawText(hh, x0, base, hours)
            c.drawText(mmText, x0 + hours.measureText(hh), base, minutes)

            date.textSize = h * 0.06f
            c.drawText(Ui.dateLong(now.toLocalDate()).replaceFirstChar { it.uppercase() }, cx, base - cap - h * 0.065f, date)

            // --- nejbližší událost
            app.calendar.upcoming().firstOrNull()?.let { next ->
                val today = LocalDate.now()
                val day = when (next.start.toLocalDate()) {
                    today -> ""
                    today.plusDays(1) -> "zítra "
                    else -> "${Ui.dayShort(next.start.toLocalDate())} "
                }
                val time = if (next.allDay) "celý den" else if (next.start.isBefore(now)) "teď" else Ui.time(next.start)
                chip.textSize = h * 0.045f
                var t = "$day$time · ${next.title}"
                while (chip.measureText(t) > (leftR - leftL) * 0.85f && t.length > 4) t = t.dropLast(2) + "…"
                val pw = chip.measureText(t) + h * 0.085f
                val cy = base + h * 0.095f
                val l = cx - pw / 2
                fill.color = Ui.CARD_HI
                c.drawRoundRect(RectF(l, cy - h * 0.04f, l + pw, cy + h * 0.04f), h * 0.04f, h * 0.04f, fill)
                fill.color = Ui.PRIMARY
                c.drawCircle(l + h * 0.033f, cy, h * 0.009f, fill)
                c.drawText(t, l + h * 0.056f, cy + h * 0.016f, chip)
            }

            // --- předpověď na 8 hodin
            val hrs = wx?.hours?.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.take(8)
            if (hrs.isNullOrEmpty()) return
            fill.color = Ui.CARD
            c.drawRoundRect(RectF(r0, t0, r1, t1), h * 0.045f, h * 0.045f, fill)
            val rowH = (t1 - t0 - h * 0.02f) / hrs.size
            hrs.forEachIndexed { i, hr ->
                val y = t0 + h * 0.01f + i * rowH
                val m = y + rowH / 2
                if (i == 0) {
                    fill.color = Ui.CARD_HI
                    c.drawRoundRect(RectF(r0 + h * 0.01f, y + h * 0.003f, r1 - h * 0.01f, y + rowH - h * 0.003f),
                        h * 0.035f, h * 0.035f, fill)
                }
                hourLabel.textSize = h * 0.04f
                hourLabel.color = if (i == 0) Ui.TEXT else Ui.DIM
                c.drawText(if (i == 0) "teď" else "${hr.time.hour}", r0 + h * 0.035f, m + h * 0.014f, hourLabel)
                WeatherIcon.draw(c, hr.code, hr.isDay, r0 + colW * 0.36f, m, h * 0.062f)
                if (hr.precipProb >= 20 || hr.precip >= 0.1) {
                    rain.textSize = h * 0.03f
                    c.drawText("${hr.precipProb} %", r0 + colW * 0.63f, m - h * 0.002f, rain)
                    mm.textSize = h * 0.026f
                    c.drawText("${Ui.num(hr.precip)} mm", r0 + colW * 0.63f, m + h * 0.028f, mm)
                }
                temp.textSize = h * 0.046f
                c.drawText(Ui.deg(hr.temp), r1 - h * 0.03f, m + h * 0.016f, temp)
            }
        }
    }
}
