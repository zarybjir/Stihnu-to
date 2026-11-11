package cz.stihnuto.panel.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Hlavní okno: vlevo co největší čas (hodiny modře, minuty bíle), nad ním
 * datum, pod ním nejbližší událost. Vpravo úzký sloupec s předpovědí (ikona,
 * teplota, šance na déšť, mm) na dnešek a zítřek — roluje se prstem plynule,
 * se setrvačností. Sloupec se zúží, když ve výřezu neprší, a plynule roztáhne,
 * když déšť přijde. Pod časem je souhrn dne.
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
        canvasView.backToNow(animate = false) // příště zase od „nyní“
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
        private val temp = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.RIGHT)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fade = Paint()
        private val rect = RectF()
        private val cardPath = Path()
        private val caretPath = Path()

        // --- rolování hodinovky (plynulé, po pixelech)
        private val scroller = OverScroller(ctx)
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
        private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity
        private var tracker: VelocityTracker? = null
        /** O kolik pixelů je seznam hodin odrolovaný (0 = nahoře je „nyní“). */
        private var scrollPx = 0f
        private var maxScroll = 0f
        /** Kam seznam po puštění prstu plynule dojíždí (celý řádek); NaN = nikam. */
        private var settleTo = Float.NaN
        /** Prst je na sloupci (i když se ještě nehýbe) / už se jím roluje. */
        private var touching = false
        private var dragging = false
        private var downX = 0f
        private var downY = 0f
        private var lastY = 0f
        /** Levý okraj sloupce — dotyk vlevo od něj patří přepínání oken. */
        private var colLeft = 1e9f
        /** Aktuální (animovaná) šířka sloupce a jestli má být široký (s deštěm). */
        private var colW = 0f
        private var wide = false
        /** Po chvíli bez dotyku se hodinovka sama vrátí na „nyní“. */
        private val returnToNow = Runnable { backToNow(animate = true) }

        // velikost písma času se počítá jen při změně místa, ne v každém snímku rolování
        private var fitWidth = -1f
        private var fitHeight = -1f
        private var fitSize = 0f
        // přechody na okrajích sloupce (naznačí, že seznam pokračuje)
        private var fadeTop: Shader? = null
        private var fadeBottom: Shader? = null
        private var fadeKey = 0f

        fun backToNow(animate: Boolean) {
            removeCallbacks(returnToNow)
            scroller.forceFinished(true)
            settleTo = Float.NaN
            if (animate && scrollPx > 0f) {
                settleTo = 0f
                scroller.startScroll(0, scrollPx.toInt(), 0, -scrollPx.toInt(), 700)
                postInvalidateOnAnimation()
            } else if (scrollPx != 0f) {
                scrollPx = 0f
                invalidate()
            }
        }

        override fun onDraw(c: Canvas) {
            val app = App.instance
            val h = height.toFloat()
            // s horní lištou je obrazovka nižší — velikost hodinovky ale drž jako bez ní
            val hu = h + (if (app.settings.topBar) Ui.unit(context) * 12f else 0f)
            val w = width.toFloat()
            val pad = h * 0.06f
            val now = LocalDateTime.now()
            val wx = app.weather.data

            // setrvačnost po puštění prstu, dosednutí na celý řádek a návrat na „nyní“
            if (scroller.computeScrollOffset()) {
                scrollPx = scroller.currY.toFloat()
                if (scroller.isFinished && !settleTo.isNaN()) { scrollPx = settleTo; settleTo = Float.NaN }
                postInvalidateOnAnimation()
            }

            // --- vpravo úzký sloupec s předpovědí (dnešek + zítřek do půlnoci)
            val t0 = pad * 0.5f
            val t1 = h - pad * 0.9f
            val visible = if (app.settings.topBar) 9 else 10
            val rowH = (t1 - t0 - h * 0.016f) / visible
            val thisHour = now.withMinute(0).withSecond(0).withNano(0)
            val cutoff = now.toLocalDate().plusDays(2).atStartOfDay() // půlnoc po zítřku
            val allH = wx?.hours?.filter { !it.time.isBefore(thisHour) && it.time.isBefore(cutoff) }.orEmpty()
            val total = allH.size
            maxScroll = maxOf(0, total - visible) * rowH
            scrollPx = scrollPx.coerceIn(0f, maxScroll)
            // Prst pryč a setrvačnost dojela: seznam měkce dosedne na nejbližší celý řádek,
            // ať v klidu nezůstávají nahoře a dole useknuté půlřádky.
            if (!touching && scroller.isFinished && settleTo.isNaN()) {
                val snapped = (Math.round(scrollPx / rowH) * rowH).coerceIn(0f, maxScroll)
                if (abs(snapped - scrollPx) > 0.5f) {
                    settleTo = snapped
                    scroller.startScroll(0, scrollPx.toInt(), 0, Math.round(snapped - scrollPx), 220)
                    postInvalidateOnAnimation()
                }
            }
            // první (i jen zčásti) viditelný řádek a o kolik je vysunutý nahoru
            val first = (scrollPx / rowH).toInt().coerceIn(0, maxOf(0, total - 1))
            val frac = scrollPx - first * rowH

            // Šířka sloupce se řídí deštěm ve výřezu: roztáhne se, když je řádek s deštěm vidět
            // aspoň z půlky, a zúží, až žádný takový vidět není vůbec (ať to při pomalém
            // rolování na hraně nepřeskakuje sem a tam).
            fun rainy(i: Int) = allH[i].let { it.precipProb >= 20 || it.precip >= 0.1 }
            val half = Math.round(scrollPx / rowH)
            val rainHalf = (half until minOf(total, half + visible)).any { rainy(it) }
            val rainAny = (first until minOf(total, first + visible + 1)).any { rainy(it) }
            if (rainHalf) wide = true else if (!rainAny) wide = false
            val target = if (total == 0) 0f else hu * (if (wide) 0.47f else 0.31f)
            if (colW <= 0f) colW = target else {
                val d = target - colW
                if (abs(d) > 0.5f) { colW += d * 0.35f; postInvalidateOnAnimation() } else colW = target
            }
            // %/mm kresli, až je sloupec skoro roztažený, ať text během animace nevyčnívá
            val showRain = colW > hu * 0.45f
            val r1 = w - pad * 0.3f
            val r0 = r1 - colW

            // --- vlevo čas, velikost podle dostupné šířky (číslice Roboto mají stejnou šířku)
            val leftR = r0 - pad * 0.25f
            val leftL = pad * 0.1f
            val cx = leftL + (leftR - leftL) / 2
            val hh = "${now.hour}"
            val mmText = ":%02d".format(now.minute)
            val avail = leftR - leftL - pad * 0.25f
            if (avail != fitWidth || h != fitHeight) {
                var s = h * 0.66f
                fun timeWidth(): Float {
                    hours.textSize = s; minutes.textSize = s
                    return hours.measureText("00") + minutes.measureText(":00")
                }
                while (timeWidth() > avail && s > 10) s *= 0.99f
                fitSize = s; fitWidth = avail; fitHeight = h
            }
            val size = fitSize
            hours.textSize = size; minutes.textSize = size
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
            val next = app.calendar.upcoming().firstOrNull()
            if (next != null) {
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
                rect.set(l, cy - h * 0.04f, l + pw, cy + h * 0.04f)
                c.drawRoundRect(rect, h * 0.04f, h * 0.04f, fill)
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
                val top = if (next != null) chipY + h * 0.045f else base
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
            if (total == 0) return
            colLeft = r0
            val rad = h * 0.04f
            fill.color = Ui.CARD
            rect.set(r0, t0, r1, t1)
            c.drawRoundRect(rect, rad, rad, fill)
            val top = t0 + h * 0.008f
            val bottom = t1 - h * 0.008f

            // řádky se kreslí posunuté o zlomek výšky a oříznuté do karty (i do jejích oblých rohů)
            c.save()
            cardPath.reset()
            cardPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
            c.clipPath(cardPath)
            c.clipRect(r0, top, r1, bottom)
            var k = 0
            while (first + k < total) {
                val y = top + k * rowH - frac
                if (y >= bottom) break
                val hr = allH[first + k]
                val nowRow = first + k == 0
                val m = y + rowH / 2
                if (nowRow) {
                    fill.color = Ui.CARD_HI
                    rect.set(r0 + h * 0.008f, y + h * 0.002f, r1 - h * 0.008f, y + rowH - h * 0.002f)
                    c.drawRoundRect(rect, rowH / 2, rowH / 2, fill)
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
                if (showRain && (hr.precipProb >= 20 || hr.precip >= 0.1)) {
                    rain.textSize = hu * 0.035f
                    c.drawText("${hr.precipProb}%", r0 + hu * 0.365f, m + hu * 0.013f, rain)
                    mm.textSize = hu * 0.032f
                    c.drawText(Ui.num(hr.precip), r1 - hu * 0.022f, m + hu * 0.012f, mm)
                }
                k++
            }
            // useknuté řádky u okrajů (jen během rolování) plynule mizí do barvy karty
            val more = scrollPx < maxScroll - 0.5f
            val cut = frac > 0.5f && frac < rowH - 0.5f
            val fadeH = rowH * 0.6f
            if (fadeTop == null || fadeKey != top + bottom + fadeH) {
                val clear = Ui.CARD and 0x00FFFFFF
                fadeTop = LinearGradient(0f, top, 0f, top + fadeH, Ui.CARD, clear, Shader.TileMode.CLAMP)
                fadeBottom = LinearGradient(0f, bottom - fadeH, 0f, bottom, clear, Ui.CARD, Shader.TileMode.CLAMP)
                fadeKey = top + bottom + fadeH
            }
            if (cut) {
                fade.shader = fadeTop; c.drawRect(r0, top, r1, top + fadeH, fade)
                fade.shader = fadeBottom; c.drawRect(r0, bottom - fadeH, r1, bottom, fade)
            }
            c.restore()

            // náznak rolování: šipky, když je nahoře/dole víc hodin
            val cxh = (r0 + r1) / 2
            if (scrollPx > 0.5f) caret(c, cxh, t0 + h * 0.022f, hu * 0.02f, true)
            if (more) caret(c, cxh, t1 - h * 0.022f, hu * 0.02f, false)
        }

        private fun caret(c: Canvas, cx: Float, cy: Float, r: Float, up: Boolean) {
            val d = if (up) -1f else 1f
            caretPath.reset()
            caretPath.moveTo(cx - r, cy - r * 0.5f * d)
            caretPath.lineTo(cx, cy + r * 0.5f * d)
            caretPath.lineTo(cx + r, cy - r * 0.5f * d)
            fill.style = Paint.Style.STROKE; fill.strokeWidth = r * 0.4f; fill.color = Ui.DIM
            c.drawPath(caretPath, fill)
            fill.style = Paint.Style.FILL
        }

        /**
         * Svislý tah po sloupci roluje hodinovku (obsah jede s prstem, po puštění dojede
         * setrvačností). Vodorovné přejetí se nechá být — přepne okno jako všude jinde.
         */
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (maxScroll <= 0f || e.x < colLeft) return false // mimo sloupec / není co rolovat
                    removeCallbacks(returnToNow)
                    scroller.forceFinished(true)
                    settleTo = Float.NaN
                    downX = e.x; downY = e.y; lastY = e.y
                    touching = true
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    if (!dragging) {
                        val dy = abs(e.y - downY)
                        if (dy > slop && dy > abs(e.x - downX)) {
                            dragging = true
                            lastY = e.y // bez skoku o délku „rozjezdu“
                            parent?.requestDisallowInterceptTouchEvent(true) // teď už to není přepnutí okna
                        }
                    }
                    if (dragging) {
                        scrollPx = (scrollPx + lastY - e.y).coerceIn(0f, maxScroll)
                        lastY = e.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    var swipe = 0
                    if (dragging) tracker?.let { t ->
                        t.addMovement(e)
                        t.computeCurrentVelocity(1000, maxFling.toFloat())
                        val v = -t.yVelocity // prst nahoru = obsah jede dál dolů
                        if (abs(v) > minFling) {
                            scroller.fling(0, scrollPx.toInt(), 0, v.toInt(), 0, 0, 0, maxScroll.toInt())
                            postInvalidateOnAnimation()
                        }
                    } else {
                        // Šikmé přejetí do strany, které se nestalo rolováním: přepni okno stejně,
                        // jako kdyby začalo kdekoli jinde na obrazovce.
                        val dx = e.x - downX
                        if (abs(dx) > width * 0.1f && abs(dx) > abs(e.y - downY)) swipe = if (dx < 0) 1 else -1
                    }
                    endTouch()
                    if (swipe != 0) (parent as? cz.stihnuto.panel.Pager)?.onSwipe?.invoke(swipe)
                }
                MotionEvent.ACTION_CANCEL -> endTouch()
            }
            return true
        }

        private fun endTouch() {
            touching = false
            dragging = false
            tracker?.recycle(); tracker = null
            parent?.requestDisallowInterceptTouchEvent(false)
            postDelayed(returnToNow, 45_000)
            invalidate() // ať se spustí dosednutí na celý řádek
        }
    }
}
