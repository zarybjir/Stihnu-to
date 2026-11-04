package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherApi
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Hlavní okno ve stylu Pixelu: velké tlusté hodiny, nahoře datum a počasí,
 * pod hodinami nejbližší událost a malý pruh s předpovědí na dalších 8 hodin.
 * Zároveň vzor pro nové okno.
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
        private val date = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM)
        private val small = Ui.paint(Ui.DIM, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val temp = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val chip = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM)
        private val rain = Ui.paint(Ui.BLUE, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val pad = h * 0.06f
            val now = LocalDateTime.now()
            val app = App.instance
            val wx = app.weather.data

            // --- nahoře: datum vlevo, počasí vpravo
            date.textSize = h * 0.058f
            val d = Ui.dateLong(now.toLocalDate())
            c.drawText(d.replaceFirstChar { it.uppercase() }, pad, pad + h * 0.05f, date)
            if (wx != null) {
                val cur = wx.current
                chip.textSize = h * 0.058f
                val label = Ui.deg(cur.temp)
                val desc = WeatherApi.describe(cur.code)
                small.textAlign = Paint.Align.RIGHT
                small.textSize = h * 0.04f
                val iconS = h * 0.09f
                val labelW = chip.measureText(label)
                val right = w - pad
                c.drawText(label, right - labelW, pad + h * 0.05f, chip)
                WeatherIcon.draw(c, cur.code, cur.isDay, right - labelW - iconS * 0.65f, pad + h * 0.03f, iconS)
                c.drawText(desc, right, pad + h * 0.105f, small)
                small.textAlign = Paint.Align.CENTER
            }

            // --- hodiny: hodiny modře, minuty bíle, tlusté písmo
            val hh = "${now.hour}"
            val mm = "%02d".format(now.minute)
            var size = h * 0.42f
            hours.textSize = size; minutes.textSize = size
            fun total() = hours.measureText(hh) + minutes.measureText(":$mm")
            while (total() > w - pad * 2 && size > 10) {
                size *= 0.95f; hours.textSize = size; minutes.textSize = size
            }
            val x0 = (w - total()) / 2
            val baseline = h * 0.54f
            c.drawText(hh, x0, baseline, hours)
            c.drawText(":$mm", x0 + hours.measureText(hh), baseline, minutes)

            // --- nejbližší událost
            val next = app.calendar.upcoming().firstOrNull()
            if (next != null) {
                val today = LocalDate.now()
                val day = when (next.start.toLocalDate()) {
                    today -> ""
                    today.plusDays(1) -> "zítra "
                    else -> "${Ui.dayShort(next.start.toLocalDate())} "
                }
                val time = if (next.allDay) "celý den" else if (next.start.isBefore(now)) "teď" else Ui.time(next.start)
                val text = "$day$time  ·  ${next.title}"
                chip.textSize = h * 0.05f
                var t = text
                while (chip.measureText(t) > w * 0.8f && t.length > 4) t = t.dropLast(2) + "…"
                val tw = chip.measureText(t)
                val dot = h * 0.018f
                val cw = tw + dot * 2 + h * 0.07f
                val cy = h * 0.64f
                val r = RectF((w - cw) / 2, cy - h * 0.042f, (w + cw) / 2, cy + h * 0.042f)
                fill.color = Ui.CARD_HI
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                fill.color = Ui.PRIMARY
                c.drawCircle(r.left + h * 0.035f + dot / 2, cy, dot / 2 + 1, fill)
                c.drawText(t, r.left + h * 0.035f + dot * 1.5f + h * 0.01f, cy + h * 0.017f, chip)
            }

            // --- pruh s předpovědí na 8 hodin
            val hrs = wx?.hours?.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }?.take(8)
            if (!hrs.isNullOrEmpty()) {
                val top = h * 0.72f
                val bottom = h * 0.92f
                val gap = h * 0.02f
                val cw = (w - pad * 2 - gap * (hrs.size - 1)) / hrs.size
                hrs.forEachIndexed { i, hr ->
                    val left = pad + i * (cw + gap)
                    val rect = RectF(left, top, left + cw, bottom)
                    fill.color = if (i == 0) Ui.CARD_HI else Ui.CARD
                    c.drawRoundRect(rect, h * 0.05f, h * 0.05f, fill)
                    val cx = rect.centerX()
                    small.textSize = h * 0.036f
                    c.drawText(if (i == 0) "teď" else "${hr.time.hour}", cx, top + h * 0.045f, small)
                    WeatherIcon.draw(c, hr.code, hr.isDay, cx, top + h * 0.09f, h * 0.065f)
                    temp.textSize = h * 0.045f
                    c.drawText(Ui.deg(hr.temp), cx, bottom - h * (if (hr.precipProb >= 20) 0.045f else 0.02f), temp)
                    if (hr.precipProb >= 20) {
                        rain.textSize = h * 0.03f
                        c.drawText("${hr.precipProb} %", cx, bottom - h * 0.012f, rain)
                    }
                }
            }
        }
    }
}
