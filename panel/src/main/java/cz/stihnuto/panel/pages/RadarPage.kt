package cz.stihnuto.panel.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Http
import cz.stihnuto.panel.Ui
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Srážkový radar. Výchozí: vlastní animace z RainViewer (lehké na paměť).
 * Když je v nastavení vyplněná adresa, zobrazí místo toho webovou stránku.
 */
class RadarPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val frame = FrameLayout(ctx)
    override val view: View = frame
    private var web: WebView? = null
    private var radar: RadarView? = null

    override fun onShow() {
        val url = app.settings.radarUrl.trim()
        if (url.isNotEmpty()) showWeb(url) else showNative()
    }

    override fun onHide() {
        // uvolnit paměť — hodiny jí mají málo
        web?.destroy(); web = null
        radar?.stop(); radar = null
        frame.removeAllViews()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(url: String) {
        val w = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            setBackgroundColor(Ui.BG)
            loadUrl(url)
        }
        web = w
        frame.addView(w, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showNative() {
        val r = RadarView(ctx, app.settings.lat, app.settings.lon, app.settings.radarZoom)
        radar = r
        frame.addView(r, FrameLayout.LayoutParams(-1, -1))
        r.start()
    }

    private class RadarView(ctx: Context, val lat: Double, val lon: Double, val zoom: Int) : View(ctx) {
        private val app = App.instance
        private var base: Bitmap? = null
        private val frames = mutableListOf<Pair<Long, Bitmap>>()
        private var shown = 0
        private var status: String? = "Načítám radar…"
        private var running = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val text = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM)
        private val small = Ui.paint(Ui.DIM, 1f)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        private val animate = object : Runnable {
            override fun run() {
                if (!running) return
                if (frames.isNotEmpty()) {
                    shown = (shown + 1) % frames.size
                    invalidate()
                }
                app.main.postDelayed(this, if (shown == frames.size - 1) 2000 else 500)
            }
        }
        private val reload = Runnable { load() }

        fun start() {
            running = true
            post { load() }
        }

        fun stop() {
            running = false
            app.main.removeCallbacks(animate)
            app.main.removeCallbacks(reload)
            base?.recycle(); base = null
            frames.forEach { it.second.recycle() }
            frames.clear()
        }

        /** Pixelové souřadnice v Mercatorově projekci na dané úrovni přiblížení. */
        private fun worldPx(): Pair<Double, Double> {
            val n = 256.0 * (1 shl zoom)
            val x = (lon + 180) / 360 * n
            val r = Math.toRadians(lat)
            val y = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
            return x to y
        }

        private fun load() {
            val w = width
            val h = height
            if (w == 0 || h == 0 || !running) return
            app.io.execute {
                try {
                    val meta = JSONObject(Http.text("https://api.rainviewer.com/public/weather-maps.json"))
                    val host = meta.getString("host")
                    val past = meta.getJSONObject("radar").getJSONArray("past")
                    val list = (0 until past.length()).map { past.getJSONObject(it) }.takeLast(7)
                    val newBase = base ?: compose(w, h, Bitmap.Config.RGB_565, Ui.BG) { z, x, y ->
                        "https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
                    }
                    val newFrames = list.map { f ->
                        val path = f.getString("path")
                        f.getLong("time") to compose(w, h, Bitmap.Config.ARGB_8888, 0) { z, x, y ->
                            "$host$path/256/$z/$x/$y/2/1_1.png"
                        }
                    }
                    app.main.post {
                        if (!running) {
                            newFrames.forEach { it.second.recycle() }
                            if (newBase !== base) newBase.recycle()
                            return@post
                        }
                        base = newBase
                        frames.forEach { it.second.recycle() }
                        frames.clear(); frames += newFrames
                        shown = frames.size - 1
                        status = if (frames.isEmpty()) "Radar nemá data" else null
                        invalidate()
                        app.main.removeCallbacks(animate)
                        app.main.postDelayed(animate, 1500)
                    }
                } catch (e: Exception) {
                    app.main.post { status = "Radar nejde načíst (${e.message})"; invalidate() }
                }
                app.main.postDelayed(reload, 10 * 60_000L)
            }
        }

        /** Složí dlaždice mapy kolem domova do jednoho obrázku velikosti okna. */
        private fun compose(w: Int, h: Int, cfg: Bitmap.Config, bg: Int, url: (Int, Int, Int) -> String): Bitmap {
            val out = Bitmap.createBitmap(w, h, cfg)
            val c = Canvas(out)
            if (bg != 0) c.drawColor(bg)
            val scale = (h / 480f).coerceAtLeast(1f) // na 800×480 1:1
            val (cx, cy) = worldPx()
            val left = cx - w / 2 / scale
            val top = cy - h / 2 / scale
            val n = 1 shl zoom
            val tx0 = floor(left / 256).toInt()
            val ty0 = floor(top / 256).toInt()
            val tx1 = floor((left + w / scale) / 256).toInt()
            val ty1 = floor((top + h / scale) / 256).toInt()
            for (tx in tx0..tx1) for (ty in ty0..ty1) {
                if (ty < 0 || ty >= n) continue
                val bmp = Http.bitmap(url(zoom, ((tx % n) + n) % n, ty)) ?: continue
                val dx = ((tx * 256 - left) * scale).toFloat()
                val dy = ((ty * 256 - top) * scale).toFloat()
                c.drawBitmap(bmp, null, Rect(dx.toInt(), dy.toInt(), (dx + 256 * scale).toInt(), (dy + 256 * scale).toInt()), paint)
                bmp.recycle()
            }
            return out
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (running && w > 0 && frames.isEmpty()) load()
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            c.drawColor(Ui.BG)
            base?.let { c.drawBitmap(it, 0f, 0f, paint) }
            frames.getOrNull(shown)?.let { (time, bmp) ->
                paint.alpha = 210
                c.drawBitmap(bmp, 0f, 0f, paint)
                paint.alpha = 255
                val t = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault()).toLocalDateTime()
                text.textSize = h * 0.07f
                c.drawText(Ui.time(t), h * 0.04f, h * 0.1f, text)
                // ukazatel průběhu animace
                val bw = h * 0.05f
                for (i in frames.indices) {
                    dot.color = if (i == shown) Ui.TEXT else Ui.FAINT
                    c.drawRect(h * 0.04f + i * bw * 1.2f, h * 0.13f, h * 0.04f + i * bw * 1.2f + bw, h * 0.14f, dot)
                }
            }
            // domov
            dot.color = Ui.ACCENT
            c.drawCircle(width / 2f, h / 2, h * 0.014f, dot)
            dot.style = Paint.Style.STROKE; dot.strokeWidth = h * 0.006f
            c.drawCircle(width / 2f, h / 2, h * 0.03f, dot)
            dot.style = Paint.Style.FILL

            small.textSize = h * 0.03f
            small.textAlign = Paint.Align.RIGHT
            c.drawText("RainViewer · © OpenStreetMap · © CARTO", width - h * 0.03f, h * 0.05f, small)
            status?.let {
                text.textSize = h * 0.055f
                c.drawText(it, h * 0.04f, h * 0.5f, text)
            }
        }
    }
}
