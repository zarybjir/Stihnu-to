package cz.stihnuto.panel.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import cz.stihnuto.panel.App
import cz.stihnuto.panel.RadarRepo
import cz.stihnuto.panel.Ui
import java.time.Instant
import java.time.ZoneId

/**
 * Srážkový radar ČHMÚ za poslední hodinu. Data se stahují na pozadí
 * ([RadarRepo]), okno je jen zobrazuje. Když je v nastavení vyplněná
 * webová adresa, ukáže místo toho ji.
 */
class RadarPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val frame = FrameLayout(ctx)
    override val view: View = frame
    private var web: WebView? = null
    private var radar: RadarView? = null

    override fun onShow() {
        val url = app.settings.radarUrl.trim()
        // jen webová adresa — nic jiného (soubory v hodinách, javascript:, intent:) se nenačte
        if (url.startsWith("http://") || url.startsWith("https://")) showWeb(url) else showNative()
    }

    override fun onHide() {
        web?.destroy(); web = null
        radar?.stop(); radar = null
        frame.removeAllViews()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(url: String) {
        val w = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // stránka je cizí obsah: žádný přístup k souborům aplikace, k poloze ani ven z webu
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return scheme != "http" && scheme != "https" // jiné odkazy (intent:, market:, file:) neotevírat
                }
            }
            setBackgroundColor(Ui.BG)
            loadUrl(url)
        }
        web = w
        frame.addView(w, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showNative() {
        val r = RadarView(ctx)
        radar = r
        frame.addView(r, FrameLayout.LayoutParams(-1, -1))
        r.start()
    }

    private class RadarView(ctx: Context) : View(ctx) {
        private val app = App.instance
        private val repo = app.radar
        private val u = Ui.unit(ctx)
        private var shown = 0
        private var running = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val text = Ui.paint(Ui.TEXT, 1f, Ui.BLACK)
        private val small = Ui.paint(Ui.DIM, 1f, Ui.MEDIUM)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val btn = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val path = android.graphics.Path()
        private val onData = {
            shown = (repo.frames.size - 1).coerceAtLeast(0)
            invalidate()
        }

        /** Tlačítka na mapě: kruh (střed, poloměr) a co dělají. */
        private class Btn(val id: String, var x: Float = 0f, var y: Float = 0f, var r: Float = 0f)
        private val buttons = listOf("in", "out", "up", "down", "left", "right", "home").map { Btn(it) }
        private var pressed: Btn? = null

        private val animate = object : Runnable {
            override fun run() {
                if (!running) return
                val n = repo.frames.size
                if (n > 0) {
                    shown = (shown + 1) % n
                    invalidate()
                }
                app.main.postDelayed(this, if (shown == n - 1) 2000 else 500)
            }
        }

        fun start() {
            running = true
            repo.listen(onData)
            if (repo.frames.isEmpty()) repo.refresh()
            app.main.postDelayed(animate, 1500)
        }

        fun stop() {
            running = false
            repo.unlisten(onData)
            app.main.removeCallbacks(animate)
        }

        override fun onDraw(c: Canvas) {
            val w = width
            val hi = height
            val h = hi.toFloat()
            c.drawColor(Ui.BG)
            // podklad: i starý (z jiného pohledu) se přesune/roztáhne na správné místo, než se složí nový
            repo.base?.let { b ->
                rect.set(repo.toScreenX(b.left, w, hi), repo.toScreenY(b.top, w, hi),
                    repo.toScreenX(b.right, w, hi), repo.toScreenY(b.bottom, w, hi))
                c.drawBitmap(b.bmp, null, rect, paint)
            }

            val frames = repo.frames
            frames.getOrNull(shown)?.let { (time, bmp) ->
                rect.set(
                    repo.toScreenX(RadarRepo.normX(RadarRepo.CZ_W), w, hi), repo.toScreenY(RadarRepo.normY(RadarRepo.CZ_N), w, hi),
                    repo.toScreenX(RadarRepo.normX(RadarRepo.CZ_E), w, hi), repo.toScreenY(RadarRepo.normY(RadarRepo.CZ_S), w, hi),
                )
                if (!bmp.isRecycled) c.drawBitmap(bmp, null, rect, paint)
                val t = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault()).toLocalDateTime()
                text.textSize = h * 0.08f
                c.drawText(Ui.time(t), h * 0.05f, h * 0.12f, text)
                val bw = h * 0.05f
                for (i in frames.indices) {
                    dot.color = if (i == shown) Ui.PRIMARY else Ui.FAINT
                    val x = h * 0.05f + i * bw * 1.25f
                    rect.set(x, h * 0.15f, x + bw, h * 0.165f)
                    c.drawRoundRect(rect, h, h, dot)
                }
            }
            // domov (místo z nastavení), ať je vidět i po posunu mapy
            val hx = repo.toScreenX(repo.homeX(), w, hi)
            val hy = repo.toScreenY(repo.homeY(), w, hi)
            dot.color = Ui.PRIMARY
            c.drawCircle(hx, hy, h * 0.014f, dot)
            dot.style = Paint.Style.STROKE; dot.strokeWidth = h * 0.006f
            c.drawCircle(hx, hy, h * 0.032f, dot)
            dot.style = Paint.Style.FILL

            small.textSize = h * 0.03f
            small.textAlign = Paint.Align.LEFT
            c.drawText("Radar ČHMÚ · © OpenStreetMap" + if (repo.mapLoading) " · načítám mapu…" else "",
                h * 0.05f, h - h * 0.04f, small)
            val status = when {
                !repo.inCzechia -> "Vestavěný radar je jen pro Česko. Jinde vlož v nastavení adresu webového radaru."
                repo.error != null && frames.isEmpty() -> repo.error
                frames.isEmpty() -> "Načítám radar…"
                else -> null
            }
            status?.let {
                text.textSize = h * 0.05f
                c.drawText(it, h * 0.05f, h * 0.5f, text)
            }
            drawControls(c, w.toFloat(), h)
        }

        /** Vpravo nahoře + a −, vpravo dole kříž se šipkami a uprostřed návrat domů. */
        private fun drawControls(c: Canvas, w: Float, h: Float) {
            val r = u * 6f
            val gap = u * 2f
            val right = w - u * 3f - r
            fun place(id: String, x: Float, y: Float) { buttons.first { it.id == id }.apply { this.x = x; this.y = y; this.r = r } }
            place("in", right, u * 3f + r)
            place("out", right - 2 * r - gap, u * 3f + r)
            val dc = h - u * 4f - 3 * r - gap // střed kříže
            val dx = right - 2 * r - gap
            place("home", dx, dc)
            place("up", dx, dc - 2 * r - gap)
            place("down", dx, dc + 2 * r + gap)
            place("left", dx - 2 * r - gap, dc)
            place("right", dx + 2 * r + gap, dc)

            for (b in buttons) {
                val enabled = when (b.id) {
                    "in" -> repo.canZoomIn
                    "out" -> repo.canZoomOut
                    "home" -> !repo.atHome
                    else -> true
                }
                btn.color = if (b === pressed) Ui.FAINT else 0xD92D2F31.toInt()
                c.drawCircle(b.x, b.y, b.r, btn)
                glyph.color = if (enabled) Ui.TEXT else Ui.FAINT
                glyph.strokeWidth = b.r * 0.16f
                val k = b.r * 0.42f
                path.reset()
                when (b.id) {
                    "in" -> { c.drawLine(b.x - k, b.y, b.x + k, b.y, glyph); c.drawLine(b.x, b.y - k, b.x, b.y + k, glyph) }
                    "out" -> c.drawLine(b.x - k, b.y, b.x + k, b.y, glyph)
                    "up" -> { path.moveTo(b.x - k, b.y + k * 0.45f); path.lineTo(b.x, b.y - k * 0.55f); path.lineTo(b.x + k, b.y + k * 0.45f) }
                    "down" -> { path.moveTo(b.x - k, b.y - k * 0.45f); path.lineTo(b.x, b.y + k * 0.55f); path.lineTo(b.x + k, b.y - k * 0.45f) }
                    "left" -> { path.moveTo(b.x + k * 0.45f, b.y - k); path.lineTo(b.x - k * 0.55f, b.y); path.lineTo(b.x + k * 0.45f, b.y + k) }
                    "right" -> { path.moveTo(b.x - k * 0.45f, b.y - k); path.lineTo(b.x + k * 0.55f, b.y); path.lineTo(b.x - k * 0.45f, b.y + k) }
                    "home" -> {
                        glyph.strokeWidth = b.r * 0.12f
                        c.drawCircle(b.x, b.y, k * 0.75f, glyph)
                        glyph.style = Paint.Style.FILL
                        c.drawCircle(b.x, b.y, k * 0.28f, glyph)
                        glyph.style = Paint.Style.STROKE
                    }
                }
                if (!path.isEmpty) c.drawPath(path, glyph)
            }
        }

        private fun hit(x: Float, y: Float) = buttons.firstOrNull { b ->
            val dx = x - b.x; val dy = y - b.y
            dx * dx + dy * dy <= (b.r * 1.15f) * (b.r * 1.15f) // o kousek větší plocha pro prst
        }

        /** Klepnutí na tlačítka; jinde se dotyk nechá být (přejetím se dál přepínají okna). */
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressed = hit(e.x, e.y) ?: return false
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val b = pressed
                    pressed = null
                    if (b != null && hit(e.x, e.y) === b) when (b.id) {
                        "in" -> repo.zoomBy(1)
                        "out" -> repo.zoomBy(-1)
                        "up" -> repo.pan(0.0, -1.0 / 3, width, height)
                        "down" -> repo.pan(0.0, 1.0 / 3, width, height)
                        "left" -> repo.pan(-1.0 / 3, 0.0, width, height)
                        "right" -> repo.pan(1.0 / 3, 0.0, width, height)
                        "home" -> repo.resetView()
                    }
                    invalidate()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            }
            return true
        }
    }
}
