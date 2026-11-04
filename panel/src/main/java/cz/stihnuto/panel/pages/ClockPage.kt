package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherApi
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDateTime

/** Velké hodiny s datem a malým počasím. Vzor pro nové okno. */
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
    private val onWeather = { canvasView.invalidate() }

    override fun onShow() {
        app.main.post(tick)
        app.weather.listen(onWeather)
    }

    override fun onHide() {
        app.main.removeCallbacks(tick)
        app.weather.unlisten(onWeather)
    }

    private class ClockView(ctx: Context) : View(ctx) {
        private val time = Ui.paint(Ui.TEXT, 1f, Ui.LIGHT, Paint.Align.CENTER)
        private val sec = Ui.paint(Ui.DIM, 1f, Ui.LIGHT, Paint.Align.LEFT)
        private val date = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.CENTER)
        private val temp = Ui.paint(Ui.TEXT, 1f, Ui.LIGHT, Paint.Align.LEFT)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val now = LocalDateTime.now()

            time.textSize = h * 0.42f
            val hhmm = "%d:%02d".format(now.hour, now.minute)
            val tw = time.measureText(hhmm)
            val baseline = h * 0.56f
            c.drawText(hhmm, w / 2, baseline, time)
            sec.textSize = h * 0.09f
            c.drawText("%02d".format(now.second), w / 2 + tw / 2 + h * 0.02f, baseline, sec)

            date.textSize = h * 0.075f
            c.drawText(Ui.dateLong(now.toLocalDate()), w / 2, baseline + h * 0.13f, date)

            val wx = App.instance.weather.data ?: return
            val cur = wx.current
            val iconSize = h * 0.13f
            temp.textSize = h * 0.075f
            val label = "${Ui.deg(cur.temp)}  ${WeatherApi.describe(cur.code)}"
            val total = iconSize + h * 0.02f + temp.measureText(label)
            val x0 = w / 2 - total / 2
            val y = h * 0.86f
            WeatherIcon.draw(c, cur.code, cur.isDay, x0 + iconSize / 2, y - iconSize * 0.3f, iconSize)
            c.drawText(label, x0 + iconSize + h * 0.02f, y, temp)
        }
    }
}
