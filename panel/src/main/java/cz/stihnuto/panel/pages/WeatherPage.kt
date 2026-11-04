package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherApi
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDate
import java.time.LocalDateTime

/** Dnešek vlevo (s hodinami), dalších 6 dní vpravo. */
class WeatherPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val canvasView = WeatherView(ctx)
    override val view: View = canvasView
    private val onWeather = { canvasView.invalidate() }
    private val tick = object : Runnable {
        override fun run() {
            canvasView.invalidate()
            app.main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onShow() {
        app.weather.listen(onWeather)
        app.weather.refresh()
        app.main.post(tick)
    }

    override fun onHide() {
        app.weather.unlisten(onWeather)
        app.main.removeCallbacks(tick)
    }

    private class WeatherView(ctx: Context) : View(ctx) {
        private val big = Ui.paint(Ui.TEXT, 1f, Ui.LIGHT)
        private val txt = Ui.paint(Ui.TEXT, 1f)
        private val dim = Ui.paint(Ui.DIM, 1f)
        private val right = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.RIGHT)
        private val rightDim = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.RIGHT)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val pad = h * 0.05f
            val now = LocalDateTime.now()
            val repo = App.instance.weather
            val wx = repo.data

            // --- hodiny a datum vlevo nahoře
            big.textSize = h * 0.14f
            c.drawText(Ui.time(now), pad, pad + h * 0.11f, big)
            dim.textSize = h * 0.05f
            val place = App.instance.settings.place
            c.drawText("${Ui.dateLong(now.toLocalDate())} · $place", pad, pad + h * 0.18f, dim)

            if (wx == null) {
                txt.textSize = h * 0.06f
                c.drawText(repo.error ?: "Načítám počasí…", pad, h * 0.5f, txt)
                return
            }
            val cur = wx.current
            val today = wx.days.firstOrNull()

            // --- aktuálně
            val iconS = h * 0.28f
            WeatherIcon.draw(c, cur.code, cur.isDay, pad + iconS / 2, h * 0.42f, iconS)
            big.textSize = h * 0.22f
            c.drawText(Ui.deg(cur.temp), pad + iconS + h * 0.03f, h * 0.5f, big)
            txt.textSize = h * 0.055f
            c.drawText(WeatherApi.describe(cur.code), pad, h * 0.63f, txt)
            dim.textSize = h * 0.045f
            val todayRange = today?.let { "  ·  ${Ui.deg(it.max)} / ${Ui.deg(it.min)}" } ?: ""
            c.drawText("pocitově ${Ui.deg(cur.feels)}$todayRange", pad, h * 0.69f, dim)

            // --- podrobnosti 3×2
            val leftW = w * 0.47f
            val cellW = (leftW - pad) / 3
            val rainToday = today?.let { "${Ui.num(it.precipSum)} mm" } ?: "–"
            val cells = listOf(
                "vítr" to "${Math.round(cur.wind)} km/h",
                "nárazy" to "${Math.round(cur.gusts)} km/h",
                "vlhkost" to "${cur.humidity} %",
                "srážky dnes" to rainToday,
                "slunce" to (today?.let { d ->
                    "${d.sunrise?.let(Ui::time) ?: "–"}–${d.sunset?.let(Ui::time) ?: "–"}"
                } ?: "–"),
                "tlak" to "${Math.round(cur.pressure)} hPa",
            )
            cells.forEachIndexed { i, (label, value) ->
                val x = pad + (i % 3) * cellW
                val y = h * 0.79f + (i / 3) * h * 0.11f
                dim.textSize = h * 0.036f
                c.drawText(label, x, y, dim)
                txt.textSize = h * 0.048f
                c.drawText(value, x, y + h * 0.055f, txt)
            }

            // --- dny vpravo
            val x0 = leftW + pad * 0.5f
            val x1 = w - pad
            val days = wx.days.take(7)
            val rowH = (h - pad * 2) / days.size
            val lo = days.minOf { it.min }
            val hi = days.maxOf { it.max }
            fill.color = Ui.CARD
            c.drawRoundRect(RectF(x0 - pad * 0.4f, pad * 0.6f, x1 + pad * 0.4f, h - pad * 0.6f), h * 0.04f, h * 0.04f, fill)
            days.forEachIndexed { i, d ->
                val top = pad + i * rowH
                val cy = top + rowH / 2
                val label = when (d.date) {
                    LocalDate.now() -> "Dnes"
                    LocalDate.now().plusDays(1) -> "Zítra"
                    else -> "${Ui.dayShort(d.date)} ${d.date.dayOfMonth}."
                }
                txt.textSize = rowH * 0.36f
                txt.typeface = if (i == 0) Ui.MEDIUM else Ui.REGULAR
                c.drawText(label, x0, cy + rowH * 0.13f, txt)

                val iconX = x0 + (x1 - x0) * 0.27f
                WeatherIcon.draw(c, d.code, true, iconX, cy, rowH * 0.8f)

                // srážky
                val rainX = x0 + (x1 - x0) * 0.47f
                if (d.precipProb >= 10 || d.precipSum >= 0.1) {
                    dim.textSize = rowH * 0.27f
                    dim.color = Ui.BLUE
                    c.drawText("${d.precipProb} %", rainX - rowH * 0.25f, cy - rowH * 0.02f, dim)
                    dim.color = Ui.DIM
                    dim.textSize = rowH * 0.22f
                    c.drawText("${Ui.num(d.precipSum)} mm", rainX - rowH * 0.25f, cy + rowH * 0.26f, dim)
                }

                // teplotní rozsah
                val barL = x0 + (x1 - x0) * 0.72f
                val barR = x1 - (x1 - x0) * 0.12f
                rightDim.textSize = rowH * 0.32f
                c.drawText(Ui.deg(d.min), barL - rowH * 0.12f, cy + rowH * 0.12f, rightDim)
                right.textSize = rowH * 0.32f
                c.drawText(Ui.deg(d.max), x1, cy + rowH * 0.12f, right)
                val span = (hi - lo).coerceAtLeast(1.0)
                val a = barL + ((d.min - lo) / span).toFloat() * (barR - barL - rowH * 0.1f)
                val b = barL + rowH * 0.1f + ((d.max - lo) / span).toFloat() * (barR - barL - rowH * 0.1f)
                fill.color = Ui.FAINT
                c.drawRoundRect(RectF(barL, cy - rowH * 0.05f, barR, cy + rowH * 0.05f), rowH, rowH, fill)
                fill.shader = LinearGradient(a, 0f, b, 0f, tempColor(d.min), tempColor(d.max), Shader.TileMode.CLAMP)
                c.drawRoundRect(RectF(a, cy - rowH * 0.05f, b, cy + rowH * 0.05f), rowH, rowH, fill)
                fill.shader = null
            }
        }

        private fun tempColor(t: Double): Int = when {
            t < -5 -> 0xFF7FB8FF.toInt()
            t < 3 -> 0xFF4FA3FF.toInt()
            t < 10 -> 0xFF5CD6C0.toInt()
            t < 18 -> 0xFF9BD65C.toInt()
            t < 25 -> 0xFFFFC94A.toInt()
            else -> 0xFFFF7A45.toInt()
        }
    }
}
