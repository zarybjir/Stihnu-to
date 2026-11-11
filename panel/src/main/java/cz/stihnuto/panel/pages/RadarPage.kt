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
        private var shown = 0
        private var running = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val text = Ui.paint(Ui.TEXT, 1f, Ui.BLACK)
        private val small = Ui.paint(Ui.DIM, 1f, Ui.MEDIUM)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val onData = {
            shown = (repo.frames.size - 1).coerceAtLeast(0)
            invalidate()
        }

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
            val h = height.toFloat()
            c.drawColor(Ui.BG)
            repo.base?.let { c.drawBitmap(it, null, RectF(0f, 0f, w.toFloat(), h), paint) }

            val (left, top, scale) = repo.viewport(w, height)
            val (x0, y0) = repo.worldPx(RadarRepo.CZ_W, RadarRepo.CZ_N)
            val (x1, y1) = repo.worldPx(RadarRepo.CZ_E, RadarRepo.CZ_S)
            val dst = RectF(
                ((x0 - left) * scale).toFloat(), ((y0 - top) * scale).toFloat(),
                ((x1 - left) * scale).toFloat(), ((y1 - top) * scale).toFloat(),
            )
            val frames = repo.frames
            frames.getOrNull(shown)?.let { (time, bmp) ->
                if (!bmp.isRecycled) c.drawBitmap(bmp, null, dst, paint)
                val t = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault()).toLocalDateTime()
                text.textSize = h * 0.08f
                c.drawText(Ui.time(t), h * 0.05f, h * 0.12f, text)
                val bw = h * 0.05f
                for (i in frames.indices) {
                    dot.color = if (i == shown) Ui.PRIMARY else Ui.FAINT
                    val x = h * 0.05f + i * bw * 1.25f
                    c.drawRoundRect(RectF(x, h * 0.15f, x + bw, h * 0.165f), h, h, dot)
                }
            }
            // domov
            dot.color = Ui.PRIMARY
            c.drawCircle(w / 2f, h / 2, h * 0.014f, dot)
            dot.style = Paint.Style.STROKE; dot.strokeWidth = h * 0.006f
            c.drawCircle(w / 2f, h / 2, h * 0.032f, dot)
            dot.style = Paint.Style.FILL

            small.textSize = h * 0.03f
            small.textAlign = Paint.Align.RIGHT
            c.drawText("Radar ČHMÚ · © OpenStreetMap", w - h * 0.03f, h * 0.05f, small)
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
        }
    }
}
