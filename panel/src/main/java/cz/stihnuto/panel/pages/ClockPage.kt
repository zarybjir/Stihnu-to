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
 * 10 hodin (ikona, teplota, šance na déšť, mm, vítr) a pod časem souhrn dne. Zároveň vzor pro nové okno.
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
            val h = height.toFloat()
            val w = width.toFloat()
            val pad = h * 0.06f
            val now = LocalDateTime.now()
            val app = App.instance
            val wx = app.weather.data

            // --- vpravo úzký sloupec s předpovědí
            val hrs = wx?.hours?.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.take(10)
            val anyRain = hrs?.any { it.precipProb >= 20 || it.precip >= 0.1 } == true
            val colW = if (hrs.isNullOrEmpty()) 0f else h * (if (anyRain) 0.56f else 0.395f)
            val r1 = w - pad * 0.9f
            val r0 = r1 - colW
            val t0 = pad * 0.5f
            val t1 = h - pad * 0.9f

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
                val time = if (next.allDay) "celý den" else if (next.start.isBefore(now)) "teď" else Ui.time(next.start)
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
                val sun = if (d.sunset != null && now.isBefore(d.sunset)) "západ" to Ui.time(d.sunset)
                else "východ" to (wx.days.getOrNull(1)?.sunrise?.let(Ui::time) ?: "–")
                val info = listOf(
                    "dnes" to "${Ui.deg(d.max)} / ${Ui.deg(d.min)}",
                    "srážky" to "${Ui.num(d.precipSum)} mm",
                    "vítr" to "až ${Math.round(d.windMax)} km/h",
                    sun,
                )
                val iw = (leftR - leftL) / info.size
                // svisle doprostřed mezi čas (nebo událost) a spodní okraj
                val top = if (app.calendar.upcoming().isNotEmpty()) chipY + h * 0.045f else base
                val bottom = h * 0.93f
                val iy = top + (bottom - top - h * 0.1f) / 2 + h * 0.038f
                // písmo hodnot se zmenší, kdyby se vedle sebe nevešly
                var vs = h * 0.058f
                value2.textSize = vs
                while (info.maxOf { value2.measureText(it.second) } > iw * 0.92f && vs > h * 0.03f) {
                    vs *= 0.95f; value2.textSize = vs
                }
                info.forEachIndexed { i, (label, value) ->
                    val x = leftL + iw * i + iw / 2
                    small.textSize = h * 0.038f
                    c.drawText(label, x, iy, small)
                    c.drawText(value, x, iy + h * 0.065f, value2)
                }
            }

            // --- předpověď na 10 hodin
            if (hrs.isNullOrEmpty()) return
            fill.color = Ui.CARD
            c.drawRoundRect(RectF(r0, t0, r1, t1), h * 0.04f, h * 0.04f, fill)
            val rowH = (t1 - t0 - h * 0.016f) / hrs.size
            hrs.forEachIndexed { i, hr ->
                val y = t0 + h * 0.008f + i * rowH
                val m = y + rowH / 2
                if (i == 0) {
                    fill.color = Ui.CARD_HI
                    c.drawRoundRect(RectF(r0 + h * 0.008f, y + h * 0.002f, r1 - h * 0.008f, y + rowH - h * 0.002f),
                        rowH / 2, rowH / 2, fill)
                }
                if (hr.time.hour == 0 && i > 0) {
                    fill.color = Ui.FAINT
                    c.drawRect(r0 + h * 0.02f, y, r1 - h * 0.02f, y + 1.5f, fill)
                }
                hourLabel.textSize = h * 0.042f
                hourLabel.color = if (i == 0) Ui.TEXT else Ui.DIM
                c.drawText(if (i == 0) "teď" else "${hr.time.hour}", r0 + h * 0.028f, m + h * 0.015f, hourLabel)
                WeatherIcon.draw(c, hr.code, hr.isDay, r0 + h * 0.135f, m, h * 0.062f)
                temp.textSize = h * 0.048f
                c.drawText(Ui.deg(hr.temp), r0 + h * 0.26f, m + h * 0.017f, temp)
                mm.textSize = h * 0.035f
                c.drawText("${Math.round(hr.wind)}", r0 + h * 0.35f, m + h * 0.013f, mm)
                if (anyRain && (hr.precipProb >= 20 || hr.precip >= 0.1)) {
                    rain.textSize = h * 0.035f
                    c.drawText("${hr.precipProb}%", r0 + h * 0.455f, m + h * 0.013f, rain)
                    mm.textSize = h * 0.032f
                    c.drawText(Ui.num(hr.precip), r1 - h * 0.022f, m + h * 0.012f, mm)
                }
            }
            legend.textSize = h * 0.022f
            c.drawText(if (anyRain) "°C · vítr km/h · déšť · mm" else "°C · vítr km/h", r1 - h * 0.022f, t1 + h * 0.032f, legend)
        }
    }
}
