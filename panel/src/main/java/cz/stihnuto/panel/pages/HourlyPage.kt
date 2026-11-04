package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDateTime

/** Graf na 24 hodin: teplota (čára), srážky (sloupce), ikony každé 3 hodiny. */
class HourlyPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val canvasView = HourlyView(ctx)
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

    private class HourlyView(ctx: Context) : View(ctx) {
        private val title = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM)
        private val clock = Ui.paint(Ui.TEXT, 1f, Ui.LIGHT, Paint.Align.RIGHT)
        private val label = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
        private val small = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.CENTER)
        private val rain = Ui.paint(Ui.BLUE, 1f, Ui.REGULAR, Paint.Align.CENTER)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val pad = h * 0.05f
            val now = LocalDateTime.now()

            title.textSize = h * 0.065f
            c.drawText("Příštích 24 hodin", pad, pad + h * 0.06f, title)
            clock.textSize = h * 0.09f
            c.drawText(Ui.time(now), w - pad, pad + h * 0.075f, clock)

            val wx = App.instance.weather.data
            if (wx == null) {
                small.textSize = h * 0.06f
                c.drawText(App.instance.weather.error ?: "Načítám počasí…", w / 2, h / 2, small)
                return
            }
            val start = wx.hours.indexOfFirst { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }
                .coerceAtLeast(0)
            val hours = wx.hours.drop(start).take(25)
            if (hours.size < 2) return

            val left = pad
            val right = w - pad
            val step = (right - left) / (hours.size - 1)
            fun x(i: Int) = left + i * step

            // pásma: ikony | teploty | srážky | hodiny
            val iconY = h * 0.25f
            val tTop = h * 0.36f
            val tBottom = h * 0.62f
            val rTop = h * 0.68f
            val rBottom = h * 0.86f
            val hourY = h * 0.93f

            val tMin = hours.minOf { it.temp }
            val tMax = hours.maxOf { it.temp }
            val span = (tMax - tMin).coerceAtLeast(4.0)
            val mid = (tMax + tMin) / 2
            fun ty(t: Double) = (tBottom - ((t - (mid - span / 2)) / span).toFloat() * (tBottom - tTop))

            // půlnoc — svislá čára a název dne
            hours.forEachIndexed { i, hr ->
                if (hr.time.hour == 0 && i > 0) {
                    fill.color = Ui.FAINT
                    c.drawRect(x(i) - 1f, h * 0.17f, x(i) + 1f, rBottom, fill)
                    small.textSize = h * 0.04f
                    c.drawText(Ui.dayName(hr.time.toLocalDate()), x(i), h * 0.165f, small)
                }
            }

            // srážky — sloupce (mm) a průhledná pravděpodobnost
            val maxMm = hours.maxOf { it.precip }.coerceAtLeast(2.0)
            hours.forEachIndexed { i, hr ->
                val bw = step * 0.62f
                if (hr.precipProb > 0) {
                    fill.color = 0x334FA3FF
                    val ph = (rBottom - rTop) * hr.precipProb / 100f
                    c.drawRoundRect(RectF(x(i) - bw / 2, rBottom - ph, x(i) + bw / 2, rBottom), bw / 4, bw / 4, fill)
                }
                if (hr.precip > 0.05) {
                    fill.color = Ui.BLUE
                    val mh = ((hr.precip / maxMm).toFloat() * (rBottom - rTop)).coerceAtLeast(h * 0.008f)
                    c.drawRoundRect(RectF(x(i) - bw / 2, rBottom - mh, x(i) + bw / 2, rBottom), bw / 4, bw / 4, fill)
                }
            }
            fill.color = Ui.FAINT
            c.drawRect(left, rBottom, right, rBottom + 2f, fill)

            // teplota — vyhlazená čára s výplní
            val path = Path()
            hours.forEachIndexed { i, hr ->
                val px = x(i); val py = ty(hr.temp)
                if (i == 0) path.moveTo(px, py) else {
                    val ppx = x(i - 1); val ppy = ty(hours[i - 1].temp)
                    path.cubicTo((ppx + px) / 2, ppy, (ppx + px) / 2, py, px, py)
                }
            }
            val area = Path(path).apply { lineTo(x(hours.size - 1), tBottom + h * 0.03f); lineTo(left, tBottom + h * 0.03f); close() }
            fill.shader = LinearGradient(0f, tTop, 0f, tBottom, 0x55FFC94A, 0x00FFC94A, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            fill.shader = null
            line.color = Ui.ACCENT
            line.strokeWidth = h * 0.011f
            c.drawPath(path, line)

            // popisky každé 3 hodiny
            for (i in hours.indices step 3) {
                val hr = hours[i]
                WeatherIcon.draw(c, hr.code, hr.isDay, x(i), iconY, h * 0.11f)
                label.textSize = h * 0.05f
                c.drawText(Ui.deg(hr.temp), x(i), ty(hr.temp) - h * 0.03f, label)
                fill.color = Ui.ACCENT
                c.drawCircle(x(i), ty(hr.temp), h * 0.012f, fill)
                small.textSize = h * 0.045f
                c.drawText(if (i == 0) "teď" else "${hr.time.hour}", x(i), hourY, small)
                if (hr.precipProb >= 10) {
                    rain.textSize = h * 0.036f
                    c.drawText("${hr.precipProb} %", x(i), rTop - h * 0.01f, rain)
                }
            }

            // legenda
            small.textSize = h * 0.032f
            small.textAlign = Paint.Align.RIGHT
            c.drawText("sloupce: srážky (mm) · světlé: pravděpodobnost", right, h * 0.985f, small)
            small.textAlign = Paint.Align.CENTER
        }
    }
}
